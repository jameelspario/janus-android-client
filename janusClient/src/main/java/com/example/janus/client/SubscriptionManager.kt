package com.example.janus.client

import org.json.JSONArray
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.get

/**
 * Manages subscription to remote streams (feeds/publishers).
 *
 * Subscribe triggers:
 *   • "event" + publishers list → auto-subscribe each feed
 *
 * Unsubscribe triggers:
 *   A  "event" + unpublished:<feedId>  → publisher stopped publishing
 *   B  "event" + leaving:<feedId>      → participant left room
 *   C  "event" + destroyed             → host destroyed room
 *   D  leaveRoom()    called locally   → unsubscribeAllInRoom()
 *   E  disconnect() / release()        → unsubscribeAll()
 *   F  negotiation failure             → cleanupEntry()
 *   G  onRemoveStream (network drop)   → unsubscribe()
 */
class SubscriptionManager(
    private val signaling: SignalingManager,
    private val webRtc: WebRTCManager,
    private val sdkListener: JanusSDKEventListener?
) {

    // Is this subscription feature active?
    @Volatile
    var enabled: Boolean = true

    // roomId → set of subscribed stream/feed ids
    private val subscriptionsByRoom = ConcurrentHashMap<Int, MutableSet<BigInteger>>()

    // streamId (feed) → subscription details
    private val activeSubscriptions = ConcurrentHashMap<BigInteger, SubscriptionEntry>()
    // feedId → renderer registered by the UI (may arrive before track does)
    private val renderers = ConcurrentHashMap<BigInteger, SurfaceViewRenderer>()

    private val pendingSubscriptions = ConcurrentHashMap.newKeySet<BigInteger>()

    data class SubscriptionEntry(
        val roomId: Int,
        val feedId: BigInteger,                    // Janus feed / publisher id
        val display:String,
        val subscriberHandleId: BigInteger?,   // plugin handle for this subscription
        var peerConnection: PeerConnection?,
        var videoTrack: VideoTrack? = null,
        var audioTrack: AudioTrack? = null,
    )

    // ------------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------------

    fun subscribe(roomId: Int, display: String, feedId: BigInteger) {
        if (!enabled) {
            consoleLogE("SubscriptionManager", "Subscriptions are disabled")
            return
        }

        if (activeSubscriptions.containsKey(feedId)) {
            consoleLogE("SubscriptionManager", "Already subscribed to feed $feedId")
            return
        }

        if (!pendingSubscriptions.add(feedId)) {
            consoleLogE("SubMgr", "subscribe: attach already in-flight for feed=$feedId")
            return
        }

        // Track per-room
        subscriptionsByRoom.computeIfAbsent(roomId) { mutableSetOf() }.add(feedId)

        // 1. Attach new videoroom plugin handle (subscriber)
        signaling.attachPlugin(JanusPlugin.VIDEOROOM.pluginName) { handleId ->
            if (handleId == null) {
                pendingSubscriptions.remove(feedId)
                subscriptionsByRoom[roomId]?.remove(feedId)
                sdkListener?.onError("Failed to attach subscriber plugin for $feedId")
                return@attachPlugin
            }

            val entry = SubscriptionEntry(
                roomId = roomId,
                feedId = feedId,
                display = display,
                subscriberHandleId = handleId,
                peerConnection = null
            )

            activeSubscriptions[feedId] = entry

            val body = mapOf<String, Any>(
                "ptype" to "subscriber",
                "request" to "join",
                "room" to roomId,
                "feed" to feedId.toLong()
            )
            signaling.sendMessage(body = body, null, handleId = handleId){ response, jsep ->
                //attached response
                pendingSubscriptions.remove(feedId)

                val videoroom = response["videoroom"] as? String
                consoleLogE("SubMgr", "join response feed=$feedId videoroom=$videoroom")

                if(videoroom=="attached" && jsep!=null){
                    val confirmedFeed    = (response["id"]    as? Number)?.toLong()?.toBigInteger() ?: feedId
                    val confirmedRoom    = (response["room"]  as? Number)?.toInt() ?: roomId
                    val confirmedDisplay = (response["display"] as? String) ?: display
                    val streams = response["streams"] as? JSONArray
                    consoleLogE(
                        "subscriber attached",
                        "room=$confirmedRoom, feed=$confirmedFeed, display=$confirmedDisplay"
                    )

                    negotiatePeerConnection(
                        response,
                        jsep,
                        confirmedFeed,
                        confirmedRoom,
                        confirmedDisplay,
                        handleId,
                        entry
                    )

                } else {
                    consoleLogE("SubMgr", "join rejected feed=$feedId – cleaning up. response=$response")
                    cleanupEntry(feedId, sendLeave = false)
                }

            }

        }
    }

    private fun negotiatePeerConnection(
        response : Map<String, Any>,
        jsep     : Map<String, Any>,
        feedId   : BigInteger,
        roomId   : Int,
        display  : String,
        handleId : BigInteger,
        entry    : SubscriptionEntry
    ) {

        val offerSdp = jsep["sdp"] as? String ?: run {
            consoleLogE("SubMgr", "negotiatePeerConnection: no SDP in jsep for feed=$feedId")
            cleanupEntry(feedId, sendLeave = true)
            return
        }

        val iceServers  = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val observer = object : SimplePeerConnectionObserver() {

            // Legacy fallback
            override fun onAddStream(stream: MediaStream) {
                val videoTrack = stream.videoTracks.firstOrNull()
                val audioTrack = stream.audioTracks.firstOrNull()
                deliverTrack(
                    feedId, display,
                    videoTrack,
                    audioTrack
                )
            }

            // Trigger H – network drop or remote side gone
            override fun onRemoveStream(stream: MediaStream) {
                consoleLogE("SubMgr", "onRemoveStream: feed=$feedId")
                unsubscribe(feedId)
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendTrickleIceCandidate(
                    candidate.sdp, candidate.sdpMLineIndex, candidate.sdpMid,
                    handle = handleId
                )
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    signaling.sendTrickleCandidateComplete("", handle = handleId)
                }
            }
        }

        val pc = webRtc.createPeerConnection(iceServers, observer) ?: run {
            consoleLogE("SubMgr", "createPeerConnection returned null for feed=$feedId")
            cleanupEntry(feedId, sendLeave = true)
            return
        }

        // Persist the PC so cleanupEntry/unsubscribe can dispose it
        activeSubscriptions[feedId] = activeSubscriptions[feedId]!!.copy(peerConnection = pc)

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        // Step 3 – setRemoteDescription (Janus offer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // Step 4 – createAnswer
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        // Step 5 – setLocalDescription
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                // Step 6 – send "start" with our answer to Janus
                                val startBody = mapOf<String, Any>(
                                    "request" to "start",
                                    "room"    to roomId
                                )
                                val answerJsep = mapOf(
                                    "type" to sdp.type.canonicalForm(),
                                    "sdp"  to sdp.description
                                )
                                signaling.sendMessage(
                                    body = startBody, jsep = answerJsep, handleId = handleId
                                ) { startResp, _ ->
                                    consoleLogE("SubMgr", "start resp feed=$feedId: $startResp")
                                    // Tracks arrive via onTrack/onAddStream once Janus starts forwarding
                                    val videoroom = response["videoroom"] as? String
                                    if (videoroom=="event" && response["started"] == "ok"){
                                        // stream started

                                    }
                                }
                            }
                            override fun onSetFailure(error: String) {
                                consoleLogE("SubMgr", "setLocalDesc failed feed=$feedId: $error")
                                cleanupEntry(feedId, sendLeave = true)  // Trigger G
                            }
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String) {
                        consoleLogE("SubMgr", "createAnswer failed feed=$feedId: $error")
                        cleanupEntry(feedId, sendLeave = true)  // Trigger G
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(p0: String?) {
                consoleLogE("SubMgr", "setRemoteDesc failed feed=$feedId: $p0")
                cleanupEntry(feedId, sendLeave = true)  // Trigger G
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteOffer)
    }

    /**
     * Clean up a failed / mid-flight subscription.
     *
     * @param sendLeave true  → the plugin handle exists on Janus; send "unsubscribe" + detach
     *                  false → Janus never created the handle (attach failed); skip signaling
     */
    private fun cleanupEntry(feedId: BigInteger, sendLeave: Boolean) {
        val entry = activeSubscriptions.remove(feedId) ?: return
        subscriptionsByRoom[entry.roomId]?.remove(feedId)
        if (subscriptionsByRoom[entry.roomId]?.isEmpty() == true) {
            subscriptionsByRoom.remove(entry.roomId)
        }

        if (sendLeave) {
            entry.subscriberHandleId?.let { handleId ->
                signaling.sendMessage(
                    body = mapOf("request" to "unsubscribe"),
                    jsep = null, handleId = handleId
                ) { _, _ -> signaling.detachPlugin(handleId) }
            }
        }

        releaseWebRtcResources(entry, feedId)
        sdkListener?.onRemoteStreamRemoved(feedId)
    }

    /** Dispose the PeerConnection, both tracks, and any attached renderer. */
    private fun releaseWebRtcResources(entry: SubscriptionEntry, feedId: BigInteger){
        try {
            renderers.remove(feedId)?.let { renderer ->
                entry.videoTrack?.removeSink(renderer)
                renderer.clearImage()
            }
//            entry.videoTrack?.dispose()
//            entry.audioTrack?.dispose()
            // dispose() closes ICE + DTLS and releases all native resources
            entry.peerConnection?.dispose()
        } catch (e: Exception) {
            consoleLogE("SubMgr", "releaseWebRtcResources error feed=$feedId: ${e.message}")
        }
    }

    private fun deliverTrack(feedId: BigInteger,
                             display: String?,
                             videoTrack: VideoTrack?,
                             audioTrack: AudioTrack?) {
        val current = activeSubscriptions[feedId] ?: return
        // Merge – only update a track type if it hasn't arrived yet
        val mergedVideo = if (videoTrack != null && current.videoTrack == null) videoTrack else current.videoTrack
        val mergedAudio = if (audioTrack != null && current.audioTrack == null) audioTrack else current.audioTrack

        activeSubscriptions[feedId] = current.copy(videoTrack = mergedVideo, audioTrack = mergedAudio)

        // Attach any pre-registered renderer
        if (videoTrack != null && current.videoTrack == null) {
            renderers[feedId]?.let { videoTrack.addSink(it) }
            // Notify SDK → UI can now call sdk.showRemoteStream(feedId, renderer)
            sdkListener?.onRemoteStreamAvailable(feedId, display, videoTrack)
        }
    }

    private fun cleanupFailedSubscription(feedId: BigInteger) {
        activeSubscriptions.remove(feedId)?.let { entry ->
            subscriptionsByRoom[entry.roomId]?.remove(feedId)
            entry.subscriberHandleId?.let { signaling.detachPlugin(it) }
        }
    }

    /**
     * Attach a SurfaceViewRenderer to a feed.
     * Safe to call before or after the VideoTrack arrives – either way it will render.
     */
    fun attachRenderer(feedId: BigInteger, renderer: SurfaceViewRenderer) {
        try {
            renderer.init(webRtc.eglBaseContext, null)
        } catch (_: Exception) { /* already initialised */ }
        renderer.setMirror(false)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderers[feedId] = renderer
        activeSubscriptions[feedId]?.videoTrack?.addSink(renderer)
    }

    /** Remove the renderer for a feed (call when the View is being destroyed). */
    fun detachRenderer(feedId: BigInteger) {
        val renderer = renderers.remove(feedId) ?: return
        activeSubscriptions[feedId]?.videoTrack?.removeSink(renderer)
    }

    fun unsubscribe(feedId: BigInteger) {
        val entry = activeSubscriptions.remove(feedId)
        pendingSubscriptions.remove(feedId)

        if (entry == null) {
            consoleLogE("SubMgr", "unsubscribe: no entry for feed=$feedId (already removed)")
            return
        }

        // 1. Room index cleanup
        subscriptionsByRoom[entry.roomId]?.remove(feedId)
        if (subscriptionsByRoom[entry.roomId]?.isEmpty() == true) {
            subscriptionsByRoom.remove(entry.roomId)
        }

        // 2 & 3. Janus signaling teardown (async – does not block WebRTC cleanup)
        entry.subscriberHandleId?.let { handleId ->
            signaling.sendMessage(
                body    = mapOf("request" to "unsubscribe"),
                jsep    = null,
                handleId = handleId
            ) { resp, _ ->
                consoleLogE("SubMgr", "unsubscribe ack feed=$feedId resp=$resp")
                signaling.detachPlugin(handleId) { ok ->
                    consoleLogE("SubMgr", "detach feed=$feedId ok=$ok")
                }
            }
        }

        // 4. WebRTC teardown – immediate, no need to wait for Janus ACK
        releaseWebRtcResources(entry, feedId)

        // 5. Notify UI
        sdkListener?.onRemoteStreamRemoved(entry.feedId)
        consoleLogE("SubscriptionManager", "Unsubscribed from feed $feedId")

    }

    /**
     * Unsubscribe every feed in a room.
     * Call when:
     *   – Local user presses "Leave Room"   (from JanusSDK.leaveRoom)
     *   – Host's "destroyed" event fires    (from handleSignalingMessage)
     */
    fun unsubscribeAllInRoom(roomId: Int) {
        val feeds = subscriptionsByRoom[roomId]?.toList()
        if (feeds.isNullOrEmpty()) {
            consoleLogE("SubMgr", "unsubscribeAllInRoom: nothing to do for room=$roomId")
            return
        }
        consoleLogE("SubMgr", "unsubscribeAllInRoom: removing ${feeds.size} feed(s) from room=$roomId")
        feeds.forEach { unsubscribe(it) }
    }

    /**
     * Unsubscribe from ALL active feeds across every room.
     * Call from JanusSDK.disconnect() or Activity.onDestroy().
     */
    fun unsubscribeAll() {
        val feeds = activeSubscriptions.keys.toList()
        consoleLogE("SubMgr", "unsubscribeAll: tearing down ${feeds.size} feed(s)")
        feeds.forEach { unsubscribe(it) }
    }

    /**
     * Completely disable subscriptions → unsubscribe everything
     */
    fun disable() {
        enabled = false
        unsubscribeAll()
    }

    fun enable() {
        enabled = true
    }

    fun handleSignalingMessage(message: Map<String, Any>) {
        consoleLogE("-- sub", message.toString())

        try {
            // 1. Safely get plugindata → data
            val data = message["data"] as? Map<*, *> ?: return
            val videoroom = data["videoroom"] as? String ?: return
            val roomId = (data["room"] as? Number)?.toInt()
            consoleLogE("-- sub", videoroom)

            when (videoroom) {
                "event" -> {
                    roomId ?: return

                    // ───────────────────────────────────────────────
                    // Handle new publishers list
                    // ───────────────────────────────────────────────
                    println("---- DATA MAP KEYS ----")
                    data.forEach { key, value ->
                        println("KEY=$key  TYPE=${value?.javaClass?.name}  VALUE=$value")
                    }
                    println("------------------------")
                    println(data["publishers"]?.javaClass?.name)
                    val publishersJson = data["publishers"] as? JSONArray

                    consoleLogE("publishers count = ${publishersJson?.length()}")
                    consoleLogE("publishers = $publishersJson")

                    if(publishersJson!=null) {

                        for (i in 0 until (publishersJson.length())) {
                            val pub = publishersJson.getJSONObject(i) ?: continue

                            pub.let {
                                val feedId = pub.optLong("id").toBigInteger()
                                val display = pub.optString("display")
                                val audioCodec = pub.optString("audio_codec")
                                val videoCodec = pub.optString("video_codec")

                                consoleLogE(
                                    "New publisher",
                                    "feed=$feedId, display=$display, audio=$audioCodec, video=$videoCodec"
                                )

                                // ───────────────────────────────────────────────
                                // Decide whether to subscribe
                                // ───────────────────────────────────────────────
                                subscribe(roomId = roomId, display = display, feedId = feedId)

                            }
                        }
                    }


                    /*
                    sub | {plugin=janus.plugin.videoroom, data={videoroom=event, room=1234, display=asdf, unpublished=7468658616706119}}

                     */
                    // ── Trigger A: publisher stopped publishing ─────────────────────
                    // Janus sends {"videoroom":"event","unpublished":<feedId>}
                    // NOT a top-level "videoroom":"unpublished" — that branch was dead code
                    val unpublishedRaw = data["unpublished"]
                    if (unpublishedRaw != null && unpublishedRaw != "ok") {
                        val feedId = (unpublishedRaw as? Number)?.toLong()?.toBigInteger()
                        if (feedId != null) {
                            consoleLogE("SubMgr", "unpublished feed=$feedId room=$roomId")
                            unsubscribe(feedId)
                        }
                    }

                    // ── Trigger B: participant left the room entirely ───────────────
                    // Same envelope: {"videoroom":"event","leaving":<feedId>}
                    // {plugin=janus.plugin.videoroom, data={videoroom=event, room=1234, display=asdf, leaving=7468658616706119}}
                    val leavingRaw = data["leaving"]
                    if (leavingRaw != null && leavingRaw != "ok") {
                        val feedId = (leavingRaw as? Number)?.toLong()?.toBigInteger()
                        if (feedId != null) {
                            consoleLogE("SubMgr", "leaving feed=$feedId room=$roomId")
                            unsubscribe(feedId)
                        }
                    }


                    // Trigger C – host destroyed room
                    if (data.containsKey("destroyed")) {
                        consoleLogE("SubMgr", "room destroyed room=$roomId")
                        unsubscribeAllInRoom(roomId)
                    }
                }
                "updated" -> {
                    // Janus is renegotiating the subscriber stream (publisher toggled tracks,
                    // or stream composition changed). Re-negotiate the existing PC with the new offer.
                    val senderHandleId = (message["sender"] as? Number)?.toLong()?.toBigInteger()

                    // Find the subscription entry that owns this handle
                    val entry = activeSubscriptions.values.firstOrNull {
                        it.subscriberHandleId == senderHandleId
                    } ?: run {
                        consoleLogE("SubMgr", "updated: no entry for sender=$senderHandleId")
                        return
                    }

                    val jsepMap = message["jsep"] as? Map<*, *> ?: run {
                        consoleLogE("SubMgr", "updated: no jsep for feed=${entry.feedId}")
                        return
                    }

                    val offerSdp = jsepMap["sdp"] as? String ?: run {
                        consoleLogE("SubMgr", "updated: no sdp for feed=${entry.feedId}")
                        return
                    }

                    val pc = entry.peerConnection ?: run {
                        consoleLogE("SubMgr", "updated: no PC for feed=${entry.feedId}")
                        return
                    }

                    val handleId = entry.subscriberHandleId ?: return
                    val roomId   = entry.roomId
                    val feedId   = entry.feedId

                    consoleLogE("SubMgr", "updated: re-negotiating feed=$feedId")

                    val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            pc.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(sdp: SessionDescription) {
                                    pc.setLocalDescription(object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            signaling.sendMessage(
                                                body = mapOf("request" to "start", "room" to roomId),
                                                jsep = mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                                                handleId = handleId
                                            ) { resp, _ ->
                                                consoleLogE("SubMgr", "updated start resp feed=$feedId: $resp")
                                            }
                                        }
                                        override fun onSetFailure(e: String) { consoleLogE("SubMgr", "updated setLocal fail feed=$feedId: $e") }
                                    }, sdp)
                                }
                                override fun onCreateFailure(e: String) { consoleLogE("SubMgr", "updated createAnswer fail feed=$feedId: $e") }
                            }, MediaConstraints())
                        }
                        override fun onSetFailure(e: String?) { consoleLogE("SubMgr", "updated setRemote fail feed=$feedId: $e") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, remoteOffer)
                }

                // You can handle other videoroom event types here too
                "joined" -> { /* ... */ }
                "leaving" -> { /* handle participant left */ }
                "unpublished" -> { /* handle unpublish */ }

                else -> {
                    consoleLogE("sub", "Unhandled videoroom event type", videoroom)
                }
            }
        } catch (e: Exception) {
            consoleLogE("Error parsing Janus event", e.message ?: "Unknown error")
            e.printStackTrace()
        }
    }
}