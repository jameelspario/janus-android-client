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
 * Coordinates the Janus VideoRoom plugin protocol for publishing, room joining, and
 * unsolicited signaling events (new publishers, leaving publishers, room destroyed, etc.).
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

    /** Stored after a successful publisher join; passed to subscriber join as private_id. */
    var privateId: Int? = null
        internal set

    private var publisherPeerConnection: PeerConnection? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Room Join
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Joins (or creates and joins) a VideoRoom room as a publisher.
     * Returns the join result including any pre-existing publishers with their stream mids.
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
            "room"    to roomId,
            "ptype"   to "publisher",
            "display" to displayName
        )

        val response = signalingClient.sendMessage(sId, hId, joinBody)
        val pluginData = response.pluginDataMap
        val vr = pluginData["videoroom"] as? String

        if (vr == "joined") {
            return parseJoinResponse(roomId, pluginData)
        }

        // Room does not exist — HOST can create it
        val errorCode = pluginData["error_code"] as? Int ?: response.errorCode
        if ((errorCode == 427 || errorCode == 426) && role == UserRole.HOST) {
            SDKLogger.info(TAG, "Room $roomId does not exist, creating as HOST...")
            createRoom(sId, hId, roomId, displayName)
            val retryResponse = signalingClient.sendMessage(sId, hId, joinBody)
            return parseJoinResponse(roomId, retryResponse.pluginDataMap)
        }

        val errorReason = pluginData["error"] as? String ?: response.error ?: "Unknown join error"
        throw RuntimeException("Join room failed: $errorReason (code $errorCode)")
    }

    private suspend fun createRoom(
        sessionId: BigInteger,
        handleId: BigInteger,
        roomId: Int,
        displayName: String
    ) {
        val createBody = mapOf<String, Any>(
            "request"     to "create",
            "room"        to roomId,
            "ptype"       to "publisher",
            "display"     to displayName,
            "description" to "Room $roomId",
            "publishers"  to 20,
            "bitrate"     to 2_000_000,
            "fir_freq"    to 10,
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

    // ─────────────────────────────────────────────────────────────────────────
    // Response Parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseJoinResponse(roomId: Int, data: Map<String, Any>): VideoRoomJoinResult {
        val participantId = data["id"]?.toString() ?: ""
        privateId = (data["private_id"] as? Number)?.toInt()

        val publishers = parsePublishersList(data["publishers"] as? List<*>)

        SDKLogger.info(TAG, "Joined room $roomId as $participantId (privateId=$privateId), " +
            "found ${publishers.size} existing publisher(s)")

        return VideoRoomJoinResult(roomId, participantId, privateId, publishers)
    }

    /**
     * Parses the `publishers[]` array from any Janus VideoRoom response.
     * Each publisher entry may contain a `streams[]` sub-array with per-mid info.
     *
     * Mirrors the JS loop in videoroomtest.js:
     * ```js
     * for(let f in list) {
     *     let streams = list[f]["streams"];
     *     for(let i in streams) { stream["id"] = id; stream["display"] = display; }
     * }
     * ```
     */
    private fun parsePublishersList(pubsList: List<*>?): List<PublisherFeedInfo> {
        if (pubsList == null) return emptyList()
        return pubsList.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val feedId  = (item["id"] as? Number)?.toLong()?.toBigInteger() ?: return@mapNotNull null
            val display = item["display"] as? String ?: ""
            // Skip dummy entries
            if (item["dummy"] == true) return@mapNotNull null
            val streams = parsePublisherStreams(item["streams"] as? List<*>)
            PublisherFeedInfo(feedId, display, streams)
        }
    }

    /** Parses the per-publisher `streams[]` sub-array. */
    private fun parsePublisherStreams(streamsList: List<*>?): List<PublisherStreamInfo> {
        if (streamsList == null) return emptyList()
        return streamsList.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val mid   = item["mid"] as? String ?: return@mapNotNull null
            val type  = item["type"] as? String ?: return@mapNotNull null
            val codec = item["codec"] as? String
            PublisherStreamInfo(mid, type, codec)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local Publishing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Publishes local audio/video tracks to the VideoRoom.
     * Uses the `configure` request with an SDP offer (matching videoroomtest.js).
     */
    suspend fun publishLocalStream(
        roomId: Int,
        audioTrack: LocalAudioTrack?,
        videoTrack: LocalVideoTrack?
    ): PeerConnection {
        val sId = sessionId ?: throw IllegalStateException("Session not established")
        val hId = publisherHandleId ?: throw IllegalStateException("Handle not attached")

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                SDKLogger.debug(TAG, "Publisher ICE: $s")
            }
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) {
                    scope.launch(Dispatchers.IO) {
                        signalingClient.sendTrickleCandidate(sId, hId, null)
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    signalingClient.sendTrickleCandidate(sId, hId, mapOf(
                        "candidate"     to candidate.sdp,
                        "sdpMid"        to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    ))
                }
            }
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<MediaStream>) {}
            override fun onTrack(t: RtpTransceiver) {}
        }

        val pc = webRtcEngine.createPeerConnection(observer)
            ?: throw RuntimeException("Failed to create publisher PeerConnection")
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

        // Send configure + SDP offer (matches videoroomtest.js publishOwnFeed)
        val configureBody = mapOf<String, Any>(
            "request" to "configure",
            "audio"   to (audioTrack != null),
            "video"   to (videoTrack != null)
        )
        val jsepOffer = mapOf<String, Any>(
            "type" to offer.type.canonicalForm(),
            "sdp"  to offer.description
        )

        val response = signalingClient.sendMessage(sId, hId, configureBody, jsepOffer)
        val answerJsep = response.jsep
            ?: throw RuntimeException("No JSEP answer for configure: ${response.rawJson}")
        val answerSdp  = answerJsep["sdp"] as? String
            ?: throw RuntimeException("No SDP in answer JSEP")

        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))

        SDKLogger.info(TAG, "Publisher stream configured and remote answer applied")
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
            SDKLogger.warn(TAG, "Leave room error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unsolicited Event Routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Routes unsolicited Janus events to the appropriate handler.
     *
     *  - Events from the **subscriber handle** → [RemoteSubscriptionManager.handleUnsolicitedEvent]
     *  - Events from the **publisher handle** → handled inline below:
     *      - `publishers[]`  → new feeds available  → [subscribeTo]
     *      - `leaving`       → publisher left        → [unsubscribeFrom]
     *      - `unpublished`   → publisher unpublished → [unsubscribeFrom]
     *      - `destroyed`     → room destroyed        → [unsubscribeAll]
     */
    fun handleUnsolicitedEvent(eventMap: Map<String, Any>, roomId: Int?) {
        val data = eventMap["data"] as? Map<*, *> ?: return
        val videoroom = data["videoroom"] as? String ?: return
        val currentRoom = (data["room"] as? Number)?.toInt() ?: roomId ?: return
        val sId = sessionId ?: return
        val senderHandleId = eventMap["sender"] as? BigInteger

        // ── Route subscriber-handle events ──────────────────────────────────
        if (senderHandleId != null && senderHandleId != publisherHandleId) {
            val jsep = eventMap["jsep"] as? Map<String, Any>
            subscriptionManager.handleUnsolicitedEvent(sId, senderHandleId, eventMap, jsep)
            return
        }

        // ── Publisher-handle events ─────────────────────────────────────────
        @Suppress("UNCHECKED_CAST")
        val dataMap = data as Map<String, Any>

        when (videoroom) {
            "event" -> {
                // New publisher(s) joined
                val publishersList = dataMap["publishers"] as? List<*>
                if (publishersList != null) {
                    val feeds = parsePublishersList(publishersList)
                    if (feeds.isNotEmpty()) {
                        SDKLogger.info(TAG, "${feeds.size} new publisher(s) in room $currentRoom: ${feeds.map { it.feedId }}")
                        // Single subscribeTo call — uses "update" if handle already exists
                        subscriptionManager.subscribeTo(sId, currentRoom, privateId, feeds)
                    }
                }

                // Publisher unpublished their stream
                val unpublished = dataMap["unpublished"]
                if (unpublished != null && unpublished != "ok") {
                    val feedId = (unpublished as? Number)?.toLong()?.toBigInteger()
                    if (feedId != null) {
                        SDKLogger.info(TAG, "Feed $feedId unpublished in room $currentRoom")
                        subscriptionManager.unsubscribeFrom(sId, feedId)
                    }
                }

                // Publisher left the room
                val leaving = dataMap["leaving"]
                if (leaving != null && leaving != "ok") {
                    val feedId = (leaving as? Number)?.toLong()?.toBigInteger()
                    if (feedId != null) {
                        SDKLogger.info(TAG, "Feed $feedId left room $currentRoom")
                        subscriptionManager.unsubscribeFrom(sId, feedId)
                    }
                }

                // Room destroyed
                if (dataMap.containsKey("destroyed")) {
                    SDKLogger.info(TAG, "Room $currentRoom was destroyed")
                    subscriptionManager.unsubscribeAll(sId)
                }
            }
        }
    }
}
