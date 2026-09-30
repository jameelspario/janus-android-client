package com.example.janus.client.videoroom

import com.example.janus.client.SDKLogger
import com.example.janus.client.signaling.JanusSignalingClient
import com.example.janus.client.signaling.toJanusId
import com.example.janus.client.track.RemoteAudioTrack
import com.example.janus.client.track.RemoteVideoTrack
import com.example.janus.client.track.Track
import com.example.janus.client.webrtc.RemoteCandidateBuffer
import com.example.janus.client.webrtc.SdpUtils.audioLevelOrNull
import com.example.janus.client.webrtc.SdpUtils.createAnswerSuspend
import com.example.janus.client.webrtc.SdpUtils.getStatsSuspend
import com.example.janus.client.webrtc.SdpUtils.setLocalDescriptionSuspend
import com.example.janus.client.webrtc.SdpUtils.setRemoteDescriptionSuspend
import com.example.janus.client.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Callbacks for remote subscription track events.
 */
interface RemoteSubscriptionListener {
    fun onRemoteTrackSubscribed(feedId: BigInteger, display: String, track: Track)
    fun onRemoteTrackUnsubscribed(feedId: BigInteger, track: Track?)
}

/**
 * Manages a SINGLE subscriber handle for ALL remote VideoRoom feeds.
 *
 * Architecture mirrors the official Janus `videoroomtest.js` reference:
 *
 *  - ONE plugin handle (`remoteFeed`) subscribes to ALL publishers simultaneously
 *    via a single `join { ptype:"subscriber", streams:[{feed,mid},...] }` request.
 *  - When new publishers join, an `update { subscribe:[...] }` request adds their
 *    streams to the SAME handle — no new attachment required.
 *  - Mid → feedId mapping is built from the `streams[]` array in `"attached"` /
 *    `"updated"` events (equivalent to the JS `subStreams` map).
 *  - When all feeds leave, the handle is detached cleanly.
 */
class RemoteSubscriptionManager(
    private val scope: CoroutineScope,
    private val signalingClient: JanusSignalingClient,
    private val webRtcEngine: WebRtcEngine,
    private val listener: RemoteSubscriptionListener
) {
    private val TAG = "RemoteSubscriptionManager"

    // ── Single subscriber handle & PeerConnection (like remoteFeed in JS) ───
    @Volatile private var subscriberHandleId: BigInteger? = null
    @Volatile private var subscriberPc: PeerConnection? = null

    // Cached session/room/privateId for deferred re-use
    @Volatile private var activeSessionId: BigInteger? = null
    @Volatile private var activeRoomId: Int = 0
    @Volatile private var activePrivateId: Long? = null

    /** Candidates Janus trickles for the subscriber handle (see [addRemoteCandidate]). */
    private val subscriberCandidates = RemoteCandidateBuffer(TAG)

    // ── State maps ───────────────────────────────────────────────────────────

    /**
     * Subscriber-side mid → publisher stream info.
     * Equivalent to `subStreams` in videoroomtest.js.
     * Populated from the `streams[]` array in "attached" / "updated" events.
     */
    private val subStreams = ConcurrentHashMap<String, SubStreamInfo>()

    /** feedId → displayName for all currently subscribed feeds. */
    private val subscribedFeeds = ConcurrentHashMap<BigInteger, String>()

    /** Mids whose tracks have already been delivered to the listener. Prevents duplicates. */
    private val deliveredMids = ConcurrentHashMap<String, Boolean>()

    // ── Handle-creation guard (like creatingSubscription flag in JS) ─────────
    private val isCreatingHandle = AtomicBoolean(false)
    private val pendingSources = Collections.synchronizedList(mutableListOf<PublisherFeedInfo>())

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subscribe to one or more publisher feeds.
     *
     *  - First call: attaches a new plugin handle and joins with all feeds at once.
     *  - Subsequent calls: sends an `"update"` request on the existing handle
     *    (no new attachment, matching videoroomtest.js behavior).
     *
     * Safe to call from any thread.
     */
    fun subscribeTo(
        sessionId: BigInteger,
        roomId: Int,
        privateId: Long?,
        feeds: List<PublisherFeedInfo>
    ) {
        if (feeds.isEmpty()) return

        activeSessionId = sessionId
        activeRoomId    = roomId
        activePrivateId = privateId

        // Skip feeds already subscribed
        val newFeeds = feeds.filter { !subscribedFeeds.containsKey(it.feedId) }
        if (newFeeds.isEmpty()) {
            SDKLogger.debug(TAG, "All feeds already subscribed, nothing to do")
            return
        }

        // If handle creation is in progress, queue for later (JS: setTimeout retry)
        if (!isCreatingHandle.compareAndSet(false, true)) {
            SDKLogger.debug(TAG, "Handle creation in progress — queuing ${newFeeds.size} feed(s)")
            pendingSources.addAll(newFeeds)
            return
        }

        val existingHandle = subscriberHandleId
        if (existingHandle != null) {
            // Handle already exists → send "update" to add new feeds (no new attach)
            isCreatingHandle.set(false)
            sendUpdateSubscription(sessionId, existingHandle, newFeeds)
        } else {
            // No handle yet → create one with all feeds in a single join
            scope.launch(Dispatchers.IO) {
                try {
                    createSubscriberHandle(sessionId, roomId, privateId, newFeeds)
                } finally {
                    isCreatingHandle.set(false)
                    flushPendingSources()
                }
            }
        }
    }

    /**
     * Unsubscribes from a specific publisher feed.
     *
     * Sends `{ request:"unsubscribe", streams:[{feed:feedId}] }` to the single handle.
     * The handle is only detached when ALL feeds have unsubscribed.
     * Matches `unsubscribeFrom(id)` in videoroomtest.js.
     */
    fun unsubscribeFrom(sessionId: BigInteger, feedId: BigInteger) {
        val display = subscribedFeeds.remove(feedId) ?: return
        val handleId = subscriberHandleId

        // Remove mid mappings for this feed
        val midsForFeed = subStreams.entries
            .filter { it.value.feedId == feedId }
            .map { it.key }
        midsForFeed.forEach { mid ->
            subStreams.remove(mid)
            deliveredMids.remove(mid)
        }

        SDKLogger.info(TAG, "Unsubscribing feed $feedId ($display), removing mids: $midsForFeed")

        // Notify UI immediately
        listener.onRemoteTrackUnsubscribed(feedId, null)

        // Detach handle when last feed is gone - no point renegotiating an empty subscription.
        if (subscribedFeeds.isEmpty()) {
            SDKLogger.info(TAG, "No feeds remaining — detaching subscriber handle")
            cleanupHandle(sessionId)
            return
        }

        if (handleId != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    // app: unsubscribeStream → {request:"unsubscribe", streams:[{feed}]}
                    val body = mapOf<String, Any>(
                        "request" to "unsubscribe",
                        "streams" to listOf(mapOf("feed" to feedId.toLong()))
                    )
                    val response = signalingClient.sendMessage(sessionId, handleId, body)
                    SDKLogger.info(TAG, "Sent unsubscribe for feed $feedId")
                    // Janus answers with "updated" + a new offer that must be answered, or the
                    // subscription stalls.
                    updateSubStreamsFromData(response.pluginDataMap)
                    response.jsep?.let { renegotiateSubscriberPc(sessionId, handleId, it) }
                } catch (e: Exception) {
                    SDKLogger.warn(TAG, "Error sending unsubscribe for feed $feedId: ${e.message}")
                }
            }
        }
    }

    /** A candidate Janus trickled for the subscriber handle. */
    fun addRemoteCandidate(senderHandleId: BigInteger, candidate: Map<String, Any>?) {
        if (senderHandleId != subscriberHandleId) return
        subscriberCandidates.add(subscriberPc, candidate)
    }

    /** `webrtcup` / `media` / `slowlink` / `hangup` / `detached` on the subscriber handle. */
    fun handleHandleEvent(type: String, senderHandleId: BigInteger, raw: Map<String, Any>) {
        if (senderHandleId != subscriberHandleId) return
        when (type) {
            "webrtcup" -> SDKLogger.info(TAG, "Subscriber PeerConnection is up")
            "media" -> SDKLogger.debug(TAG, "Subscriber media ${raw["type"]} receiving=${raw["receiving"]} mid=${raw["mid"]}")
            "slowlink" -> SDKLogger.warn(TAG, "Subscriber slowlink: $raw")
            "hangup" -> SDKLogger.warn(TAG, "Subscriber hung up: ${raw["reason"]}")
            "detached" -> {
                SDKLogger.warn(TAG, "Subscriber handle detached by Janus")
                activeSessionId?.let { unsubscribeAll(it) }
            }
        }
    }

    /**
     * Incoming audio level (0..1) per subscribed feed, from the subscriber PeerConnection's
     * `inbound-rtp` audio stats (app's JanusService.checkAudioLevels, incoming side).
     */
    suspend fun remoteAudioLevels(): Map<BigInteger, Float> {
        val pc = subscriberPc ?: return emptyMap()
        val report = try { pc.getStatsSuspend() } catch (e: Exception) { return emptyMap() }

        // Older WebRTC builds don't put "mid" on inbound-rtp - fall back to the receiving
        // track's id → transceiver mid.
        val midByTrackId: Map<String, String> by lazy {
            try {
                pc.transceivers.mapNotNull { t ->
                    val id = t.receiver?.track()?.id() ?: return@mapNotNull null
                    val mid = t.mid ?: return@mapNotNull null
                    id to mid
                }.toMap()
            } catch (e: Exception) { emptyMap() }
        }

        val levels = mutableMapOf<BigInteger, Float>()
        report.statsMap.values
            .filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
            .forEach { stat ->
                val level = stat.audioLevelOrNull() ?: return@forEach
                val mid = stat.members["mid"] as? String
                    ?: (stat.members["trackIdentifier"] as? String)?.let { midByTrackId[it] }
                    ?: return@forEach
                val feedId = subStreams[mid]?.feedId ?: return@forEach
                levels[feedId] = maxOf(level, levels[feedId] ?: 0f)
            }
        return levels
    }

    /**
     * Handles unsolicited events pushed by Janus on the subscriber handle.
     *
     *  - `"updated"`: renegotiation triggered by an `"update"` request (new publisher added).
     *    Parses the new `streams[]` mapping and re-runs the offer→answer cycle.
     */
    fun handleUnsolicitedEvent(
        sessionId: BigInteger,
        senderHandleId: BigInteger,
        eventMap: Map<String, Any>,
        jsep: Map<String, Any>?
    ) {
        if (senderHandleId != subscriberHandleId) return

        val data = eventMap["data"] as? Map<*, *>
        val videoroom = data?.get("videoroom") as? String

        SDKLogger.debug(TAG, "Subscriber handle unsolicited event: videoroom=$videoroom")

        // Always refresh mid→feedId mappings if streams[] is present
        if (data != null) {
            @Suppress("UNCHECKED_CAST")
            updateSubStreamsFromData(data as Map<String, Any>)
        }

        when (videoroom) {
            "updated" -> {
                // Renegotiation: Janus sent a new SDP offer after an "update" request.
                // This path handles cases where the offer arrives as an unsolicited event
                // rather than as the direct transaction response.
                if (jsep != null) {
                    scope.launch(Dispatchers.IO) {
                        renegotiateSubscriberPc(sessionId, senderHandleId, jsep)
                    }
                }
            }
        }
    }

    /**
     * Unsubscribes from all feeds and detaches the single subscriber handle.
     * Called on room disconnect.
     */
    fun unsubscribeAll(sessionId: BigInteger) {
        val feeds = subscribedFeeds.keys.toList()
        feeds.forEach { feedId ->
            subscribedFeeds.remove(feedId)
            listener.onRemoteTrackUnsubscribed(feedId, null)
        }
        subStreams.clear()
        deliveredMids.clear()
        pendingSources.clear()
        cleanupHandle(sessionId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Handle Creation (first subscriber join)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun createSubscriberHandle(
        sessionId: BigInteger,
        roomId: Int,
        privateId: Long?,
        feeds: List<PublisherFeedInfo>
    ) {
        SDKLogger.info(TAG, "Attaching subscriber handle for ${feeds.size} feed(s): ${feeds.map { it.feedId }}")

        val handleId = signalingClient.attachPlugin(sessionId, "janus.plugin.videoroom")
        subscriberHandleId = handleId

        // Mark feeds as subscribed optimistically (rolled back on failure)
        feeds.forEach { subscribedFeeds[it.feedId] = it.display }

        val streamsArray = buildStreamsArray(feeds)
        val joinBody = mutableMapOf<String, Any>(
            "request" to "join",
            "ptype"   to "subscriber",
            "room"    to roomId,
            "streams" to streamsArray,
            // app's VideoRoomManager.subscribe sends use_msid:false
            "use_msid" to false
        )
        // private_id links us to the publisher handle (required on most Janus rooms)
        privateId?.let { joinBody["private_id"] = it }

        SDKLogger.debug(TAG, "Sending subscriber join with streams: $streamsArray")

        val joinResponse = signalingClient.sendMessage(sessionId, handleId, joinBody)
        val vrEvent = joinResponse.pluginDataMap["videoroom"] as? String

        if (vrEvent != "attached") {
            val errCode = joinResponse.pluginDataMap["error_code"]
            val errMsg  = joinResponse.pluginDataMap["error"]
            SDKLogger.warn(TAG, "Subscriber join rejected — code=$errCode msg='$errMsg'")
            // Rollback
            feeds.forEach { subscribedFeeds.remove(it.feedId) }
            try { signalingClient.detachPlugin(sessionId, handleId) } catch (_: Exception) {}
            subscriberHandleId = null
            return
        }

        // Build mid→feedId map from the "attached" response's streams[]
        updateSubStreamsFromData(joinResponse.pluginDataMap)

        val jsep = joinResponse.jsep
        if (jsep != null) {
            setupSubscriberPeerConnection(sessionId, handleId, jsep)
        } else {
            SDKLogger.warn(TAG, "No JSEP in 'attached' response — will handle renegotiation via event")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Update (add new feeds to existing handle)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendUpdateSubscription(
        sessionId: BigInteger,
        handleId: BigInteger,
        feeds: List<PublisherFeedInfo>
    ) {
        feeds.forEach { subscribedFeeds[it.feedId] = it.display }

        val subscribeArray = buildStreamsArray(feeds)
        val updateBody = mapOf<String, Any>(
            "request"   to "update",
            "subscribe" to subscribeArray
        )

        SDKLogger.info(TAG, "Sending update/subscribe for ${feeds.size} feed(s): ${feeds.map { it.feedId }}")

        scope.launch(Dispatchers.IO) {
            try {
                val response = signalingClient.sendMessage(sessionId, handleId, updateBody)

                // Refresh subStreams from the transaction response (may contain streams[])
                updateSubStreamsFromData(response.pluginDataMap)

                // If Janus returned a renegotiation offer as the direct transaction response
                val jsep = response.jsep
                if (jsep != null) {
                    val vrEvent = response.pluginDataMap["videoroom"] as? String
                    SDKLogger.info(TAG, "update response contains JSEP (vrEvent=$vrEvent), renegotiating")
                    renegotiateSubscriberPc(sessionId, handleId, jsep)
                }
            } catch (e: Exception) {
                SDKLogger.error(TAG, "Failed to send update subscription: ${e.message}", e)
                feeds.forEach { subscribedFeeds.remove(it.feedId) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – PeerConnection Setup
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun setupSubscriberPeerConnection(
        sessionId: BigInteger,
        handleId: BigInteger,
        jsep: Map<String, Any>
    ) {
        val offerSdp = jsep["sdp"] as? String ?: run {
            SDKLogger.error(TAG, "No SDP in subscriber JSEP offer")
            return
        }

        val observer = buildPcObserver(sessionId, handleId)
        val pc = webRtcEngine.createPeerConnection(observer) ?: run {
            SDKLogger.error(TAG, "Failed to create subscriber PeerConnection")
            return
        }
        subscriberPc = pc

        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        subscriberCandidates.flush(pc)
        val answer = pc.createAnswerSuspend(recvOnlyConstraints())
        pc.setLocalDescriptionSuspend(answer)

        val startBody  = mapOf<String, Any>("request" to "start", "room" to activeRoomId)
        val answerJsep = mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description)

        val started = signalingClient.sendMessage(sessionId, handleId, startBody, answerJsep)
        if (started.pluginDataMap["started"] != "ok") {
            SDKLogger.warn(TAG, "Subscriber start not ok: ${started.rawJson}")
        }
        SDKLogger.info(TAG, "Subscriber PC started for ${subscribedFeeds.size} feed(s)")
    }

    private suspend fun renegotiateSubscriberPc(
        sessionId: BigInteger,
        handleId: BigInteger,
        jsep: Map<String, Any>
    ) {
        val pc = subscriberPc ?: run {
            SDKLogger.warn(TAG, "renegotiateSubscriberPc: no PeerConnection")
            return
        }
        val offerSdp = jsep["sdp"] as? String ?: return

        try {
            pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            subscriberCandidates.flush(pc)
            val answer = pc.createAnswerSuspend(recvOnlyConstraints())
            pc.setLocalDescriptionSuspend(answer)

            val startBody  = mapOf<String, Any>("request" to "start", "room" to activeRoomId)
            val answerJsep = mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description)

            signalingClient.sendMessage(sessionId, handleId, startBody, answerJsep)
            SDKLogger.info(TAG, "Subscriber PC renegotiated successfully")
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Subscriber PC renegotiation failed: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – PeerConnection.Observer
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPcObserver(
        sessionId: BigInteger,
        handleId: BigInteger
    ): PeerConnection.Observer = object : PeerConnection.Observer {

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            SDKLogger.debug(TAG, "Subscriber ICE connection: $s")
        }

        override fun onIceConnectionReceivingChange(r: Boolean) {}

        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) {
                scope.launch(Dispatchers.IO) {
                    signalingClient.sendTrickleCandidate(sessionId, handleId, null)
                }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch(Dispatchers.IO) {
                signalingClient.sendTrickleCandidate(
                    sessionId, handleId,
                    mapOf(
                        "candidate"     to candidate.sdp,
                        "sdpMid"        to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    )
                )
            }
        }

        override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {
            subscriberPc?.removeIceCandidates(c)
        }
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}

        // Legacy path — intentionally empty; onTrack below is the primary path
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}

        /**
         * Primary track delivery (Unified Plan).
         *
         * Maps [transceiver.mid] → [SubStreamInfo] via [subStreams] to identify
         * the originating publisher — equivalent to the JS onremotetrack callback:
         *
         *   let sub  = subStreams[mid];
         *   let feed = feedStreams[sub.feed_id];
         */
        override fun onTrack(transceiver: RtpTransceiver) {
            val mid   = transceiver.mid ?: return
            val track = transceiver.receiver?.track() ?: return

            // Guard: deliver each mid only once
            if (deliveredMids.putIfAbsent(mid, true) != null) return

            SDKLogger.debug(TAG, "onTrack mid=$mid kind=${track.kind()}")

            if (subStreams.containsKey(mid)) {
                deliverTrack(mid, track)
            } else {
                // subStreams may not be populated yet if "attached" parsing is still in flight.
                // Retry after a short delay (150 ms is well within ICE setup time).
                SDKLogger.warn(TAG, "subStreams not yet ready for mid=$mid — retrying in 150 ms")
                scope.launch(Dispatchers.IO) {
                    delay(150)
                    deliverTrack(mid, track)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Track Delivery
    // ─────────────────────────────────────────────────────────────────────────

    private fun deliverTrack(mid: String, track: org.webrtc.MediaStreamTrack) {
        val info = subStreams[mid] ?: run {
            SDKLogger.warn(TAG, "deliverTrack: still no subStreamInfo for mid=$mid — dropping")
            // Let a later onTrack for this mid (after the next "updated") deliver it.
            deliveredMids.remove(mid)
            return
        }
        SDKLogger.info(TAG, "Delivering ${info.type} track mid=$mid → feed=${info.feedId} (${info.feedDisplay})")

        when (track) {
            is RtcVideoTrack -> listener.onRemoteTrackSubscribed(
                info.feedId, info.feedDisplay, RemoteVideoTrack(track)
            )
            is RtcAudioTrack -> listener.onRemoteTrackSubscribed(
                info.feedId, info.feedDisplay, RemoteAudioTrack(track)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the `streams` array for a subscriber join / update body.
     *
     *  - If a feed carries explicit [PublisherStreamInfo] entries, emit one
     *    `{feed, mid}` per stream (selective subscription).
     *  - Otherwise emit `{feed: feedId}` and let Janus subscribe to all streams.
     */
    private fun buildStreamsArray(feeds: List<PublisherFeedInfo>): List<Map<String, Any>> =
        feeds.flatMap { feed ->
            if (feed.streams.isNotEmpty()) {
                feed.streams.map { stream ->
                    mapOf("feed" to feed.feedId.toLong(), "mid" to stream.mid)
                }
            } else {
                listOf(mapOf("feed" to feed.feedId.toLong()))
            }
        }

    /**
     * Parses the `streams[]` array from an "attached" or "updated" Janus event and
     * populates [subStreams] with mid → [SubStreamInfo] mappings.
     *
     * JS equivalent:
     * ```js
     * for(let i in msg["streams"]) {
     *     let mid = msg["streams"][i]["mid"];
     *     subStreams[mid] = msg["streams"][i];
     * }
     * ```
     */
    private fun updateSubStreamsFromData(data: Map<String, Any>) {
        val streamsList = data["streams"] as? List<*> ?: return
        streamsList.forEach { item ->
            if (item is Map<*, *>) {
                val mid     = item["mid"]?.toString() ?: return@forEach
                // Inactive m-line (feed gone) - Janus may reuse this mid for another feed later.
                if (item["active"] == false) {
                    subStreams.remove(mid)
                    deliveredMids.remove(mid)
                    return@forEach
                }
                val feedId  = item["feed_id"].toJanusId() ?: return@forEach
                val display = item["feed_display"] as? String ?: subscribedFeeds[feedId] ?: ""
                val type    = item["type"] as? String ?: "unknown"
                val codec   = item["codec"] as? String
                subStreams[mid] = SubStreamInfo(mid, feedId, display, type, codec)
                SDKLogger.debug(TAG, "subStreams updated: mid=$mid → feedId=$feedId ($type)")
            }
        }
    }

    private fun recvOnlyConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    /** Drains [pendingSources] queued while the handle was being created. */
    private fun flushPendingSources() {
        val pending = synchronized(pendingSources) {
            pendingSources.toList().also { pendingSources.clear() }
        }
        if (pending.isEmpty()) return
        SDKLogger.debug(TAG, "Flushing ${pending.size} queued feed(s) after handle creation")
        val sId = activeSessionId ?: return
        subscribeTo(sId, activeRoomId, activePrivateId, pending)
    }

    private fun cleanupHandle(sessionId: BigInteger) {
        val handleId = subscriberHandleId ?: return
        subscriberHandleId = null
        subscriberCandidates.clear()

        scope.launch(Dispatchers.IO) {
            try {
                signalingClient.detachPlugin(sessionId, handleId)
                SDKLogger.info(TAG, "Subscriber handle $handleId detached")
            } catch (e: Exception) {
                SDKLogger.warn(TAG, "Error detaching handle: ${e.message}")
            }
        }

        try { subscriberPc?.dispose() } catch (_: Exception) {}
        subscriberPc = null
    }
}
