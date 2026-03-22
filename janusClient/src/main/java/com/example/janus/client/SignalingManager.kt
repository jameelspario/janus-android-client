package com.example.janus.client

import okhttp3.WebSocketListener
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles all signaling communication with Janus server
 * Manages session lifecycle, plugin attachment, and message routing
 */
class SignalingManager(private val janusServerUrl: String) {
    private lateinit var webSocket: JanusWebSocket
    private var eventListener: JanusSignalingEventListener? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    private val transactionId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val messageHandlers = ConcurrentHashMap<String, (Map<String, Any>) -> Unit>()
    private var sessionId: BigInteger? = null
    private var handleId: BigInteger? = null
    private var keepAliveThread: Thread? = null

    /**
     * Set event listener
     */
    fun setEventListener(listener: JanusSignalingEventListener) {
        this.eventListener = listener
    }

    /**
     * Connect to Janus server
     */
    fun connect(): Boolean {
        return try {
            if (isConnected.get()) return true
            if (isConnecting.getAndSet(true)) return false // Already connecting

            SDKLogger.info("JanusSignaling", "Connecting to: $janusServerUrl")

            webSocket = JanusWebSocket(janusServerUrl)
            webSocket.connect(JanusWebSocketListener())
            true
        } catch (e: Exception) {
            SDKLogger.error("JanusSignaling", "Connection failed", e)
            isConnecting.set(false)
            false
        }
    }

    /**
     * Disconnect from server
     */
    fun disconnect() {
        try {
            stopKeepAlive()
            webSocket.close()
            isConnected.set(false)
            isConnecting.set(false)
            eventListener?.onDisconnected()
            SDKLogger.info("JanusSignaling", "Disconnected")
        } catch (e: Exception) {
            SDKLogger.error("JanusSignaling", "Disconnect error", e)
        }
    }

    /**
     * Create Janus session
     */
    fun createSession() {
        sendRequest(
            action = "create",
            params = mapOf("janus" to "create"),
            callback = { response, _ ->
                sessionId = (response["id"] as? Number)
                    ?.toLong()
                    ?.toBigInteger()
                SDKLogger.info("JanusSignaling", "Session created: $sessionId")
                if (sessionId != null) {
                    startKeepAlive()
                    attachPlugin(JanusPlugin.VIDEOROOM.pluginName) { handleId0 ->
                        handleId = handleId0
                    }
                }
            }
        )
    }

    /**
     * Attach to a plugin
     */
    fun attachPlugin(pluginName: String, callback: (BigInteger?) -> Unit) {
        if (sessionId == null) {
            SDKLogger.error("JanusSignaling", "No session ID")
            callback(null)
            return
        }

        sendRequest(  //attach
            action = "attach",
            params = mapOf(
                "janus" to "attach",
                "plugin" to pluginName
            ),
            handleId = null,
            callback = { response, _ ->
                val handleId = (response["id"] as? Number)
                    ?.toLong()
                    ?.toBigInteger()
                SDKLogger.info("JanusSignaling", "Plugin attached: $handleId")
                callback(handleId)
            }
        )
    }

    /**
     * Detach a plugin handle from the session.
     * Sends {"janus":"detach"} so Janus cleans up the subscriber handle server-side.
     * Always call this after a subscriber sends "leave", or on forced cleanup.
     */
    fun detachPlugin(handleId: BigInteger, callback: ((Boolean) -> Unit)? = null) {
        if (sessionId == null) {
            SDKLogger.error("JanusSignaling", "detachPlugin: no session")
            callback?.invoke(false)
            return
        }
        sendRequest(
            action = "detach",
            params = mapOf("janus" to "detach"),
            handleId = handleId,
            callback = { _, _ ->
                SDKLogger.info("JanusSignaling", "Plugin detached handle=$handleId")
                callback?.invoke(true)
            }
        )
    }

    /**
     * Send ICE candidate
     */
    fun sendTrickleIceCandidate(
        candidate1: String,
        sdpMLineIndex: Int,
        sdpMid: String,
        handle: BigInteger? = null
    ) {

//        val candidate = JSONObject()
//        val message = JSONObject()
//
//        candidate.putOpt("candidate", candidate1)
//        candidate.putOpt("sdpMid", sdpMid)
//        candidate.putOpt("sdpMLineIndex", sdpMLineIndex)
//
//        message.putOpt("janus", "trickle")
//        message.putOpt("candidate", candidate)
//        message.putOpt("transaction", getNextTransactionId())
//        message.putOpt("session_id", sessionId)
//        message.putOpt("handle_id", handle ?: handleId)

        val candidate = mapOf<String, Any>(
            "candidate" to candidate1,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex
        )
        sendTrickle(candidate, handleId = handle ?: handleId)
    }

    fun sendTrickleCandidateComplete(streamId: String, handle: BigInteger? = null) {
//        val candidate = JSONObject()
//        val message = JSONObject()
//
//        candidate.putOpt("completed", true);
//
//        message.putOpt("janus", "trickle")
//        message.putOpt("candidate", candidate)
//        message.putOpt("transaction", getNextTransactionId())
//        message.putOpt("session_id", sessionId)
//        message.putOpt("handle_id", handle ?: handleId)
//
//        webSocket.send(message)
        val candidate2 = mapOf<String, Any>(
            "completed" to true,
        )
        sendTrickle(candidate2, handleId = handle ?: handleId)
        consoleLogE("Sent trickle complete for $streamId")
    }

    /**
     * Keep-alive ping
     */
    fun keepAlive() {
        if (sessionId == null || !isConnected.get()) return

        sendRequest(
            action = "keepalive",
            params = mapOf("janus" to "keepalive"),
            callback = { it1, it2 -> }
        )
    }

    /**
     * Register a message handler for specific plugin
     */
    fun registerMessageHandler(pluginName: String, handler: (Map<String, Any>) -> Unit) {
        messageHandlers[pluginName] = handler
        SDKLogger.debug("JanusSignaling", "Message handler registered for $pluginName")
    }

    /**
     * Unregister a message handler
     */
    fun unregisterMessageHandler(pluginName: String) {
        messageHandlers.remove(pluginName)
        SDKLogger.debug("JanusSignaling", "Message handler unregistered for $pluginName")
    }

    /**
     * Get current session ID
     */
    fun getSessionId(): BigInteger? = sessionId

    /**
     * Get current handle ID
     */
    fun getHandleId(): BigInteger? = handleId

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected.get()

    /**
     * Send generic message to Janus
     */
    fun sendMessage(
        body: Map<String, Any>,
        jsep: Map<String, Any>? = null,
        callback: ((Map<String, Any>, Map<String, Any>?) -> Unit)? = null
    ) {
        sendMessage(body, jsep, handleId, callback)
    }

    fun sendMessage(
        body: Map<String, Any>,
        jsep: Map<String, Any>? = null,
        handleId: BigInteger? = null,
        callback: ((Map<String, Any>, Map<String, Any>?) -> Unit)? = null
    ) {
        if (sessionId == null || handleId == null) {
            SDKLogger.error("JanusSignaling", "No session or handle")
            callback?.invoke(emptyMap(), null)
            return
        }

        val params = mutableMapOf(
            "janus" to "message",
            "body" to body
        )

        if (jsep != null) {
            params["jsep"] = jsep
        }

        sendRequest( //sendMessage
            action = "message",
            params = params,
            handleId = handleId,
            callback = callback ?: { it1, it2 -> }
        )
    }


    /*
    val candidate1 = mapOf<String, Any>(
        "candidate" to "",
        "sdpMid" to "",
        "sdpMLineIndex" to 0
    )

     val candidate2 = mapOf<String, Any>(
        "completed" to true,
    )
     */
    fun sendTrickle(
        candidate: Map<String, Any>? = null,
        callback: ((Map<String, Any>, Map<String, Any>?) -> Unit)? = null
    ) {
        sendTrickle(candidate, handleId, callback)
    }

    fun sendTrickle(
        candidate: Map<String, Any>? = null,
        handleId: BigInteger? = null,
        callback: ((Map<String, Any>, Map<String, Any>?) -> Unit)? = null
    ) {
        if (sessionId == null || handleId == null) {
            SDKLogger.error("JanusSignaling", "No session or handle")
            callback?.invoke(emptyMap(), null)
            return
        }

        val params = mutableMapOf<String, Any>()
        if (candidate != null) {
            params["candidate"] = candidate
        }

        sendRequest( //sendMessage
            action = "trickle",
            params = params,
            handleId = handleId,
            callback = callback ?: { it1, it2 -> }
        )
    }

    /**
     * Send offer/answer SDP
     */
    fun sendSDP(
        sdpType: String,
        sdp: String,
        body: Map<String, Any>,
        callback: (Map<String, Any>, Map<String, Any>?) -> Unit
    ) {
        val jsep = mapOf(
            "type" to sdpType,
            "sdp" to sdp
        )

        sendMessage(
            body = body,
            jsep = jsep,
            callback = callback
        )
    }

    /**
     * Send request to server (internal)
     */
    private fun sendRequest(
        action: String,
        params: Map<String, Any>,
        callback: (Map<String, Any>, Map<String, Any>?) -> Unit
    ) {
        sendRequest(action, params, handleId, callback)
    }

    private fun sendRequest(
        action: String,
        params: Map<String, Any>,
        handleId: BigInteger? = null,
        callback: (Map<String, Any>, Map<String, Any>?) -> Unit
    ) {
        try {
            if (!isConnected.get()) {
                SDKLogger.warn("JanusSignaling", "Not connected, queueing request")
                callback(emptyMap(), null)
                return
            }

            val txId = getNextTransactionId()
            val request = JSONObject().apply {
                put("janus", params["janus"] ?: action)

                if (sessionId != null) {
                    put("session_id", sessionId)
                }
                if (handleId != null) {
                    put("handle_id", handleId)
                }

                put("transaction", txId)

                // Add body if present
                params["body"]?.let {
                    put("body", JSONObject(it as Map<*, *>))
                }
                // Add jsep if present
                params["jsep"]?.let {
                    put("jsep", JSONObject(it as Map<*, *>))
                }
                // Add candidate if present
                params["candidate"]?.let {
                    put("candidate", JSONObject(it as Map<*, *>))
                }
                // Add any other params
                params.forEach { (key, value) ->
                    if (key !in listOf("janus", "body", "jsep", "candidate")) {
                        put(key, value)
                    }
                }
            }

            SDKLogger.debug("JanusSignaling", "Sending: $request")

            pendingRequests[txId] = PendingRequest(
                transactionId = txId,
                action = action,
                timestamp = System.currentTimeMillis(),
                callback = callback
            )

            webSocket.send(request)
        } catch (e: Exception) {
            SDKLogger.error("JanusSignaling", "Send request failed", e)
            callback(emptyMap(), null)
        }
    }

    /**
     * Handle incoming message from server
     */
    private fun handleMessage(message: JSONObject) {
        try {
            SDKLogger.debug("JanusSignaling", "Received: $message")

            val janus = message.optString("janus")
            val txId = message.optString("transaction")

            // ──────────────────────────────
            // Try to find & handle pending request FIRST
            // ──────────────────────────────
            val pending = pendingRequests[txId]  // peek first
            if (pending != null && (janus == "success" || janus == "event")) {
                // For videoroom join/create → final answer often comes in "event"
                val shouldRemove = when (janus) {
                    "success" -> true
                    "event" -> {
                        val plugindata = message.optJSONObject("plugindata")
                        val data = plugindata?.optJSONObject("data")
                        val vrType = data?.optString("videoroom")
                        vrType in listOf(
                            "joined",
                            "success",
                            "event",
                            "updated",
                            "attached"
                        ) || data?.has("error_code") == true
                    }

                    else -> false
                }

                if (shouldRemove) {
                    pendingRequests.remove(txId)
                    val dataMap =
                        message.optJSONObject("plugindata")?.optJSONObject("data")?.toMap()
                            ?: message.optJSONObject("data")?.toMap()
                            ?: emptyMap()

                    val jsep = message.optJSONObject("jsep")?.toMap()

                    try {
                        pending.callback(dataMap, jsep)
                    } catch (e: Exception) {
                        SDKLogger.error("JanusSignaling", "Callback failed for tx $txId", e)
                    }
                    return  // ← important: avoid double-processing
                }
            }

            when (janus) {
                "success" -> {
                }

                "event" -> {
                    val plugin = message.optString("sender")
                    val data = message.optJSONObject("plugindata")?.toMap() ?: emptyMap()

                    eventListener?.onMessage(data)

                    val handler = messageHandlers[plugin]
                    if (handler != null) {
                        try {
                            handler(data)
                        } catch (e: Exception) {
                            SDKLogger.error("JanusSignaling", "Error in message handler", e)
                        }
                    }
                }

                "error" -> {
                    val errorCode = message.optInt("error_code", -1)
                    val errorReason = message.optString("error")
                    SDKLogger.error("JanusSignaling", "Error $errorCode: $errorReason")
                    eventListener?.onError(errorReason)
                }

                "ack" -> {
                    SDKLogger.debug("JanusSignaling", "ACK received: $txId")
                }

                else -> {
                    SDKLogger.warn("JanusSignaling", "Unknown message type: $janus")
                }
            }
        } catch (e: Exception) {
            SDKLogger.error("JanusSignaling", "Message handling error", e)
        }
    }

    /**
     * Get next transaction ID
     */
    private fun getNextTransactionId(): String = SDKUtils.randomString(12)

    /**
     * Start keep-alive mechanism
     */
    private fun startKeepAlive() {
        keepAliveThread = Thread {
            try {
                while (isConnected.get()) {
                    Thread.sleep(30000)
                    keepAlive()
                }
            } catch (e: InterruptedException) {
                SDKLogger.debug("JanusSignaling", "Keep-alive thread interrupted")
            } catch (e: Exception) {
                SDKLogger.error("JanusSignaling", "Keep-alive error", e)
            }
        }.apply {
            isDaemon = true
            name = "Janus-KeepAlive"
        }
        keepAliveThread?.start()
    }

    /**
     * Stop keep-alive mechanism
     */
    private fun stopKeepAlive() {
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    /**
     * WebSocket listener implementation
     */
    private inner class JanusWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            SDKLogger.info("JanusWebSocket", "Connected")
            isConnected.set(true)
            isConnecting.set(false)
            eventListener?.onConnected()

            // Create session after connecting
            createSession()
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            try {
                val message = JSONObject(text)
                handleMessage(message)
            } catch (e: Exception) {
                SDKLogger.error("JanusWebSocket", "Message parsing error", e)
            }
        }

        override fun onFailure(
            webSocket: okhttp3.WebSocket,
            t: Throwable,
            response: okhttp3.Response?
        ) {
            SDKLogger.error("JanusWebSocket", "Connection failure", t)
            isConnected.set(false)
            isConnecting.set(false)
            eventListener?.onError("WebSocket error: ${t.message}")
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            SDKLogger.info("JanusWebSocket", "Connection closed: $code - $reason")
            isConnected.set(false)
            isConnecting.set(false)
            eventListener?.onDisconnected()
        }
    }

    /**
     * Pending request data class
     */
    private data class PendingRequest(
        val transactionId: String,
        val action: String,
        val timestamp: Long,
        val callback: (Map<String, Any>, Map<String, Any>?) -> Unit
    )
}

/**
 * Extension function to convert JSONObject to Map
 */
fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = this.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = this.opt(key)
        if (value != null && value != JSONObject.NULL) {
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                else -> value
            }
        }
    }
    return map
}