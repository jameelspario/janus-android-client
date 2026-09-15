package com.example.janus.client.videoroom

import com.example.janus.client.SDKLogger
import com.example.janus.client.UserRole
import com.example.janus.client.signaling.JanusSignalingClient
import com.example.janus.client.track.LocalAudioTrack
import com.example.janus.client.track.LocalVideoTrack
import com.example.janus.client.webrtc.SdpUtils.createOfferSuspend
import com.example.janus.client.webrtc.SdpUtils.setLocalDescriptionSuspend
import com.example.janus.client.webrtc.SdpUtils.setRemoteDescriptionSuspend
import com.example.janus.client.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.math.BigInteger

/**
 * Coordinates the Janus VideoRoom plugin protocol for publishing, room joining, and signaling events.
 */
class VideoRoomPlugin(
    private val scope: CoroutineScope,
    private val signalingClient: JanusSignalingClient,
    private val webRtcEngine: WebRtcEngine,
    val subscriptionManager: RemoteSubscriptionManager
) {
    private val TAG = "VideoRoomPlugin"

    var sessionId: BigInteger? = null
        internal set

    var publisherHandleId: BigInteger? = null
        internal set

    var privateId: Int? = null
        internal set

    private var publisherPeerConnection: PeerConnection? = null

    /**
     * Joins (or creates and joins) a VideoRoom room.
     */
    suspend fun joinRoom(
        roomId: Int,
        userId: String,
        displayName: String,
        role: UserRole
    ): VideoRoomJoinResult {
        val sId = sessionId ?: throw IllegalStateException("Janus session not established")
        val hId = publisherHandleId ?: throw IllegalStateException("VideoRoom handle not attached")

        val joinBody = mapOf<String, Any>(
            "request" to "join",
            "room" to roomId,
            "ptype" to "publisher",
            "display" to displayName
        )

        val response = signalingClient.sendMessage(sId, hId, joinBody)
        val pluginData = response.pluginDataMap
        val vr = pluginData["videoroom"] as? String

        if (vr == "joined") {
            return parseJoinResponse(roomId, pluginData)
        }

        // Check if room needs to be created first (HOST role only)
        val errorCode = pluginData["error_code"] as? Int ?: response.errorCode
        if (errorCode == 427 || (errorCode == 426 && role == UserRole.HOST)) {
            if (role == UserRole.HOST) {
                SDKLogger.info(TAG, "Room $roomId does not exist, creating as HOST...")
                createRoom(sId, hId, roomId, displayName)
                // Retry join
                val retryResponse = signalingClient.sendMessage(sId, hId, joinBody)
                return parseJoinResponse(roomId, retryResponse.pluginDataMap)
            }
        }

        val errorReason = pluginData["error"] as? String ?: response.error ?: "Unknown join error"
        throw RuntimeException("Join room failed: $errorReason (code $errorCode)")
    }

    private suspend fun createRoom(sessionId: BigInteger, handleId: BigInteger, roomId: Int, displayName: String) {
        val createBody = mapOf<String, Any>(
            "request" to "create",
            "room" to roomId,
            "ptype" to "publisher",
            "display" to displayName,
            "description" to "Room $roomId",
            "publishers" to 20,
            "bitrate" to 2_000_000,
            "fir_freq" to 10,
            "require_pvtid" to true
        )
        val resp = signalingClient.sendMessage(sessionId, handleId, createBody)
        val vr = resp.pluginDataMap["videoroom"] as? String
        val errCode = resp.pluginDataMap["error_code"] as? Int ?: resp.errorCode
        if (vr != "created" && errCode != 429 && errCode != 436) {
            val err = resp.pluginDataMap["error"] as? String ?: resp.error ?: "Unknown error"
            throw RuntimeException("Failed to create room $roomId: $err (code $errCode)")
        }
    }

    private fun parseJoinResponse(roomId: Int, data: Map<String, Any>): VideoRoomJoinResult {
        val participantId = data["id"]?.toString() ?: ""
        privateId = (data["private_id"] as? Number)?.toInt()
        val publishers = mutableListOf<PublisherFeedInfo>()

        val pubsList = data["publishers"] as? List<*>
        pubsList?.forEach { item ->
            if (item is Map<*, *>) {
                val feedId = (item["id"] as? Number)?.toLong()?.toBigInteger()
                val display = item["display"] as? String ?: ""
                val audioCodec = item["audio_codec"] as? String
                val videoCodec = item["video_codec"] as? String
                if (feedId != null) {
                    publishers.add(PublisherFeedInfo(feedId, display, audioCodec, videoCodec))
                }
            }
        }

        return VideoRoomJoinResult(roomId, participantId, privateId, publishers)
    }

    /**
     * Publishes local media tracks to the VideoRoom.
     */
    suspend fun publishLocalStream(
        roomId: Int,
        audioTrack: LocalAudioTrack?,
        videoTrack: LocalVideoTrack?
    ): PeerConnection {
        val sId = sessionId ?: throw IllegalStateException("Session not established")
        val hId = publisherHandleId ?: throw IllegalStateException("Handle not attached")

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                SDKLogger.debug(TAG, "Publisher ICE Connection State: $newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    scope.launch(Dispatchers.IO) {
                        signalingClient.sendTrickleCandidate(sId, hId, null)
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
                    signalingClient.sendTrickleCandidate(sId, hId, map)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {}
        }

        val pc = webRtcEngine.createPeerConnection(observer)
            ?: throw RuntimeException("Failed to create PeerConnection for publisher")
        publisherPeerConnection = pc

        audioTrack?.let { pc.addTrack(it.rtcAudioTrack) }
        videoTrack?.let { pc.addTrack(it.rtcVideoTrack) }

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        val offer = pc.createOfferSuspend(mediaConstraints)
        pc.setLocalDescriptionSuspend(offer)

        val publishBody = mapOf<String, Any>(
            "request" to "publish",
            "audio" to (audioTrack != null),
            "video" to (videoTrack != null)
        )
        val jsepOffer = mapOf<String, Any>(
            "type" to offer.type.canonicalForm(),
            "sdp" to offer.description
        )

        val response = signalingClient.sendMessage(sId, hId, publishBody, jsepOffer)
        val answerJsep = response.jsep ?: throw RuntimeException("No JSEP answer received for publish: ${response.rawJson}")
        val answerSdp = answerJsep["sdp"] as? String ?: throw RuntimeException("No SDP in answer JSEP")

        val remoteAnswer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescriptionSuspend(remoteAnswer)

        SDKLogger.info(TAG, "Publisher stream published and remote answer applied")
        return pc
    }

    suspend fun unpublishLocalStream() {
        val sId = sessionId ?: return
        val hId = publisherHandleId ?: return

        try {
            signalingClient.sendMessage(sId, hId, mapOf("request" to "unpublish"))
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Unpublish request error: ${e.message}")
        } finally {
            publisherPeerConnection?.dispose()
            publisherPeerConnection = null
        }
    }

    suspend fun leaveRoom(roomId: Int) {
        val sId = sessionId ?: return
        val hId = publisherHandleId ?: return

        try {
            unpublishLocalStream()
            signalingClient.sendMessage(sId, hId, mapOf("request" to "leave", "room" to roomId))
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Leave room request error: ${e.message}")
        }
    }

    fun handleUnsolicitedEvent(eventMap: Map<String, Any>, roomId: Int?) {
        val data = eventMap["data"] as? Map<*, *> ?: return
        val videoroom = data["videoroom"] as? String ?: return
        val currentRoom = (data["room"] as? Number)?.toInt() ?: roomId ?: return
        val sId = sessionId ?: return

        when (videoroom) {
            "event" -> {
                // Check for new publishers list
                val publishersList = data["publishers"] as? List<*>
                publishersList?.forEach { item ->
                    if (item is Map<*, *>) {
                        val feedId = (item["id"] as? Number)?.toLong()?.toBigInteger()
                        val display = item["display"] as? String ?: ""
                        if (feedId != null) {
                            SDKLogger.info(TAG, "New publisher feed discovered: $feedId ($display)")
                            subscriptionManager.subscribe(sId, currentRoom, feedId, display)
                        }
                    }
                }

                // Check for unpublished feed
                val unpublished = data["unpublished"]
                if (unpublished != null && unpublished != "ok") {
                    val feedId = (unpublished as? Number)?.toLong()?.toBigInteger()
                    if (feedId != null) {
                        SDKLogger.info(TAG, "Publisher feed unpublished: $feedId")
                        subscriptionManager.unsubscribe(sId, feedId)
                    }
                }

                // Check for participant leaving
                val leaving = data["leaving"]
                if (leaving != null && leaving != "ok") {
                    val feedId = (leaving as? Number)?.toLong()?.toBigInteger()
                    if (feedId != null) {
                        SDKLogger.info(TAG, "Publisher feed left room: $feedId")
                        subscriptionManager.unsubscribe(sId, feedId)
                    }
                }

                // Check for room destroyed
                if (data.containsKey("destroyed")) {
                    SDKLogger.info(TAG, "Room destroyed: $currentRoom")
                    subscriptionManager.unsubscribeAll(sId)
                }
            }
        }
    }
}
