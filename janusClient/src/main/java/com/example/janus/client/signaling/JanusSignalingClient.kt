package com.example.janus.client.signaling

import com.example.janus.client.SDKLogger
import com.example.janus.client.SDKUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class SignalingEvent {
    object Connected : SignalingEvent()
    data class Disconnected(val code: Int, val reason: String) : SignalingEvent()
    data class Error(val error: Throwable) : SignalingEvent()
    data class UnsolicitedEvent(val message: Map<String, Any>, val jsep: Map<String, Any>?) : SignalingEvent()

    /**
     * A remote ICE candidate Janus trickled for one of our handles (`janus:"trickle"`, or a
     * `candidate` riding on an event). [candidate] is null for the end-of-candidates marker
     * (`{"completed":true}`).
     */
    data class RemoteCandidate(val senderHandleId: BigInteger, val candidate: Map<String, Any>?) : SignalingEvent()

    /**
     * A handle-level WebRTC notification from Janus core: `webrtcup`, `media`, `slowlink`,
     * `hangup` or `detached`. [raw] carries the whole message (e.g. `reason` for a hangup,
     * `type`/`receiving` for media).
     */
    data class HandleEvent(val type: String, val senderHandleId: BigInteger, val raw: Map<String, Any>) : SignalingEvent()

    /** Janus expired the session (`janus:"timeout"`) - nothing on it works any more. */
    data class SessionTimeout(val sessionId: BigInteger?) : SignalingEvent()

    /** A Janus `error` that didn't answer any pending request. */
    data class ServerError(val code: Int?, val reason: String?) : SignalingEvent()
}

/**
 * Callback listener for everything Janus pushes that isn't the answer to one of our requests -
 * the app's `JanusClientCallback` pattern. Every method has a no-op default; implement only what
 * you need. Called on OkHttp's WebSocket thread.
 *
 * The same notifications are also emitted on [JanusSignalingClient.events] for Flow collectors.
 */
interface JanusSignalingListener {
    fun onConnected() {}
    fun onDisconnected(code: Int, reason: String) {}
    fun onFailure(error: Throwable) {}

    /** Plugin push event (publishers, unpublished, leaving, talking, updated, ...). */
    fun onEvent(sender: BigInteger?, plugindata: Map<String, Any>, jsep: Map<String, Any>?) {}

    /** Janus trickled a candidate for [sender]; null = end of candidates. */
    fun onRemoteCandidate(sender: BigInteger, candidate: Map<String, Any>?) {}

    /** `webrtcup` / `media` / `slowlink` / `hangup` / `detached` for [sender]. */
    fun onHandleEvent(type: JanusMessageType, sender: BigInteger, raw: Map<String, Any>) {}

    fun onSessionTimeout(sessionId: BigInteger?) {}

    /** A Janus `error` that didn't answer any pending request. */
    fun onError(code: Int?, reason: String?) {}
}

/**
 * Janus WebSocket signaling client, structured like the app's own `JanusClient`:
 *
 *  - the client IS the [WebSocketListener]; every incoming frame goes through
 *    [handleIncomingData], which switches on [JanusMessageType]
 *  - each request registers a [JanusTransaction] (success / error / ack callbacks) in
 *    [transactions] under its transaction id, and the matching reply invokes it
 *  - anything unsolicited goes to the [JanusSignalingListener] (and [events])
 *
 * Callback API: [connect] with callbacks, [createSession], [attachPlugin], [sendMessage], ... each
 * taking `onSuccess` / `onError`. The suspend overloads are thin wrappers over those callbacks for
 * coroutine callers (Room, VideoRoomPlugin, RemoteSubscriptionManager) - no deferreds are kept.
 */
class JanusSignalingClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : WebSocketListener() {
    private val TAG = "JanusSignalingClient"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)

    /** Pending requests by transaction id (app: `transactions`). */
    private val transactions = ConcurrentHashMap<String, JanusTransaction>()

    private var listener: JanusSignalingListener? = null

    // Callbacks for the connect in progress (cleared once it opens or fails).
    @Volatile private var onOpenCallback: (() -> Unit)? = null
    @Volatile private var onOpenFailure: ((Throwable) -> Unit)? = null

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    fun setOnJanusSignalingListener(listener: JanusSignalingListener?): JanusSignalingClient {
        this.listener = listener
        return this
    }

    fun isConnected(): Boolean = isConnected.get()

    // ─────────────────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the WebSocket. [onOpen] runs once it's open, [onFailure] if it can't open (or
     * [timeoutMs] passes first).
     */
    fun connect(
        url: String,
        timeoutMs: Long = 15_000L,
        onOpen: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (isConnected.get()) {
            onOpen()
            return
        }
        onOpenCallback = onOpen
        onOpenFailure = onFailure

        val request = Request.Builder()
            .header("Sec-WebSocket-Protocol", "janus-protocol")
            .url(url)
            .build()
        webSocket = okHttpClient.newWebSocket(request, this)

        scope.launch {
            delay(timeoutMs)
            if (!isConnected.get()) {
                takeOpenFailure()?.let { fail ->
                    webSocket?.cancel()
                    webSocket = null
                    fail(RuntimeException("Timed out ($timeoutMs ms) connecting to WebSocket: $url"))
                }
            }
        }
    }

    /** Suspend form of [connect] - returns once open, throws if it can't. */
    suspend fun connect(url: String, timeoutMs: Long = 15_000L) {
        if (isConnected.get()) return
        suspendCancellableCoroutine<Unit> { cont ->
            connect(
                url, timeoutMs,
                onOpen = { if (cont.isActive) cont.resume(Unit) },
                onFailure = { if (cont.isActive) cont.resumeWithException(it) },
            )
        }
    }

    private fun takeOpenFailure(): ((Throwable) -> Unit)? {
        val fail = onOpenFailure
        onOpenCallback = null
        onOpenFailure = null
        return fail
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        SDKLogger.info(TAG, "WebSocket connected")
        isConnected.set(true)
        val open = onOpenCallback
        onOpenCallback = null
        onOpenFailure = null
        open?.invoke()
        listener?.onConnected()
        emit(SignalingEvent.Connected)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        handleIncomingData(text)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        SDKLogger.info(TAG, "WebSocket closed: $code $reason")
        isConnected.set(false)
        failAllTransactions("WebSocket closed: $code $reason")
        listener?.onDisconnected(code, reason)
        emit(SignalingEvent.Disconnected(code, reason))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val errorMessage = buildString {
            append("Janus WebSocket failure: ${t.message}")
            response?.let { append(" (HTTP ${it.code} ${it.message})") }
                ?: append(" (no HTTP response - handshake likely failed)")
        }
        SDKLogger.error(TAG, errorMessage)
        isConnected.set(false)
        takeOpenFailure()?.invoke(t)
        failAllTransactions(errorMessage, t)
        listener?.onFailure(t)
        emit(SignalingEvent.Error(t))
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            isConnected.set(false)
            failAllTransactions("Signaling client disconnected")
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error disconnecting WebSocket", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Incoming data (app: JanusClient.handleIncomingData)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleIncomingData(text: String) {
        SDKLogger.debug(TAG, "RX: $text")
        try {
            val obj = JSONObject(text)
            val type = JanusMessageType.fromString(obj.optString("janus"))
            val transaction = obj.optString("transaction").takeIf { it.isNotEmpty() }
            val sender = obj.opt("sender").toJanusId()
            val response = parseResponse(obj)

            when (type) {
                JanusMessageType.keepalive -> {}

                JanusMessageType.ack -> {
                    // Asynchronous request accepted - the final event comes later.
                    transaction?.let { transactions[it]?.onAck?.invoke() }
                }

                JanusMessageType.success -> {
                    if (transaction != null) resolveSuccess(transaction, response)
                }

                JanusMessageType.error -> {
                    val handled = transaction != null && resolveError(
                        transaction,
                        JanusError(response.errorCode, response.error ?: "Janus error", response)
                    )
                    if (!handled) {
                        listener?.onError(response.errorCode, response.error)
                        emit(SignalingEvent.ServerError(response.errorCode, response.error))
                    }
                }

                JanusMessageType.event -> {
                    // The final answer to an asynchronous plugin request, or a push event.
                    val handled = transaction != null && resolveSuccess(transaction, response)
                    if (!handled) {
                        listener?.onEvent(sender, response.plugindata, response.jsep)
                        val fullMap = buildMap<String, Any> {
                            putAll(response.plugindata)
                            sender?.let { put("sender", it) }
                            response.jsep?.let { put("jsep", it) }
                        }
                        emit(SignalingEvent.UnsolicitedEvent(fullMap, response.jsep))
                    }
                    // An event may also carry a candidate (app handles this too).
                    obj.optJSONObject("candidate")?.let { c ->
                        if (sender != null) dispatchCandidate(sender, c)
                    }
                }

                JanusMessageType.trickle -> {
                    val c = obj.optJSONObject("candidate")
                    if (sender != null && c != null) dispatchCandidate(sender, c)
                }

                JanusMessageType.webrtcup,
                JanusMessageType.media,
                JanusMessageType.slowlink,
                JanusMessageType.hangup,
                JanusMessageType.detached -> {
                    if (sender != null) {
                        val raw = obj.toPlainMap()
                        listener?.onHandleEvent(type, sender, raw)
                        emit(SignalingEvent.HandleEvent(type.name, sender, raw))
                    }
                }

                JanusMessageType.timeout -> {
                    listener?.onSessionTimeout(response.sessionId)
                    emit(SignalingEvent.SessionTimeout(response.sessionId))
                }

                else -> SDKLogger.debug(TAG, "Ignoring Janus message type '${obj.optString("janus")}'")
            }
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error parsing incoming message", e)
            listener?.onError(null, e.message)
        }
    }

    private fun parseResponse(json: JSONObject) = JanusResponse(
        janus = json.optString("janus"),
        transaction = json.optString("transaction"),
        sessionId = json.opt("session_id").toJanusId(),
        senderHandleId = json.opt("sender").toJanusId(),
        data = json.optJSONObject("data")?.toPlainMap() ?: emptyMap(),
        plugindata = json.optJSONObject("plugindata")?.toPlainMap() ?: emptyMap(),
        jsep = json.optJSONObject("jsep")?.toPlainMap(),
        error = json.optJSONObject("error")?.optString("reason")
            ?: json.optString("error").takeIf { it.isNotEmpty() },
        errorCode = json.optJSONObject("error")?.optInt("code")
            ?: json.optInt("error_code").takeIf { it != 0 }
            // Plugin-level failures (e.g. 426 no such room) come back as a normal
            // "event" with the code inside plugindata.data - surface those too.
            ?: json.optJSONObject("plugindata")?.optJSONObject("data")
                ?.optInt("error_code")?.takeIf { it != 0 },
        rawJson = json
    )

    private fun dispatchCandidate(sender: BigInteger, candidate: JSONObject) {
        // `{"completed":true}` is the end-of-candidates marker - mapped to null.
        val c = if (candidate.optBoolean("completed", false)) null else candidate.toPlainMap()
        listener?.onRemoteCandidate(sender, c)
        emit(SignalingEvent.RemoteCandidate(sender, c))
    }

    private fun emit(event: SignalingEvent) {
        scope.launch { _events.emit(event) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transactions (app: transactions map + Transaction callbacks)
    // ─────────────────────────────────────────────────────────────────────────

    /** Registers [transaction], sends [json], and fails it with a timeout after [timeoutMs]. */
    fun send(json: JSONObject, transaction: JanusTransaction, timeoutMs: Long) {
        transactions[transaction.tid] = transaction
        transaction.timeoutJob = scope.launch {
            delay(timeoutMs)
            resolveError(
                transaction.tid,
                JanusError(null, "Timed out ($timeoutMs ms) waiting for Janus response to '${transaction.description}' (transaction: ${transaction.tid})")
            )
        }
        if (!sendRaw(json)) {
            resolveError(transaction.tid, JanusError(null, "Cannot send '${transaction.description}': WebSocket is not open"))
        }
    }

    /** @return true if [tid] was a pending transaction. */
    private fun resolveSuccess(tid: String, response: JanusResponse): Boolean {
        val t = transactions.remove(tid) ?: return false
        t.timeoutJob?.cancel()
        try {
            t.onSuccess?.invoke(response)
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error in success callback for '${t.description}'", e)
        }
        return true
    }

    /** @return true if [tid] was a pending transaction. */
    private fun resolveError(tid: String, error: JanusError): Boolean {
        val t = transactions.remove(tid) ?: return false
        t.timeoutJob?.cancel()
        try {
            t.onError?.invoke(error)
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error in error callback for '${t.description}'", e)
        }
        return true
    }

    private fun failAllTransactions(reason: String, cause: Throwable? = null) {
        transactions.keys.toList().forEach { tid -> resolveError(tid, JanusError(null, reason, cause = cause)) }
    }

    /**
     * Runs a callback request as a suspend call: the reply (success, event, or a Janus `error`
     * reply) is returned as a [JanusResponse] so callers can inspect codes, while a timeout or
     * disconnect throws.
     */
    private suspend fun awaitResponse(
        start: (onSuccess: (JanusResponse) -> Unit, onError: (JanusError) -> Unit) -> Unit
    ): JanusResponse = suspendCancellableCoroutine { cont ->
        start(
            { response -> if (cont.isActive) cont.resume(response) },
            { error ->
                if (cont.isActive) {
                    val response = error.response
                    if (response != null) cont.resume(response)
                    else cont.resumeWithException(RuntimeException(error.reason, error.cause))
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requests (app: VideoRoomManager)
    // ─────────────────────────────────────────────────────────────────────────

    /** `{"janus":"create"}` → the new session id. */
    fun createSession(
        timeoutMs: Long = 15_000L,
        onSuccess: (BigInteger) -> Unit,
        onError: (JanusError) -> Unit,
    ) {
        createSessionRaw(timeoutMs, { response ->
            val id = response.data["id"].toJanusId()
            if (id != null) onSuccess(id)
            else onError(JanusError(null, "No session id returned in create: ${response.rawJson}", response))
        }, onError)
    }

    suspend fun createSession(timeoutMs: Long = 15_000L): BigInteger {
        val response = awaitResponse { ok, err -> createSessionRaw(timeoutMs, ok, err) }
        return response.data["id"].toJanusId()
            ?: throw IllegalStateException("No session id returned in create: ${response.rawJson}")
    }

    private fun createSessionRaw(timeoutMs: Long, ok: (JanusResponse) -> Unit, err: (JanusError) -> Unit) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "create")
            put("transaction", tx)
        }
        send(request, JanusTransaction(tx, "create", onSuccess = ok, onError = err), timeoutMs)
    }

    /** `{"janus":"attach"}` → the new plugin handle id. */
    fun attachPlugin(
        sessionId: BigInteger,
        plugin: String,
        opaqueId: String? = null,
        timeoutMs: Long = 15_000L,
        onSuccess: (BigInteger) -> Unit,
        onError: (JanusError) -> Unit,
    ) {
        attachPluginRaw(sessionId, plugin, opaqueId, timeoutMs, { response ->
            val id = response.data["id"].toJanusId()
            if (id != null) onSuccess(id)
            else onError(JanusError(null, "No handle id returned in attach: ${response.rawJson}", response))
        }, onError)
    }

    suspend fun attachPlugin(
        sessionId: BigInteger,
        plugin: String,
        opaqueId: String? = null,
        timeoutMs: Long = 15_000L
    ): BigInteger {
        val response = awaitResponse { ok, err -> attachPluginRaw(sessionId, plugin, opaqueId, timeoutMs, ok, err) }
        if (response.janus == "error") {
            throw IllegalStateException("Attach $plugin failed: ${response.error} (code ${response.errorCode})")
        }
        return response.data["id"].toJanusId()
            ?: throw IllegalStateException("No handle id returned in attach: ${response.rawJson}")
    }

    private fun attachPluginRaw(
        sessionId: BigInteger,
        plugin: String,
        opaqueId: String?,
        timeoutMs: Long,
        ok: (JanusResponse) -> Unit,
        err: (JanusError) -> Unit,
    ) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "attach")
            put("session_id", sessionId)
            put("plugin", plugin)
            put("transaction", tx)
            opaqueId?.let { put("opaque_id", it) }
        }
        send(request, JanusTransaction(tx, "attach", onSuccess = ok, onError = err), timeoutMs)
    }

    /** `{"janus":"detach"}` for [handleId]. */
    fun detachPlugin(
        sessionId: BigInteger,
        handleId: BigInteger,
        timeoutMs: Long = 5_000L,
        onSuccess: (JanusResponse) -> Unit,
        onError: (JanusError) -> Unit,
    ) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "detach")
            put("session_id", sessionId)
            put("handle_id", handleId)
            put("transaction", tx)
        }
        send(request, JanusTransaction(tx, "detach", onSuccess = onSuccess, onError = onError), timeoutMs)
    }

    /** Best effort - failures are only logged. */
    suspend fun detachPlugin(sessionId: BigInteger, handleId: BigInteger, timeoutMs: Long = 5_000L) {
        try {
            awaitResponse { ok, err -> detachPlugin(sessionId, handleId, timeoutMs, ok, err) }
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "detachPlugin failed: ${e.message}")
        }
    }

    /** `{"janus":"destroy"}` - every handle on the session goes with it. */
    fun destroySession(
        sessionId: BigInteger,
        timeoutMs: Long = 5_000L,
        onSuccess: (JanusResponse) -> Unit,
        onError: (JanusError) -> Unit,
    ) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "destroy")
            put("session_id", sessionId)
            put("transaction", tx)
        }
        send(request, JanusTransaction(tx, "destroy", onSuccess = onSuccess, onError = onError), timeoutMs)
    }

    /** Best effort - failures are only logged. */
    suspend fun destroySession(sessionId: BigInteger, timeoutMs: Long = 5_000L) {
        try {
            awaitResponse { ok, err -> destroySession(sessionId, timeoutMs, ok, err) }
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "destroySession failed: ${e.message}")
        }
    }

    /**
     * `{"janus":"message", body, jsep?}` to a plugin handle. [onSuccess] gets the plugin's reply
     * (a `success` for synchronous requests, the final `event` for asynchronous ones - check its
     * plugin data for an `error_code`); [onError] gets a Janus `error`, timeout or disconnect.
     */
    fun sendMessage(
        sessionId: BigInteger,
        handleId: BigInteger,
        body: Map<String, Any>,
        jsep: Map<String, Any>? = null,
        timeoutMs: Long = 15_000L,
        onAck: (() -> Unit)? = null,
        onSuccess: (JanusResponse) -> Unit,
        onError: (JanusError) -> Unit,
    ) {
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
        val description = "message/${body["request"] ?: "?"}"
        send(request, JanusTransaction(tx, description, onSuccess, onError, onAck), timeoutMs)
    }

    suspend fun sendMessage(
        sessionId: BigInteger,
        handleId: BigInteger,
        body: Map<String, Any>,
        jsep: Map<String, Any>? = null,
        timeoutMs: Long = 15_000L
    ): JanusResponse = awaitResponse { ok, err ->
        sendMessage(sessionId, handleId, body, jsep, timeoutMs, onAck = null, onSuccess = ok, onError = err)
    }

    /** Fire-and-forget `trickle` (Janus only acks it). A null [candidate] sends `completed`. */
    fun sendTrickleCandidate(
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

    /** Fire-and-forget `keepalive` (Janus only acks it). */
    fun sendKeepAlive(sessionId: BigInteger) {
        val tx = SDKUtils.randomString(12)
        val request = JSONObject().apply {
            put("janus", "keepalive")
            put("session_id", sessionId)
            put("transaction", tx)
        }
        sendRaw(request)
    }

    /** @return false if nothing could be sent (no open WebSocket). */
    fun sendRaw(json: JSONObject): Boolean {
        val ws = webSocket ?: run {
            SDKLogger.warn(TAG, "Cannot send: WebSocket is null")
            return false
        }
        SDKLogger.debug(TAG, "TX: $json")
        return ws.send(json.toString())
    }
}
