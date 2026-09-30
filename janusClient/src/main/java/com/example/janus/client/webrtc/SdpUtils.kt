package com.example.janus.client.webrtc

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine extensions for WebRTC SDP operations to eliminate callback hell and provide clean async/await.
 */
object SdpUtils {

    suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("createOffer failed: $error"))
                    }
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }

    suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("createAnswer failed: $error"))
                    }
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }

    suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { continuation ->
            setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("setLocalDescription failed: $error"))
                    }
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }

    suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { continuation ->
            setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("setRemoteDescription failed: $error"))
                    }
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }

    /** Coroutine form of [PeerConnection.getStats]. */
    suspend fun PeerConnection.getStatsSuspend(): RTCStatsReport =
        suspendCancellableCoroutine { continuation ->
            getStats { report -> if (continuation.isActive) continuation.resume(report) }
        }

    /** Builds an [IceCandidate] from a Janus trickle `candidate` object; null if incomplete. */
    fun Map<String, Any>.toIceCandidate(): IceCandidate? {
        val sdp = this["candidate"] as? String ?: return null
        val sdpMid = this["sdpMid"] as? String ?: ""
        val index = (this["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        return IceCandidate(sdpMid, index, sdp)
    }

    /** Reads `audioLevel` (0..1) from a stats entry, if it has one. */
    fun org.webrtc.RTCStats.audioLevelOrNull(): Float? =
        (members["audioLevel"] as? Number)?.toFloat()
}
