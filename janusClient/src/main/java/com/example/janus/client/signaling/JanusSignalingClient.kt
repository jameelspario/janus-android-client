package com.example.janus.client.signaling

import com.example.janus.client.SDKLogger
import com.example.janus.client.SDKUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class SignalingEvent {
    object Connected : SignalingEvent()
    data class Disconnected(val code: Int, val reason: String) : SignalingEvent()
    data class Error(val error: Throwable) : SignalingEvent()
    data class UnsolicitedEvent(val message: Map<String, Any>, val jsep: Map<String, Any>?) : SignalingEvent()
}

/**
 * Modern, coroutine-powered Janus WebSocket signaling client.
 * Uses CompletableDeferred for asynchronous request-response transactions over WebSocket.
 */
class JanusSignalingClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val TAG = "JanusSignalingClient"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val pendingTransactions = ConcurrentHashMap<String, CompletableDeferred<JanusResponse>>()

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private var connectDeferred: CompletableDeferred<Unit>? = null

    suspend fun connect(url: String, timeoutMs: Long = 15_000L) {
        if (isConnected.get()) return

        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred

        val request = Request.Builder()
            .header("Sec-WebSocket-Protocol", "janus-protocol")
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                SDKLogger.info(TAG, "WebSocket connected")
                isConnected.set(true)
                deferred.complete(Unit)
                scope.launch { _events.emit(SignalingEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SDKLogger.error(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                if (deferred.isActive) deferred.completeExceptionally(t)
                scope.launch { _events.emit(SignalingEvent.Error(t)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                SDKLogger.info(TAG, "WebSocket closed: $code $reason")
                isConnected.set(false)
                scope.launch { _events.emit(SignalingEvent.Disconnected(code, reason)) }
            }
        })

        try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            webSocket?.cancel()
            webSocket = null
            isConnected.set(false)
            throw RuntimeException("Timed out ($timeoutMs ms) connecting to WebSocket: $url", e)
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val janus = json.optString("janus")
            val transaction = json.optString("transaction")

            SDKLogger.debug(TAG, "RX: $text")

            val response = JanusResponse(
                janus = janus,
                transaction = transaction,
                sessionId = json.optLong("session_id").takeIf { it != 0L }?.toBigInteger(),
                senderHandleId = json.optLong("sender").takeIf { it != 0L }?.toBigInteger(),
                data = json.optJSONObject("data")?.toPlainMap() ?: emptyMap(),
                plugindata = json.optJSONObject("plugindata")?.toPlainMap() ?: emptyMap(),
                jsep = json.optJSONObject("jsep")?.toPlainMap(),
                error = json.optJSONObject("error")?.optString("reason") ?: json.optString("error").takeIf { it.isNotEmpty() },
                errorCode = json.optJSONObject("error")?.optInt("code") ?: json.optInt("error_code").takeIf { it != 0 },
                rawJson = json
            )

            // 1. Check if this resolves a pending transaction
            if (transaction.isNotEmpty()) {
                val pending = pendingTransactions[transaction]
                if (pending != null) {
                    when (janus) {
                        "success", "error", "event" -> {
                            pendingTransactions.remove(transaction)
                            pending.complete(response)
                            return
                        }
                        "ack" -> {
                            // Request acknowledged by Janus, keep waiting for the final event/success
                            SDKLogger.debug(TAG, "Transaction ACK: $transaction")
                            return
                        }
                    }
                }
            }

            // 2. Unsolicited push event (e.g. publishers list, unpublished, leaving)
            if (janus == "event") {
                val fullMap = buildMap<String, Any> {
                    putAll(response.plugindata)
                    response.senderHandleId?.let { put("sender", it) }
                    response.jsep?.let { put("jsep", it) }
                }
                scope.launch {
                    _events.emit(SignalingEvent.UnsolicitedEvent(fullMap, response.jsep))
                }
            }
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error parsing incoming message", e)
        }
    }

    suspend fun createSession(timeoutMs: Long = 15_000L): BigInteger {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "create")
            put("transaction", tx)
        }
        val response = sendTransaction(tx, request, timeoutMs)
        val id = (response.data["id"] as? Number)?.toLong()?.toBigInteger()
            ?: throw IllegalStateException("No session id returned in create: ${response.rawJson}")
        return id
    }

    suspend fun attachPlugin(sessionId: BigInteger, plugin: String, timeoutMs: Long = 15_000L): BigInteger {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "attach")
            put("session_id", sessionId)
            put("plugin", plugin)
            put("transaction", tx)
        }
        val response = sendTransaction(tx, request, timeoutMs)
        val id = (response.data["id"] as? Number)?.toLong()?.toBigInteger()
            ?: throw IllegalStateException("No handle id returned in attach: ${response.rawJson}")
        return id
    }

    suspend fun detachPlugin(sessionId: BigInteger, handleId: BigInteger, timeoutMs: Long = 5_000L) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "detach")
            put("session_id", sessionId)
            put("handle_id", handleId)
            put("transaction", tx)
        }
        try {
            sendTransaction(tx, request, timeoutMs)
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "detachPlugin failed: ${e.message}")
        }
    }

    suspend fun sendMessage(
        sessionId: BigInteger,
        handleId: BigInteger,
        body: Map<String, Any>,
        jsep: Map<String, Any>? = null,
        timeoutMs: Long = 15_000L
    ): JanusResponse {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "message")
            put("session_id", sessionId)
            put("handle_id", handleId)
            put("transaction", tx)
            put("body", JSONObject(body))
            if (jsep != null) {
                put("jsep", JSONObject(jsep))
            }
        }
        return sendTransaction(tx, request, timeoutMs)
    }

    suspend fun sendTrickleCandidate(
        sessionId: BigInteger,
        handleId: BigInteger,
        candidate: Map<String, Any>?
    ) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "trickle")
            put("session_id", sessionId)
            put("handle_id", handleId)
            put("transaction", tx)
            if (candidate != null) {
                put("candidate", JSONObject(candidate))
            } else {
                put("candidate", JSONObject().put("completed", true))
            }
        }
        sendRaw(request)
    }

    suspend fun sendKeepAlive(sessionId: BigInteger) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "keepalive")
            put("session_id", sessionId)
            put("transaction", tx)
        }
        sendRaw(request)
    }

    private suspend fun sendTransaction(
        tx: String,
        json: JSONObject,
        timeoutMs: Long
    ): JanusResponse {
        val deferred = CompletableDeferred<JanusResponse>()
        pendingTransactions[tx] = deferred
        sendRaw(json)

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val janusAction = json.optString("janus")
            val reqAction = json.optJSONObject("body")?.optString("request")
            val desc = if (!reqAction.isNullOrEmpty()) "$janusAction/$reqAction" else janusAction
            throw RuntimeException("Timed out ($timeoutMs ms) waiting for Janus response to '$desc' (transaction: $tx)", e)
        } finally {
            pendingTransactions.remove(tx)
        }
    }

    fun sendRaw(json: JSONObject) {
        val ws = webSocket ?: run {
            SDKLogger.warn(TAG, "Cannot send: WebSocket is null")
            return
        }
        SDKLogger.debug(TAG, "TX: $json")
        ws.send(json.toString())
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            isConnected.set(false)
            pendingTransactions.values.forEach {
                it.completeExceptionally(RuntimeException("Signaling client disconnected"))
            }
            pendingTransactions.clear()
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error disconnecting WebSocket", e)
        }
    }

    fun isConnected(): Boolean = isConnected.get()
}
