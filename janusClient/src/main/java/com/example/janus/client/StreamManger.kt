package com.example.janus.client

import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages stream publishing and subscription for WebRTC peer connections
 * Handles both outgoing (publish) and incoming (subscribe) media streams
 */

class StreamManager(
    private val webRtcManager: WebRTCManager,
    private val signalingManager: SignalingManager
) {

    private val activeStreams = ConcurrentHashMap<String, StreamInfo>()
    private val streamListeners = mutableListOf<JanusStreamEventListener>()
    private val lock = Any()
    var localVideoTrack: VideoTrack? = null
    var localAudioTrack: AudioTrack? = null


    /**
     * Publish local stream to a room
     */
    fun publishStream(
        roomId: Int,
        audioEnabled: Boolean = true,
        videoEnabled: Boolean = true
    ): Boolean {
        return try {
            val streamId = generateStreamId("pub", "$roomId")
            SDKLogger.info("StreamManager", "Publishing stream: $streamId")

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val observer = StreamPeerConnectionObserver(streamId, { pc ->
                onPeerConnectionReady(streamId, pc)
            }, { candidate ->
                signalingManager.sendTrickleIceCandidate(candidate.sdp, candidate.sdpMLineIndex, candidate.sdpMid)
            }, {
                signalingManager.sendTrickleCandidateComplete(streamId)
            }, { sid, candidates ->
                activeStreams[sid]?.peerConnection?.removeIceCandidates(candidates)
            })

            val observer1 = object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    println("Got ICE candidate: $candidate")
                }
            }
            val pc = webRtcManager.createPeerConnection(iceServers, observer)
                ?: return false.also { SDKLogger.error("StreamManager", "Failed to create peer connection") }

            if (audioEnabled) {
                val audioTrack = webRtcManager.createAudioTrack("audio-$streamId")
                pc.addTrack(audioTrack)
                localAudioTrack = audioTrack
            }
            if (videoEnabled) {
                webRtcManager.track.videoCapturer = webRtcManager.track.createVideoCapturer()
                if (webRtcManager.track.videoCapturer == null) {
                    return false
                }
                val videoTrack = webRtcManager.createVideoTrack("video-$streamId", webRtcManager.track.videoCapturer)
                pc.addTrack(videoTrack)
                localVideoTrack = videoTrack
            }

            activeStreams[streamId] = StreamInfo(
                streamId = streamId,
                roomId = roomId,
                peerConnection = pc,
                isPublisher = true,
                audioEnabled = audioEnabled,
                videoEnabled = videoEnabled,
                createdAt = System.currentTimeMillis()
            )

            val publish = JSONObject()
            publish.putOpt("request", "publish")
            publish.putOpt("audio", audioEnabled)
            publish.putOpt("video", videoEnabled)

            val body = mapOf<String, Any>(
                "request" to "publish",
                "audio" to audioEnabled,
                "video" to videoEnabled
            )

            // Create offer for publishing
            webRtcManager.createOffer(pc, OfferAnswerObserver(streamId) { sdp ->
                /// set Local Description
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
                // send sdp
                signalingManager.sendSDP("offer", sdp.description, body) { response, jsep ->
                    // set remote description - ANSWER
                    handlePublishResponse(streamId, response, jsep)
                }
            })

            SDKLogger.info("StreamManager", "Stream publish initiated: $streamId")
            true
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Publish failed", e)
            notifyError("Failed to publish stream: ${e.message}")
            false
        }
    }

    /**
     * Unpublish local stream from a room
     * - Stops video/audio capture
     * - Sends Janus "unpublish" request
     * - Closes peer connection and releases resources
     * - Notifies listeners
     */
    fun unpublishStream(roomId: Int) {
        try {
            // 1. Find the publisher stream for this room
            val streamId = activeStreams.entries.firstOrNull { entry ->
                val info = entry.value
                info.roomId == roomId && info.isPublisher
            }?.key ?: run {
                SDKLogger.warn("StreamManager", "No active publisher stream found for room $roomId")
                return
            }

            val streamInfo = activeStreams[streamId] ?: return

            SDKLogger.info("StreamManager", "Unpublishing stream: $streamId (room $roomId)")

            // 2. Stop media capture (very important to avoid zombie camera)
            webRtcManager.track.videoCapturer?.let { capturer ->
                try {
                    capturer.stopCapture()
                    SDKLogger.debug("StreamManager", "Video capturer stopped")
                } catch (e: Exception) {
                    SDKLogger.warn("StreamManager", "Failed to stop capturer", e)
                }
            }

            // 3. Disable tracks (prevents sending black frames or silence)
            localVideoTrack?.setEnabled(false)
            localAudioTrack?.setEnabled(false)

            // 4. Send Janus "unpublish" request
            val body = mapOf<String, Any>(
                "request" to "unpublish"
            )

            signalingManager.sendMessage(body, null) { response, _ ->
                handleUnpublishResponse(streamId, response.toMap())
            }

            // 5. Immediately clean up local resources (don't wait for server ack)
            closeStreamResources(streamInfo)

            // 6. Remove from active streams
            activeStreams.remove(streamId)



            SDKLogger.info("StreamManager", "Unpublish initiated successfully: $streamId")

        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Unpublish failed for room $roomId", e)
            notifyError("Failed to unpublish stream: ${e.message}")
        }
    }

    /**
     * Handle response/ack from Janus after sending unpublish
     */
    private fun handleUnpublishResponse(streamId: String, response: Map<String, Any>) {
        consoleLogE("--- ${response}")
        try {
                if (response["videoroom"] == "event" && response["unpublished"] == "ok") {
                    SDKLogger.info("StreamManager", "Janus confirmed unpublish success: $streamId")
                    // 7. Notify listeners
                    notifyStreamUnpublished(streamId)
                } /*else if (data?.containsKey("error") == true) {
                    val error = data["error"] as? String ?: "Unknown error"
                    SDKLogger.error("StreamManager", "Janus unpublish error: $error")
                    notifyError("Unpublish rejected by server: $error")
                }*/
        } catch (e: Exception) {
            SDKLogger.warn("StreamManager", "Error handling unpublish response", e)
        }
    }

    /**
     * Clean up all resources associated with a stream
     * Should be called for both publish and subscribe streams
     */
    private fun closeStreamResources(streamInfo: StreamInfo) {
        try {
            // Stop and release capturer (only for publishers)
            if (streamInfo.isPublisher) {
                webRtcManager.track.videoCapturer?.let { capturer ->
                    try {
                        capturer.stopCapture()
                        capturer.dispose()
                        webRtcManager.track.videoCapturer = null
                    } catch (e: Exception) {
                        SDKLogger.warn("StreamManager", "Error disposing capturer", e)
                    }
                }
            }

            // Disable and remove tracks
            localVideoTrack?.let { track ->
                track.setEnabled(false)
                track.dispose()
            }
            localAudioTrack?.let { track ->
                track.setEnabled(false)
                track.dispose()
            }

            // Close peer connection
            try {
                streamInfo.peerConnection.close()
            } catch (e: Exception) {
                SDKLogger.warn("StreamManager", "Error closing peer connection", e)
            }

            // Clear references
            localVideoTrack = null
            localAudioTrack = null

            SDKLogger.debug("StreamManager", "Resources cleaned for stream ${streamInfo.streamId}")
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Error during resource cleanup", e)
        }
    }

    /**
     * Subscribe to a remote participant's stream
     */
    fun subscribeToStream(roomId: Int, participantId: String): Boolean {
        return try {
            val streamId = generateStreamId("sub", "$roomId-$participantId")
            SDKLogger.info("StreamManager", "Subscribing to stream: $streamId")

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val observer = StreamPeerConnectionObserver(streamId, { pc ->
                onPeerConnectionReady(streamId, pc)
            },{},{},{ sid, candidates -> })

            val pc = webRtcManager.createPeerConnection(iceServers, observer)
                ?: return false.also { SDKLogger.error("StreamManager", "Failed to create peer connection") }

            activeStreams[streamId] = StreamInfo(
                streamId = streamId,
                roomId = roomId,
                participantId = participantId,
                peerConnection = pc,
//                mediaStream = null,
                isPublisher = false,
                createdAt = System.currentTimeMillis()
            )

            val body = mapOf<String, Any>()
            // Create answer for subscription
            webRtcManager.createAnswer(pc, OfferAnswerObserver(streamId) { sdp ->
                signalingManager.sendSDP("answer", sdp.description, body) { response, jsep ->
                    handleSubscribeResponse(streamId, response)
                }
            })

            SDKLogger.info("StreamManager", "Stream subscribe initiated: $streamId")
            notifyStreamSubscribed(streamId)
            true
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Subscribe failed", e)
            notifyError("Failed to subscribe: ${e.message}")
            false
        }
    }

    /**
     * Unsubscribe from a remote participant's stream
     */
    fun unsubscribeFromStream(roomId: Int, participantId: String) {
        try {
            val streamId = activeStreams.keys.firstOrNull {
                activeStreams[it]?.let { info ->
                    info.roomId == roomId &&
                            info.participantId == participantId &&
                            !info.isPublisher
                } ?: false
            } ?: return

            val streamInfo = activeStreams.remove(streamId)
            if (streamInfo != null) {
                closeStreamResources(streamInfo)
                notifyStreamUnsubscribed(streamId)
                SDKLogger.info("StreamManager", "Stream unsubscribed: $streamId")
            }
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Unsubscribe error", e)
        }
    }



    /**
     * Toggle audio on local stream
     */
    fun toggleAudio(roomId: Int, enabled: Boolean): Boolean {
        return try {
            val streamInfo = activeStreams.values.firstOrNull {
                it.roomId == roomId && it.isPublisher
            } ?: return false

//            streamInfo.mediaStream?.audioTracks?.forEach { it.setEnabled(enabled) }
            streamInfo.audioEnabled = enabled

            SDKLogger.debug("StreamManager", "Audio toggled: $enabled for $roomId")
            true
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Toggle audio failed", e)
            false
        }
    }

    /**
     * Toggle video on local stream
     */
    fun toggleVideo(roomId: Int, enabled: Boolean): Boolean {
        return try {
            val streamInfo = activeStreams.values.firstOrNull {
                it.roomId == roomId && it.isPublisher
            } ?: return false

//            streamInfo.mediaStream?.videoTracks?.forEach { it.setEnabled(enabled) }
            streamInfo.videoEnabled = enabled

            SDKLogger.debug("StreamManager", "Video toggled: $enabled for $roomId")
            true
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Toggle video failed", e)
            false
        }
    }

    /**
     * Get stream info by ID
     */
    fun getStream(streamId: String): StreamInfo? = activeStreams[streamId]

    /**
     * Get all active streams
     */
    fun getAllStreams(): List<StreamInfo> = activeStreams.values.toList()

    /**
     * Get streams for a specific room
     */
    fun getStreamsForRoom(roomId: Int): List<StreamInfo> =
        activeStreams.values.filter { it.roomId == roomId }

    /**
     * Handle signaling messages related to streams
     */
    fun handleSignalingMessage(message: Map<String, Any>) {
        try {
            val eventType = message["event"] as? String ?: return

            when (eventType) {
                "stream_published" -> {
                    val streamId = message["stream_id"] as? String ?: return
                    notifyStreamPublished(streamId)
                }
                "stream_unpublished" -> {
                    val streamId = message["stream_id"] as? String ?: return
                    val streamInfo = activeStreams.remove(streamId)
                    if (streamInfo != null) {
                        closeStreamResources(streamInfo)
                        notifyStreamUnpublished(streamId)
                    }
                }
                "ice_candidate" -> {
                    val candidate = message["candidate"] as? String ?: return
                    val streamId = message["stream_id"] as? String ?: return
                    val sdpMLineIndex = (message["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                    val sdpMid = message["sdpMid"] as? String ?: ""

                    handleIceCandidate(streamId, candidate, sdpMLineIndex, sdpMid)
                }
            }
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Message handling error", e)
        }
    }

    /**
     * Close all streams
     */
    fun closeAllStreams() {
        synchronized(lock) {
            activeStreams.values.forEach { closeStreamResources(it) }
            activeStreams.clear()
        }
        SDKLogger.info("StreamManager", "All streams closed")
    }

    /**
     * Set event listener
     */
    fun setEventListener(listener: JanusStreamEventListener) {
        synchronized(lock) {
            streamListeners.add(listener)
        }
    }

    /**
     * Remove event listener
     */
    fun removeEventListener(listener: JanusStreamEventListener) {
        synchronized(lock) {
            streamListeners.remove(listener)
        }
    }

    private fun onPeerConnectionReady(streamId: String, pc: PeerConnection) {
        SDKLogger.debug("StreamManager", "Peer connection ready: $streamId")
    }

    private fun handlePublishResponse(streamId: String, response: Map<String, Any>, jsep: Map<String, Any>?) {
        try {
            jsep ?: return
            val answerSdp = jsep["sdp"] as? String ?: return

            val streamInfo = activeStreams[streamId] ?: return
            val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

            streamInfo.peerConnection.setRemoteDescription(
                SetRemoteDescriptionObserver(streamId){
                    notifyStreamPublished(streamId)
                },
                answer
            )

        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Handle publish response failed", e)
            notifyError("Failed to handle publish response: ${e.message}")
        }
    }

    private fun handleSubscribeResponse(streamId: String, response: Map<String, Any>) {
        try {
            val jsep = response["jsep"] as? Map<String, Any> ?: return
            val offerSdp = jsep["sdp"] as? String ?: return

            val streamInfo = activeStreams[streamId] ?: return
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

            streamInfo.peerConnection.setRemoteDescription(
                SetRemoteDescriptionObserver(streamId){},
                offer
            )

            SDKLogger.debug("StreamManager", "Subscribe response handled: $streamId")
        } catch (e: Exception) {
            SDKLogger.error("StreamManager", "Handle subscribe response failed", e)
            notifyError("Failed to handle subscribe response: ${e.message}")
        }
    }

    private fun handleIceCandidate(
        streamId: String,
        candidate: String,
        sdpMLineIndex: Int,
        sdpMid: String
    ) {
        try {
            val streamInfo = activeStreams[streamId] ?: return
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            webRtcManager.addIceCandidate(streamInfo.peerConnection, iceCandidate)
        } catch (e: Exception) {
            SDKLogger.warn("StreamManager", "Error handling ICE candidate", e)
        }
    }

    fun isPublishing(roomId: Int): Boolean {
        return activeStreams.values.any { info ->
            info.roomId == roomId && info.isPublisher
        }
    }
    private fun generateStreamId(prefix: String, seed: String): String {
        return "$prefix-${seed.hashCode()}-${System.currentTimeMillis()}"
    }

    // Event notification methods

    private fun notifyStreamPublished(streamId: String) {
        synchronized(lock) {
            streamListeners.forEach {
                try {
                    it.onStreamPublished(streamId)
                } catch (e: Exception) {
                    SDKLogger.error("StreamManager", "Error in listener", e)
                }
            }
        }
    }

    private fun notifyStreamUnpublished(streamId: String) {
        synchronized(lock) {
            streamListeners.forEach {
                try {
                    it.onStreamUnpublished(streamId)
                } catch (e: Exception) {
                    SDKLogger.error("StreamManager", "Error in listener", e)
                }
            }
        }
    }

    private fun notifyStreamSubscribed(streamId: String) {
        synchronized(lock) {
            streamListeners.forEach {
                try {
                    it.onStreamSubscribed(streamId)
                } catch (e: Exception) {
                    SDKLogger.error("StreamManager", "Error in listener", e)
                }
            }
        }
    }

    private fun notifyStreamUnsubscribed(streamId: String) {
        synchronized(lock) {
            streamListeners.forEach {
                try {
                    it.onStreamUnsubscribed(streamId)
                } catch (e: Exception) {
                    SDKLogger.error("StreamManager", "Error in listener", e)
                }
            }
        }
    }

    private fun notifyError(error: String) {
        synchronized(lock) {
            streamListeners.forEach {
                try {
                    it.onError(error)
                } catch (e: Exception) {
                    SDKLogger.error("StreamManager", "Error in listener", e)
                }
            }
        }
    }

    /**
     * Stream information data class
     */
    data class StreamInfo(
        val streamId: String,
        val roomId: Int,
        val participantId: String? = null,
        val peerConnection: PeerConnection,
//        val mediaStream: MediaStream?,
        val isPublisher: Boolean,
        var audioEnabled: Boolean = true,
        var videoEnabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )
}

/**
 * Peer connection observer for streams
 */
class StreamPeerConnectionObserver(
    private val streamId: String,
    private val onReady: (PeerConnection) -> Unit,
    private val onIceCandidate1: (IceCandidate) -> Unit,           // new: callback to send to Janus
    private val onIceGatheringComplete: () -> Unit,
    private val onIceCandidatesRemoved1:(streamId:String, candidates: Array<IceCandidate>) -> Unit
) : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        SDKLogger.debug("StreamPeerConn", "ICE connection state: $newState")
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        if (newState == PeerConnection.IceGatheringState.COMPLETE) {
            consoleLogE("ICE gathering completed for stream $streamId")
            onIceGatheringComplete()   // send { "completed": true } or null candidate
        }
    }
    override fun onIceCandidate(candidate: IceCandidate) {
        onIceCandidate1(candidate)
    }
    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        onIceCandidatesRemoved1(streamId, candidates)
    }
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
}

abstract class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
}

/**
 * SDP offer/answer observer
 */
class OfferAnswerObserver(
    private val streamId: String,
    private val onSuccess: (SessionDescription) -> Unit
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {
        SDKLogger.debug("OfferAnswer", "SDP created successfully")
        onSuccess(sdp)
    }

    override fun onSetSuccess() {
        SDKLogger.debug("OfferAnswer", "SDP set successfully")
    }

    override fun onCreateFailure(error: String) {
        SDKLogger.error("OfferAnswer", "Create failed: $error")
    }

    override fun onSetFailure(error: String) {
        SDKLogger.error("OfferAnswer", "Set failed: $error")
    }
}

abstract class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {
        SDKLogger.debug("OfferAnswer", "SDP created successfully")
    }

    override fun onSetSuccess() {
        SDKLogger.debug("OfferAnswer", "SDP set successfully")
    }

    override fun onCreateFailure(error: String) {
        SDKLogger.error("OfferAnswer", "Create failed: $error")
    }

    override fun onSetFailure(error: String) {
        SDKLogger.error("OfferAnswer", "Set failed: $error")
    }
}

/**
 * Set remote description observer
 */
class SetRemoteDescriptionObserver(private val streamId: String, val onSuccess:()->Unit) : SdpObserver {
    override fun onSetSuccess() {
        SDKLogger.debug("SetRemoteDesc", "Remote description set for $streamId")
        onSuccess.invoke()
    }

    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {
        SDKLogger.error("SetRemoteDesc", "Failed: $error")
    }
}

/**
 * Stream event listener interface
 */
interface JanusStreamEventListener {
    fun onStreamPublished(streamId: String)
    fun onStreamUnpublished(streamId: String)
    fun onStreamSubscribed(streamId: String)
    fun onStreamUnsubscribed(streamId: String)
    fun onError(error: String)
}

/**
 * Stream publication configuration
 */
data class PublishStreamConfig(
    val roomId: String,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val iceServers: List<PeerConnection.IceServer>,
    val videoBitrate: Int = 2000
) {
    fun generateStreamId() = "stream-${System.currentTimeMillis()}"
}

/**
 * Stream subscription configuration
 */
data class SubscribeStreamConfig(
    val roomId: String,
    val participantId: String,
    val iceServers: List<PeerConnection.IceServer>
)

/**
 * Callback for peer connection creation
 */
interface CreatePeerConnectionCallback {
    fun onIceCandidate(candidate: IceCandidate)
    fun onIceGatheringComplete()
    fun onAddStream(stream: MediaStream)
    fun onRemoveStream(stream: MediaStream?)
    fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?)
}

/**
 * Callback for offer creation
 */
interface CreateOfferCallback {
    fun onCreateOfferSuccess(sdp: SessionDescription?)
    fun onCreateFailed(error: String?)
}

/**
 * Callback for answer creation
 */
interface CreateAnswerCallback {
    fun onSetAnswerSuccess(sdp: SessionDescription)
    fun onSetAnswerFailed(error: String)
}