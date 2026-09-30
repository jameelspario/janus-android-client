package com.example.janus.client.videoroom

import com.example.janus.client.SDKLogger
import com.example.janus.client.UserRole
import com.example.janus.client.room.RoomOptions
import com.example.janus.client.signaling.JanusSignalingClient
import com.example.janus.client.signaling.toJanusId
import com.example.janus.client.track.LocalAudioTrack
import com.example.janus.client.track.LocalVideoTrack
import com.example.janus.client.webrtc.RemoteCandidateBuffer
import com.example.janus.client.webrtc.SdpUtils.audioLevelOrNull
import com.example.janus.client.webrtc.SdpUtils.createOfferSuspend
import com.example.janus.client.webrtc.SdpUtils.getStatsSuspend
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
 *
 * Message-for-message this follows the app's own working client
 * (`com.bindaslive.janus.JanusService` / `JanusClient` / `VideoRoomManager`):
 *  - join as `ptype:"publisher"`; on error 426 (no such room) create the room and join again
 *  - publish with `configure {audio, video, bitrate}` + SDP offer, apply the answer
 *  - remote ICE candidates Janus trickles are applied to the matching PeerConnection
 *  - `unpublished` / `leaving` → drop that feed, `publishers` → subscribe
 *  - `unpublish` / `leave` on the way out
 */
class VideoRoomPlugin(
    private val scope: CoroutineScope,
    private val signalingClient: JanusSignalingClient,
    private val webRtcEngine: WebRtcEngine,
    val subscriptionManager: RemoteSubscriptionManager,
    private val options: RoomOptions = RoomOptions(),
) {
    private val TAG = "VideoRoomPlugin"

    var sessionId: BigInteger? = null
        internal set

    var publisherHandleId: BigInteger? = null
        internal set

    /** Stored after a successful publisher join; passed to subscriber join as private_id. */
    var privateId: Long? = null
        internal set

    /** This client's own publisher id in the room (the `id` from `joined`). */
    var myFeedId: BigInteger? = null
        internal set

    /** Janus `talking` / `stopped-talking` for a feed (rooms created with `audiolevel_event`). */
    var onPublisherTalking: ((feedId: BigInteger, talking: Boolean) -> Unit)? = null

    /** Janus hung up the publisher PeerConnection (e.g. "DTLS timeout") - app's `onHangup`. */
    var onPublisherHangup: ((reason: String?) -> Unit)? = null

    private var publisherPeerConnection: PeerConnection? = null
    private val publisherCandidates = RemoteCandidateBuffer(TAG)

    val isPublishing: Boolean
        get() = publisherPeerConnection != null

    // ─────────────────────────────────────────────────────────────────────────
    // Room Join
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Joins (or creates and joins) a VideoRoom room as a publisher.
     * Returns the join result including any pre-existing publishers with their stream mids.
     *
     * Like the app's client, whoever finds the room missing (error 426) creates it - host or
     * guest - so a guest arriving first doesn't fail the whole join.
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

        // Room does not exist → create it, then join again (app: error_code 426 → createRoom)
        val errorCode = (pluginData["error_code"] as? Number)?.toInt() ?: response.errorCode
        if (errorCode == ERROR_NO_SUCH_ROOM) {
            SDKLogger.info(TAG, "Room $roomId does not exist, creating it (role=$role)...")
            createRoom(sId, hId, roomId, displayName)
            val retryResponse = signalingClient.sendMessage(sId, hId, joinBody)
            val retryData = retryResponse.pluginDataMap
            if (retryData["videoroom"] == "joined") {
                return parseJoinResponse(roomId, retryData)
            }
            val retryErr = retryData["error"] as? String ?: retryResponse.error ?: "Unknown join error"
            throw RuntimeException("Join room $roomId failed after create: $retryErr (code ${retryResponse.errorCode})")
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
        val createBody = mutableMapOf<String, Any>(
            "request"     to "create",
            "room"        to roomId,
            "description" to "Room $roomId",
            "publishers"  to options.maxPublishers,
            "bitrate"     to options.publishBitrate,
            "fir_freq"    to 10,
            // Audio-level reporting (the app requested these alongside its join) - lets Janus
            // push `talking` / `stopped-talking` events for every publisher.
            "audiolevel_ext"       to true,
            "audiolevel_event"     to true,
            "audio_active_packets" to 100,
            "audio_level_average"  to 25,
        )
        if (options.requirePrivateId) createBody["require_pvtid"] = true

        val resp = signalingClient.sendMessage(sessionId, handleId, createBody)
        val vr = resp.pluginDataMap["videoroom"] as? String
        val errCode = (resp.pluginDataMap["error_code"] as? Number)?.toInt() ?: resp.errorCode
        // 427 = room already exists (someone else created it first) - fine, just join.
        if (vr != "created" && errCode != ERROR_ROOM_EXISTS) {
            val err = resp.pluginDataMap["error"] as? String ?: resp.error ?: "Unknown error"
            throw RuntimeException("Failed to create room $roomId: $err (code $errCode)")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response Parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseJoinResponse(roomId: Int, data: Map<String, Any>): VideoRoomJoinResult {
        val participantId = data["id"]?.toString() ?: ""
        myFeedId = data["id"].toJanusId()
        privateId = data["private_id"].toJanusId()?.toLong()

        val publishers = parsePublishersList(data["publishers"] as? List<*>)

        SDKLogger.info(TAG, "Joined room $roomId as $participantId (privateId=$privateId), " +
            "found ${publishers.size} existing publisher(s)")

        return VideoRoomJoinResult(roomId, participantId, privateId, publishers)
    }

    /**
     * Parses the `publishers[]` array from any Janus VideoRoom response.
     * Each publisher entry may contain a `streams[]` sub-array with per-mid info.
     *
     * Mirrors the JS loop in videoroomtest.js (and the app's MessageParser.handlePublisher):
     * dummy publishers are skipped, as is this client's own feed.
     */
    private fun parsePublishersList(pubsList: List<*>?): List<PublisherFeedInfo> {
        if (pubsList == null) return emptyList()
        return pubsList.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            if (item["dummy"] == true) return@mapNotNull null
            val feedId  = item["id"].toJanusId() ?: return@mapNotNull null
            if (feedId == myFeedId) return@mapNotNull null
            val display = item["display"] as? String ?: ""
            val streams = parsePublisherStreams(item["streams"] as? List<*>)
            PublisherFeedInfo(feedId, display, streams)
        }
    }

    /** Parses the per-publisher `streams[]` sub-array, skipping `disabled` streams (app does too). */
    private fun parsePublisherStreams(streamsList: List<*>?): List<PublisherStreamInfo> {
        if (streamsList == null) return emptyList()
        return streamsList.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            if (item["disabled"] == true) return@mapNotNull null
            val mid   = item["mid"]?.toString() ?: return@mapNotNull null
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
     * Uses the `configure` request with an SDP offer (matching the app's publishOwnFeed):
     * `{request:"configure", audio, video, bitrate}` + offer, then applies Janus's answer.
     */
    suspend fun publishLocalStream(
        roomId: Int,
        audioTrack: LocalAudioTrack?,
        videoTrack: LocalVideoTrack?
    ): PeerConnection {
        val sId = sessionId ?: throw IllegalStateException("Session not established")
        val hId = publisherHandleId ?: throw IllegalStateException("Handle not attached")

        // A previous publish (e.g. cohost re-publishing) must not leak its PeerConnection.
        closePublisherPeerConnection()

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
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {
                publisherPeerConnection?.removeIceCandidates(c)
            }
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

        val hasAudio = audioTrack != null
        val hasVideo = videoTrack != null

        // Same constraints as the app's createOffer(videoOffer, audioOffer).
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", hasAudio.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", hasVideo.toString()))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        val offer = pc.createOfferSuspend(mediaConstraints)
        pc.setLocalDescriptionSuspend(offer)

        val configureBody = mutableMapOf<String, Any>(
            "request" to "configure",
            "audio"   to hasAudio,
            "video"   to hasVideo
        )
        if (hasAudio || hasVideo) configureBody["bitrate"] = options.publishBitrate

        val jsepOffer = mapOf<String, Any>(
            "type" to offer.type.canonicalForm(),
            "sdp"  to offer.description
        )

        val response = signalingClient.sendMessage(sId, hId, configureBody, jsepOffer)
        val data = response.pluginDataMap
        if (data["configured"] != "ok" && response.jsep == null) {
            val err = data["error"] as? String ?: response.error ?: "no answer"
            throw RuntimeException("Configure (publish) failed: $err (code ${response.errorCode})")
        }
        val answerJsep = response.jsep
            ?: throw RuntimeException("No JSEP answer for configure: ${response.rawJson}")
        val answerSdp  = answerJsep["sdp"] as? String
            ?: throw RuntimeException("No SDP in answer JSEP")

        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        publisherCandidates.flush(pc)

        SDKLogger.info(TAG, "Publisher stream configured and remote answer applied")
        return pc
    }

    suspend fun unpublishLocalStream() {
        val sId = sessionId ?: return
        val hId = publisherHandleId ?: return
        if (publisherPeerConnection == null) return
        try {
            signalingClient.sendMessage(sId, hId, mapOf("request" to "unpublish"))
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Unpublish request error: ${e.message}")
        } finally {
            closePublisherPeerConnection()
        }
    }

    private fun closePublisherPeerConnection() {
        publisherCandidates.clear()
        publisherPeerConnection?.let { pc ->
            try { pc.dispose() } catch (e: Exception) {
                SDKLogger.warn(TAG, "Error disposing publisher PC: ${e.message}")
            }
        }
        publisherPeerConnection = null
    }

    /** Leaves the room (app's `leaveStream`), unpublishing first if publishing. */
    suspend fun leaveRoom(roomId: Int) {
        val sId = sessionId ?: return
        val hId = publisherHandleId ?: return
        try {
            unpublishLocalStream()
            signalingClient.sendMessage(sId, hId, mapOf("request" to "leave"), timeoutMs = 5_000L)
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Leave room $roomId error: ${e.message}")
        }
    }

    /** Detaches the publisher handle (app's `detachPlugin`). */
    suspend fun detach() {
        val sId = sessionId ?: return
        val hId = publisherHandleId ?: return
        signalingClient.detachPlugin(sId, hId)
        publisherHandleId = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ICE / Handle notifications
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A candidate Janus trickled for [senderHandleId] - applied to the publisher PC or handed to
     * the subscriber side (app's `onIceCandidate` callback → `peerConnection.addIceCandidate`).
     */
    fun addRemoteCandidate(senderHandleId: BigInteger, candidate: Map<String, Any>?) {
        if (senderHandleId == publisherHandleId) {
            publisherCandidates.add(publisherPeerConnection, candidate)
        } else {
            subscriptionManager.addRemoteCandidate(senderHandleId, candidate)
        }
    }

    /** `webrtcup` / `media` / `slowlink` / `hangup` / `detached` for one of our handles. */
    fun handleHandleEvent(type: String, senderHandleId: BigInteger, raw: Map<String, Any>) {
        if (senderHandleId != publisherHandleId) {
            subscriptionManager.handleHandleEvent(type, senderHandleId, raw)
            return
        }
        when (type) {
            "webrtcup" -> SDKLogger.info(TAG, "Publisher PeerConnection is up")
            "media" -> SDKLogger.debug(TAG, "Publisher media ${raw["type"]} receiving=${raw["receiving"]}")
            "slowlink" -> SDKLogger.warn(TAG, "Publisher slowlink: $raw")
            "hangup" -> {
                val reason = raw["reason"] as? String
                SDKLogger.warn(TAG, "Publisher hung up: $reason")
                onPublisherHangup?.invoke(reason)
            }
            "detached" -> {
                SDKLogger.warn(TAG, "Publisher handle detached by Janus")
                publisherHandleId = null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio levels (app's JanusService.checkAudioLevels, outgoing side)
    // ─────────────────────────────────────────────────────────────────────────

    /** This device's own captured mic level (0..1), from the `media-source` audio stats. */
    suspend fun localAudioLevel(): Float? {
        val pc = publisherPeerConnection ?: return null
        val report = try { pc.getStatsSuspend() } catch (e: Exception) { return null }
        return report.statsMap.values
            .filter { it.type == "media-source" && it.members["kind"] == "audio" }
            .firstNotNullOfOrNull { it.audioLevelOrNull() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unsolicited Event Routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Routes unsolicited Janus events to the appropriate handler.
     *
     *  - Events from the **subscriber handle** → [RemoteSubscriptionManager.handleUnsolicitedEvent]
     *  - Events from the **publisher handle** → handled inline below:
     *      - `publishers[]`  → new feeds available  → subscribeTo
     *      - `leaving`       → publisher left        → unsubscribeFrom
     *      - `unpublished`   → publisher unpublished → unsubscribeFrom
     *      - `kicked`        → publisher kicked      → unsubscribeFrom
     *      - `talking` / `stopped-talking` → [onPublisherTalking]
     *      - `destroyed`     → room destroyed        → unsubscribeAll
     */
    fun handleUnsolicitedEvent(eventMap: Map<String, Any>, roomId: Int?) {
        val data = eventMap["data"] as? Map<*, *> ?: return
        val videoroom = data["videoroom"] as? String ?: return
        val currentRoom = (data["room"] as? Number)?.toInt() ?: roomId ?: return
        val sId = sessionId ?: return
        val senderHandleId = eventMap["sender"] as? BigInteger

        // ── Route subscriber-handle events ──────────────────────────────────
        if (senderHandleId != null && senderHandleId != publisherHandleId) {
            @Suppress("UNCHECKED_CAST")
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

                // Publisher unpublished / left / was kicked. "ok" is the answer to our own
                // request, not someone else's feed.
                listOf("unpublished", "leaving", "kicked").forEach { key ->
                    val value = dataMap[key] ?: return@forEach
                    if (value == "ok") return@forEach
                    val feedId = value.toJanusId() ?: return@forEach
                    SDKLogger.info(TAG, "Feed $feedId $key in room $currentRoom")
                    subscriptionManager.unsubscribeFrom(sId, feedId)
                }

                dataMap["error"]?.let { err ->
                    SDKLogger.warn(TAG, "Publisher handle error event: $err (code ${dataMap["error_code"]})")
                }
            }

            "talking", "stopped-talking" -> {
                val feedId = dataMap["id"].toJanusId() ?: return
                onPublisherTalking?.invoke(feedId, videoroom == "talking")
            }

            "destroyed" -> {
                SDKLogger.info(TAG, "Room $currentRoom was destroyed")
                subscriptionManager.unsubscribeAll(sId)
            }
        }
    }

    companion object {
        /** JANUS_VIDEOROOM_ERROR_NO_SUCH_ROOM */
        const val ERROR_NO_SUCH_ROOM = 426
        /** JANUS_VIDEOROOM_ERROR_ROOM_EXISTS */
        const val ERROR_ROOM_EXISTS = 427
    }
}
