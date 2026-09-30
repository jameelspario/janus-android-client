package com.example.janus.client.webrtc

import com.example.janus.client.SDKLogger
import com.example.janus.client.webrtc.SdpUtils.toIceCandidate
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/**
 * Holds remote ICE candidates Janus trickles before the matching [PeerConnection] has its remote
 * description (adding them earlier fails), then applies them once [flush] is called after
 * `setRemoteDescription`. Mirrors what the app's JanusService does on each `onIceCandidate`,
 * minus the ordering race.
 */
class RemoteCandidateBuffer(private val tag: String) {
    private val pending = mutableListOf<IceCandidate>()

    /** Adds [candidate] now if [pc] is ready, otherwise queues it. A null candidate (Janus's
     * end-of-candidates marker) needs no action. */
    @Synchronized
    fun add(pc: PeerConnection?, candidate: Map<String, Any>?) {
        val ice = candidate?.toIceCandidate() ?: return
        if (pc == null || pc.remoteDescription == null) {
            pending += ice
            return
        }
        apply(pc, ice)
    }

    /** Applies every queued candidate to [pc] - call right after its remote description is set. */
    @Synchronized
    fun flush(pc: PeerConnection) {
        val queued = pending.toList()
        pending.clear()
        queued.forEach { apply(pc, it) }
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun apply(pc: PeerConnection, ice: IceCandidate) {
        pc.addIceCandidate(ice, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() {}
            override fun onAddFailure(error: String?) {
                SDKLogger.warn(tag, "addIceCandidate failed: $error (${ice.sdp})")
            }
        })
    }
}
