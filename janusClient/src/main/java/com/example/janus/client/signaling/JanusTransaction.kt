package com.example.janus.client.signaling

import kotlinx.coroutines.Job
import java.util.Locale

/**
 * Every value Janus puts in a message's `janus` field - same set as the app's own
 * `com.bindaslive.janus.MessageType`, plus `slowlink` / `timeout` and an [unknown] fallback so a
 * new server message never throws.
 */
enum class JanusMessageType {
    message,
    trickle,
    detach,
    destroy,
    keepalive,
    create,
    attach,
    event,
    error,
    ack,
    success,
    webrtcup,
    hangup,
    detached,
    media,
    slowlink,
    timeout,
    unknown;

    companion object {
        fun fromString(value: String?): JanusMessageType =
            values().firstOrNull { it.name == value?.lowercase(Locale.ROOT) } ?: unknown
    }
}

/** Why a transaction failed: a Janus `error` reply (with [response]), a timeout, or a disconnect. */
data class JanusError(
    val code: Int?,
    val reason: String,
    val response: JanusResponse? = null,
    val cause: Throwable? = null,
)

/**
 * A pending request, keyed by its `transaction` id - the app's `Transaction` pattern.
 *
 *  - [onSuccess]: Janus answered with `success` (core requests) or the plugin's final `event`
 *    (asynchronous plugin requests, e.g. join / configure / start).
 *  - [onError]:   Janus answered with `error`, or the request timed out / the socket closed.
 *  - [onAck]:     Janus acknowledged an asynchronous request; the final answer is still to come.
 */
class JanusTransaction(
    val tid: String,
    /** e.g. "message/join" - only used in logs and error text. */
    val description: String,
    val onSuccess: ((JanusResponse) -> Unit)? = null,
    val onError: ((JanusError) -> Unit)? = null,
    val onAck: (() -> Unit)? = null,
) {
    internal var timeoutJob: Job? = null
}
