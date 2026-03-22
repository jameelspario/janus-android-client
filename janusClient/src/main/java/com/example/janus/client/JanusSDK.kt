package com.example.janus.client

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main SDK entry point for Janus WebRTC client
 * Provides initialization and high-level API for managing rooms and streams
 *
 * * Usage:
 * val config = JanusSDKConfigBuilder(context)
 *     .serverUrl("wss://...")
 *     .build()
 *
 * val sdk = JanusSDK(context, config)
 *     .addInterceptor(LoggingInterceptor())
 *     .initialize(eventListener)
 *     .connect()
 */

class JanusSDK(
    val context: Context,
    val config: JanusSDKConfig
) {
    private lateinit var webRtcManager: WebRTCManager
    private lateinit var signalingManager: SignalingManager
    private lateinit var roomManager: RoomManager
    private lateinit var streamManager: StreamManager
    private lateinit var pkModeManager: JanusPKModeManager
    private var subscriptionManager: SubscriptionManager? = null

    private var sdkEventListener: JanusSDKEventListener? = null
    private val isInitialized = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    // Interceptor chain
    private val interceptors = mutableListOf<SDKInterceptor>()

    // State and statistics
    private var stateManager: SDKStateManager = SDKStateManager()
    private var statisticsCollector: StatisticsCollector = StatisticsCollector()

    // Room-to-streams mapping for easy management
    private val roomStreamsMap = ConcurrentHashMap<Int, MutableSet<String>>()

    /**
     * Add interceptor to the chain
     */
    fun addInterceptor(interceptor: SDKInterceptor) = apply {
        interceptors.add(interceptor)
    }

    /**
     * Add multiple interceptors
     */
    fun addInterceptors(interceptorList: List<SDKInterceptor>) = apply {
        interceptors.addAll(interceptorList)
    }

    /**
     * Initialize the SDK
     */
    fun initialize(listener: JanusSDKEventListener): JanusSDK {
        check(!isInitialized.get()) { "SDK already initialized" }

        return try {
            consoleLogE("JanusSDK", "Initializing SDK")

            sdkEventListener = listener

            // Initialize WebRTC
            webRtcManager = WebRTCManager(context, config.eglBaseContext)
            webRtcManager.initialize()

            // Initialize Signaling
            signalingManager = SignalingManager(config.janusServerUrl)
            signalingManager.setEventListener(createSignalingEventListener())

            // Initialize Room Manager
            roomManager = RoomManager(signalingManager)
            roomManager.setEventListener(createRoomEventListener())

            // Initialize Stream Manager
            streamManager = StreamManager(webRtcManager, signalingManager)
            streamManager.setEventListener(createStreamEventListener())

            // Initialize PK Mode Manager
            pkModeManager = JanusPKModeManager(roomManager)

            isInitialized.set(true)
            consoleLogE("JanusSDK", "SDK initialized successfully")
            this
        } catch (e: Exception) {
            consoleLogE("JanusSDK", "Initialization failed: ${e.message}")
            e.printStackTrace()
            throw RuntimeException("SDK initialization failed", e)
        }
    }

    fun enableSubscription(): JanusSDK {
        check(isInitialized.get()) {
            "Cannot enable subscriptions before SDK is initialized. " +
                    "Call initialize() first."
        }

        if (subscriptionManager != null) {
            consoleLogE("JanusSDK", "Subscriptions already enabled")
            return this
        }

        subscriptionManager = SubscriptionManager(
            signaling = signalingManager,
            webRtc = webRtcManager,
            sdkListener = sdkEventListener
        )

        return this
    }

    /**
     * Disables subscriptions and cleans up all active ones
     */
    fun disableSubscription(): JanusSDK {
        subscriptionManager?.disable()
        subscriptionManager = null
        consoleLogE("JanusSDK", "Subscription manager disabled")
        return this
    }

    /**
     * Connect to Janus server
     */
    fun connect(): JanusSDK {
        checkInitialized()

        if (isConnected.getAndSet(true)) {
            SDKLogger.warn("JanusSDK", "Already connected")
            return this
        }

        return try {
            // Pre-connect hooks
            if (!interceptors.all { it.onPreConnect() }) {
                isConnected.set(false)
                throw RuntimeException("Pre-connect check failed")
            }

            signalingManager.connect()
            SDKLogger.info("JanusSDK", "Connected to Janus server")

            // Post-connect hooks
            interceptors.forEach { it.onPostConnect() }
            this
        } catch (e: Exception) {
            isConnected.set(false)
            SDKLogger.error("JanusSDK", "Connection failed: ${e.message}", e)
            sdkEventListener?.onError("Connection failed: ${e.message}")
            this
        }
    }

    /**
     * Disconnect from Janus server
     */
    fun disconnect() : JanusSDK {
        checkInitialized()

        if (!isConnected.getAndSet(false)) {
            return this
        }

        return try {
            interceptors.forEach { it.onPreDisconnect() }

            // Leave all rooms and close streams
            stateManager.getRooms().forEach { roomId ->
                try {
                    leaveRoom(roomId)
                } catch (e: Exception) {
                    SDKLogger.warn("JanusSDK", "Error leaving room $roomId: ${e.message}")
                }
            }

            streamManager.closeAllStreams()
            signalingManager.disconnect()

            SDKLogger.info("JanusSDK", "Disconnected from server")
            interceptors.forEach { it.onPostDisconnect() }
            this
        } catch (e: Exception) {
            SDKLogger.error("JanusSDK", "Disconnect error: ${e.message}", e)
            this
        }
    }

    // ==================== ROOM MANAGEMENT ====================

    /**
     * Join a room as host or guest
     *
     * Guests join as viewers and can publish/unpublish on demand.
     * Hosts manage the room and their stream controls the broadcast.
     */
    fun joinRoom(
        roomId: Int,
        userId: String,
        userRole: UserRole,
        displayName: String
    ) : JanusSDK {
        checkInitialized()
        checkConnected()

        SDKLogger.info("JanusSDK", "Joining room $roomId as ${userRole.name}")
        roomManager.joinRoom(roomId, userId, userRole, displayName)
        return this
    }

    /**
     * Leave current room
     */
    fun leaveRoom(roomId: Int): JanusSDK {
        checkInitialized()

        SDKLogger.info("JanusSDK", "Leaving room $roomId")

        // Unpublish stream if active
        if (streamManager.isPublishing(roomId)) {
            unpublishStream(roomId)
        }

        // Unsubscribe from all streams in room
        roomStreamsMap[roomId]?.forEach { streamId ->
            unsubscribeStream(streamId)
        }
        roomStreamsMap.remove(roomId)

        roomManager.leaveRoom(roomId) { success, errorMsg ->
            sdkEventListener?.onRoomLeft(roomId)
        }
        stateManager.removeRoom(roomId)

        return this
    }

    /**
     * Get all rooms user is in
     */
    fun getRoomsInSession(): Set<Int> = stateManager.getRooms()

    /**
     * Get room details
     */
    fun getRoomInfo(roomId: Int): JanusRoom? = roomManager.getRoom(roomId)


    // ==================== STREAM MANAGEMENT ====================

    /**
     * Publish local audio/video stream
     */
    fun publishStream(roomId: Int, audioEnabled: Boolean, videoEnabled: Boolean): JanusSDK {
        checkInitialized()

        SDKLogger.info("JanusSDK", "Publishing stream in room $roomId (A:$audioEnabled V:$videoEnabled)")
        streamManager.publishStream(roomId, audioEnabled, videoEnabled)
        return this
    }

    /**
     * Unpublish local stream
     *
     * If a host unpublishes, the broadcast ends.
     *
     */
    fun unpublishStream(roomId: Int): JanusSDK {
        checkInitialized()

        SDKLogger.info("JanusSDK", "Unpublishing stream from room $roomId")
        streamManager.unpublishStream(roomId)
        return this
    }

    /**
     * Unsubscribe from a remote stream
     */
    fun unsubscribeStream(streamId: String): JanusSDK {
        checkInitialized()
        SDKLogger.info("JanusSDK", "Unsubscribing from stream $streamId")
//        streamManager.unsubscribeStream(streamId)
        return this
    }
    /**
     * Get all active streams in a room
     */
    fun getRoomStreams(roomId: Int): Set<String> = roomStreamsMap[roomId]?.toSet() ?: emptySet()

    /**
     * Check if currently publishing in a room
     */
//    fun isPublishing(roomId: Int): Boolean = streamManager.isPublishing(roomId)

    /**
     * Check if subscribed to a stream
     */
//    fun isSubscribed(streamId: String): Boolean = streamManager.isSubscribed(streamId)


    /**
     * Show remote stream
     */
    fun showRemoteStream(streamId: String, renderer: SurfaceViewRenderer): JanusSDK {
        checkInitialized()
//        streamManager.attachRenderer(streamId, renderer)
        return this
    }

    /**
     * Remove renderer
     */
    fun removeRenderer(renderer: SurfaceViewRenderer): JanusSDK {
        checkInitialized()
        renderer.release()
        return this
    }

    // =========================================================================
    //  RENDERING
    // =========================================================================

    /**
     * Attach local preview to a renderer.
     * Call after publishStream() succeeds or whenever you want a self-preview.
     */
    fun showLocalPreview(streamId: String, renderer: SurfaceViewRenderer, eglContext: EglBase.Context) {
        val track = streamManager.localVideoTrack ?: return
        renderer.init(eglContext, null)
        renderer.setMirror(true)
        renderer.keepScreenOn = true
        track.addSink(renderer)
    }

    fun removeLocalPreview(renderer: SurfaceViewRenderer) {
        streamManager.localVideoTrack?.removeSink(renderer)
        renderer.clearImage()
        renderer.release()
    }

    /**
     * Attach a SurfaceViewRenderer to a remote feed.
     *
     * Call this from JanusSDKEventListener.onRemoteStreamAvailable().
     * If the track is already live the renderer starts immediately.
     * If it hasn't arrived yet it is queued and attached once it does.
     *
     * @param feedId   the publisher's feed ID from onRemoteStreamAvailable
     * @param renderer an already-initialised SurfaceViewRenderer
     */
    fun showRemoteStream(feedId: BigInteger, renderer: SurfaceViewRenderer): JanusSDK {
        checkInitialized()
        subscriptionManager?.attachRenderer(feedId, renderer)
            ?: SDKLogger.warn("JanusSDK", "showRemoteStream: subscriptions not enabled")
        return this
    }

    /**
     * Remove the renderer for a remote feed.
     * Call when the corresponding View is being destroyed.
     */
    fun removeRemoteStream(feedId: BigInteger): JanusSDK {
        checkInitialized()
        subscriptionManager?.detachRenderer(feedId)
        return this
    }

    // =========================================================================
    //  DEVICE CONTROLS
    // =========================================================================

    fun toggleAudio(roomId: Int, enabled: Boolean) {
        checkInitialized()
        streamManager.localAudioTrack?.setEnabled(enabled)
    }

    fun toggleVideo(roomId: Int, enabled: Boolean) {
        checkInitialized()
        streamManager.localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera(): JanusSDK {
        checkInitialized()
        webRtcManager.switchCamera()
        return this
    }

    fun setSpeakerEnabled(enabled: Boolean): JanusSDK {
        checkInitialized()
        webRtcManager.setSpeakerEnabled(enabled)
        return this
    }

    // =========================================================================
    //  PK MODE
    // =========================================================================
    fun startPKMode(
        roomId1: Int,
        roomId2: Int,
        durationMs: Long = 0
    ): JanusSDK {
        checkInitialized()

        SDKLogger.info("JanusSDK", "Starting PK mode between rooms $roomId1 and $roomId2")

        if (!pkModeManager.startPKMode(roomId1, roomId2, durationMs)) {
            sdkEventListener?.onError("Failed to start PK mode")
        }
        return this
    }

    /**
     * Stop PK mode
     */
    fun stopPKMode(): JanusSDK {
        checkInitialized()

        SDKLogger.info("JanusSDK", "Stopping PK mode")
        pkModeManager.stopPKMode()
        return this
    }

    fun getPKModeStatistics(): PKModeStatistics = pkModeManager.getPKModeStatistics()
    fun getPKModeStatus(): PKModeStatus?         = pkModeManager.getPKModeStatus()
    fun isPKModeActive(): Boolean                = pkModeManager.isPKModeActive()

    // ==================== STATISTICS & MONITORING ====================

    /**
     * Get statistics for a stream
     */
//    fun getStreamStatistics(streamId: String): StreamStats? =
//        statisticsCollector.getStatistic(streamId)?.toStreamStats(streamId)

    /**
     * Get all statistics
     */
//    fun getAllStatistics(): Map<String, StreamStats> {
//        val stats = mutableMapOf<String, StreamStats>()
//        statisticsCollector.getAllStatistics().forEach { (streamId, stat) ->
//            stats[streamId] = stat.toStreamStats(streamId)
//        }
//        return stats
//    }

    // ==================== GETTERS FOR ADVANCED USAGE ====================

    fun getWebRTCManager(): WebRTCManager = webRtcManager
    fun getSignalingManager(): SignalingManager = signalingManager
    fun getRoomManager(): RoomManager = roomManager
    fun getStreamManager(): StreamManager = streamManager
    fun getPKModeManager(): JanusPKModeManager = pkModeManager
    fun getStateManager(): SDKStateManager = stateManager
    fun getStatisticsCollector(): StatisticsCollector = statisticsCollector

    /**
     * Get current room by ID
     */
    fun getRoom(roomId: Int): JanusRoom? = roomManager.getRoom(roomId)

    /**
     * Get all active rooms
     */
    fun getAllRooms(): List<JanusRoom> = roomManager.getAllRooms()

    /**
     * Get participants in a room
     */
    fun getParticipants(roomId: Int): List<JanusParticipant> = roomManager.getParticipants(roomId)

    /**
     * Release all resources
     */
    fun release(): JanusSDK {
        return try {
            if (isConnected.get()) {
                disconnect()
            }
            isInitialized.set(false)
            SDKLogger.info("JanusSDK", "SDK released")
            this
        } catch (e: Exception) {
            SDKLogger.error("JanusSDK", "Release error: ${e.message}", e)
            this
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun createSignalingEventListener() = object : JanusSignalingEventListener {
        override fun onConnected() {
            stateManager.setConnected(true)
            sdkEventListener?.onSignalingConnected()
        }

        override fun onDisconnected() {
            stateManager.setConnected(false)
            isConnected.set(false)
            sdkEventListener?.onSignalingDisconnected()
        }

        override fun onError(error: String) {
            sdkEventListener?.onError(error)
            interceptors.forEach { it.onError(error) }
        }

        override fun onMessage(message: Map<String, Any>) {
            // Delegate to managers
            roomManager.handleSignalingMessage(message)
            streamManager.handleSignalingMessage(message)
            subscriptionManager?.handleSignalingMessage(message)

        }
    }

    private fun createRoomEventListener() = object : JanusRoomEventListener {
        override fun onRoomJoined(room: JanusRoom, existingPublishers: List<Pair<BigInteger, String>>?) {
            stateManager.addRoom(room.roomId)
            roomStreamsMap.putIfAbsent(room.roomId, mutableSetOf())
            sdkEventListener?.onRoomJoined(room)

            existingPublishers?.let { pub ->
                if (pub.isNotEmpty()) {
                    consoleLogE(
                        "JanusSDK",
                        "Auto-subscribing to ${pub.size} existing publisher(s) in room=${room.roomId}"
                    )
                    pub.forEach { (feedId, display) ->
                        subscriptionManager?.subscribe(
                            roomId = room.roomId,
                            display = display,
                            feedId = feedId
                        )
                    }
                }
            }
        }

        override fun onRoomLeft(roomId: Int) {
            stateManager.removeRoom(roomId)
            roomStreamsMap.remove(roomId)
            sdkEventListener?.onRoomLeft(roomId)
        }

        override fun onParticipantJoined(participant: JanusParticipant) {
            sdkEventListener?.onParticipantJoined(participant)
        }

        override fun onParticipantLeft(participantId: String) {
            sdkEventListener?.onParticipantLeft(participantId)
        }

        override fun onError(error: String) {
            sdkEventListener?.onError(error)
            interceptors.forEach { it.onError(error) }
        }
    }

    private fun createStreamEventListener() = object : JanusStreamEventListener {
        override fun onStreamPublished(streamId: String) {
            stateManager.addStream(streamId)
            sdkEventListener?.onStreamPublished(streamId)
        }

        override fun onStreamUnpublished(streamId: String) {
            stateManager.removeStream(streamId)
            roomStreamsMap.values.forEach { it.remove(streamId) }
            sdkEventListener?.onStreamUnpublished(streamId)
        }

        override fun onStreamSubscribed(streamId: String) {
            stateManager.addStream(streamId)
            // Track stream in room
//            roomManager.getRoom(0)?.let { room ->
//                roomStreamsMap.computeIfAbsent(room.roomId) { mutableSetOf() }.add(streamId)
//            }
//            sdkEventListener?.onStreamSubscribed(streamId)
        }

        override fun onStreamUnsubscribed(streamId: String) {
            stateManager.removeStream(streamId)
            roomStreamsMap.values.forEach { it.remove(streamId) }
//            sdkEventListener?.onStreamUnsubscribed(streamId)
        }

        override fun onError(error: String) {
            sdkEventListener?.onError(error)
            interceptors.forEach { it.onError(error) }
        }
    }

    private fun checkInitialized() = check(isInitialized.get()) { "SDK not initialized. Call initialize() first." }
    private fun checkConnected()   = check(isConnected.get())   { "SDK not connected. Call connect() first." }

    companion object {
        /**
         * Create configuration builder
         */
        fun configBuilder(context: Context): JanusSDKConfigBuilder {
            return JanusSDKConfigBuilder(context)
        }
    }
}

