package com.example.janus.client.videoroom

import com.example.janus.client.SDKLogger
import com.example.janus.client.signaling.JanusSignalingClient
import com.example.janus.client.track.RemoteAudioTrack
import com.example.janus.client.track.RemoteVideoTrack
import com.example.janus.client.track.Track
import com.example.janus.client.webrtc.SdpUtils.createAnswerSuspend
import com.example.janus.client.webrtc.SdpUtils.setLocalDescriptionSuspend
import com.example.janus.client.webrtc.SdpUtils.setRemoteDescriptionSuspend
import com.example.janus.client.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack as RtcAudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack as RtcVideoTrack
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Callbacks for remote subscription track events.
 */
interface RemoteSubscriptionListener {
    fun onRemoteTrackSubscribed(feedId: BigInteger, display: String, track: Track)
    fun onRemoteTrackUnsubscribed(feedId: BigInteger, track: Track?)
}

/**
 * Manages WebRTC subscriber peer connections for remote VideoRoom feeds.
 */
class RemoteSubscriptionManager(
    private val scope: CoroutineScope,
    private val signalingClient: JanusSignalingClient,
    private val webRtcEngine: WebRtcEngine,
    private val listener: RemoteSubscriptionListener
) {
    private val TAG = "RemoteSubscriptionManager"

    private data class SubscriberEntry(
        val feedId: BigInteger,
        val display: String,
        val roomId: Int,
        val subscriberHandleId: BigInteger,
        var peerConnection: PeerConnection? = null,
        var videoTrack: RemoteVideoTrack? = null,
        var audioTrack: RemoteAudioTrack? = null
    )

    private val activeSubscribers = ConcurrentHashMap<BigInteger, SubscriberEntry>()
    private val pendingFeeds = ConcurrentHashMap.newKeySet<BigInteger>()

    fun subscribe(sessionId: BigInteger, roomId: Int, feedId: BigInteger, display: String) {
        if (!pendingFeeds.add(feedId)) {
            SDKLogger.debug(TAG, "Subscription already in flight for feed $feedId")
            return
        }

        if (activeSubscribers.containsKey(feedId)) {
            SDKLogger.debug(TAG, "Already subscribed to feed $feedId")
            pendingFeeds.remove(feedId)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                SDKLogger.info(TAG, "Attaching subscriber handle for feed $feedId ($display)...")
                val handleId = signalingClient.attachPlugin(sessionId, "janus.plugin.videoroom")

                val entry = SubscriberEntry(
                    feedId = feedId,
                    display = display,
                    roomId = roomId,
                    subscriberHandleId = handleId
                )
                activeSubscribers[feedId] = entry

                val joinBody = mapOf<String, Any>(
                    "request" to "join",
                    "ptype" to "subscriber",
                    "room" to roomId,
                    "feed" to feedId.toLong()
                )

                val joinResponse = signalingClient.sendMessage(sessionId, handleId, joinBody)
                val jsep = joinResponse.jsep

                if (jsep != null && (joinResponse.pluginDataMap["videoroom"] == "attached" || joinResponse.rawJson.optString("janus") == "event")) {
                    setupSubscriberPeerConnection(sessionId, entry, jsep)
                } else {
                    SDKLogger.warn(TAG, "Join as subscriber rejected for feed $feedId: ${joinResponse.rawJson}")
                    cleanupSubscription(sessionId, feedId, sendLeave = false)
                }
            } catch (e: Exception) {
                SDKLogger.error(TAG, "Failed to subscribe to feed $feedId", e)
                cleanupSubscription(sessionId, feedId, sendLeave = true)
            } finally {
                pendingFeeds.remove(feedId)
            }
        }
    }

    private suspend fun setupSubscriberPeerConnection(
        sessionId: BigInteger,
        entry: SubscriberEntry,
        jsep: Map<String, Any>
    ) {
        val offerSdp = jsep["sdp"] as? String ?: run {
            SDKLogger.error(TAG, "No SDP offer in jsep for feed ${entry.feedId}")
            cleanupSubscription(sessionId, entry.feedId, sendLeave = true)
            return
        }

        val feedId = entry.feedId
        val handleId = entry.subscriberHandleId

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                SDKLogger.debug(TAG, "Subscriber PC ICE state for feed $feedId: $newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    scope.launch(Dispatchers.IO) {
                        signalingClient.sendTrickleCandidate(sessionId, handleId, null)
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    val map = mapOf<String, Any>(
                        "candidate" to candidate.sdp,
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    )
                    signalingClient.sendTrickleCandidate(sessionId, handleId, map)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { onIncomingVideoTrack(entry, it) }
                stream.audioTracks.firstOrNull()?.let { onIncomingAudioTrack(entry, it) }
            }
            override fun onRemoveStream(stream: MediaStream) {
                unsubscribe(sessionId, feedId)
            }
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                when (val track = receiver.track()) {
                    is RtcVideoTrack -> onIncomingVideoTrack(entry, track)
                    is RtcAudioTrack -> onIncomingAudioTrack(entry, track)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                when (val track = transceiver.receiver.track()) {
                    is RtcVideoTrack -> onIncomingVideoTrack(entry, track)
                    is RtcAudioTrack -> onIncomingAudioTrack(entry, track)
                }
            }
        }

        val pc = webRtcEngine.createPeerConnection(observer) ?: run {
            SDKLogger.error(TAG, "Failed to create subscriber PeerConnection for feed $feedId")
            cleanupSubscription(sessionId, feedId, sendLeave = true)
            return
        }

        entry.peerConnection = pc

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescriptionSuspend(remoteOffer)

        val answerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        val answer = pc.createAnswerSuspend(answerConstraints)
        pc.setLocalDescriptionSuspend(answer)

        val startBody = mapOf<String, Any>(
            "request" to "start",
            "room" to entry.roomId
        )
        val answerJsep = mapOf(
            "type" to answer.type.canonicalForm(),
            "sdp" to answer.description
        )

        signalingClient.sendMessage(sessionId, handleId, startBody, answerJsep)
        SDKLogger.info(TAG, "Started subscriber feed $feedId successfully")
    }

    private fun onIncomingVideoTrack(entry: SubscriberEntry, rtcTrack: RtcVideoTrack) {
        if (entry.videoTrack != null) return
        val remoteTrack = RemoteVideoTrack(rtcTrack)
        entry.videoTrack = remoteTrack
        listener.onRemoteTrackSubscribed(entry.feedId, entry.display, remoteTrack)
    }

    private fun onIncomingAudioTrack(entry: SubscriberEntry, rtcTrack: RtcAudioTrack) {
        if (entry.audioTrack != null) return
        val remoteTrack = RemoteAudioTrack(rtcTrack)
        entry.audioTrack = remoteTrack
        listener.onRemoteTrackSubscribed(entry.feedId, entry.display, remoteTrack)
    }

    fun unsubscribe(sessionId: BigInteger, feedId: BigInteger) {
        cleanupSubscription(sessionId, feedId, sendLeave = true)
    }

    private fun cleanupSubscription(sessionId: BigInteger, feedId: BigInteger, sendLeave: Boolean) {
        val entry = activeSubscribers.remove(feedId) ?: return
        pendingFeeds.remove(feedId)

        if (sendLeave) {
            scope.launch(Dispatchers.IO) {
                try {
                    signalingClient.sendMessage(
                        sessionId = sessionId,
                        handleId = entry.subscriberHandleId,
                        body = mapOf("request" to "unsubscribe")
                    )
                    signalingClient.detachPlugin(sessionId, entry.subscriberHandleId)
                } catch (e: Exception) {
                    SDKLogger.warn(TAG, "Error unsubscribing feed $feedId: ${e.message}")
                }
            }
        }

        val removedTrack = entry.videoTrack ?: entry.audioTrack

        try {
            listener.onRemoteTrackUnsubscribed(feedId, removedTrack)
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Error in onRemoteTrackUnsubscribed listener for $feedId: ${e.message}")
        }

        try {
            entry.videoTrack?.removeAllSinks()
            entry.videoTrack = null
            entry.audioTrack = null
            entry.peerConnection?.dispose()
            entry.peerConnection = null
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Error disposing subscriber WebRTC resources for $feedId: ${e.message}")
        }

        SDKLogger.info(TAG, "Cleaned up subscription for feed $feedId")
    }

    fun unsubscribeAll(sessionId: BigInteger) {
        val feeds = activeSubscribers.keys.toList()
        feeds.forEach { cleanupSubscription(sessionId, it, sendLeave = true) }
    }
}
