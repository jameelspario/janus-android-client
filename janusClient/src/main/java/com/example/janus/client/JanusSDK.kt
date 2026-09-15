package com.example.janus.client

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main SDK entry point for Janus WebRTC client
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
    private val interceptors = mutableListOf<SDKInterceptor>()

    private var stateManager: SDKStateManager = SDKStateManager()
    private var statisticsCollector: StatisticsCollector = StatisticsCollector()
    private val roomStreamsMap = ConcurrentHashMap<Int, MutableSet<String>>()

    fun addInterceptor(interceptor: SDKInterceptor) = apply {
        interceptors.add(interceptor)
    }

    fun initialize(listener: JanusSDKEventListener): JanusSDK {
        check(!isInitialized.get()) { "SDK already initialized" }
        return try {
            sdkEventListener = listener
            webRtcManager = WebRTCManager(context, config.eglBaseContext)
            webRtcManager.initialize()
            signalingManager = SignalingManager(config.janusServerUrl)
            signalingManager.setEventListener(createSignalingEventListener())
            roomManager = RoomManager(signalingManager)
            roomManager.setEventListener(createRoomEventListener())
            streamManager = StreamManager(webRtcManager, signalingManager)
            streamManager.setEventListener(createStreamEventListener())
            pkModeManager = JanusPKModeManager(roomManager)
            isInitialized.set(true)
            this
        } catch (e: Exception) {
            throw RuntimeException("SDK initialization failed", e)
        }
    }

    fun enableSubscription(): JanusSDK {
        check(isInitialized.get())
        if (subscriptionManager == null) {
            subscriptionManager = SubscriptionManager(signalingManager, webRtcManager, sdkEventListener)
        }
        return this
    }

    fun connect(): JanusSDK {
        checkInitialized()
        if (isConnected.getAndSet(true)) return this
        try {
            if (!interceptors.all { it.onPreConnect() }) {
                isConnected.set(false)
                throw RuntimeException("Pre-connect check failed")
            }
            signalingManager.connect()
            interceptors.forEach { it.onPostConnect() }
        } catch (e: Exception) {
            isConnected.set(false)
            sdkEventListener?.onError("Connection failed: ${e.message}")
        }
        return this
    }

    fun disconnect(): JanusSDK {
        checkInitialized()
        if (!isConnected.getAndSet(false)) return this
        interceptors.forEach { it.onPreDisconnect() }
        stateManager.getRooms().forEach { leaveRoom(it) }
        streamManager.closeAllStreams()
        signalingManager.disconnect()
        interceptors.forEach { it.onPostDisconnect() }
        return this
    }

    fun joinRoom(roomId: Int, userId: String, userRole: UserRole, displayName: String) {
        checkInitialized()
        roomManager.joinRoom(roomId, userId, userRole, displayName)
    }

    fun leaveRoom(roomId: Int) {
        checkInitialized()
        if (streamManager.isPublishing(roomId)) unpublishStream(roomId)
        roomManager.leaveRoom(roomId) { _, _ -> sdkEventListener?.onRoomLeft(roomId) }
        stateManager.removeRoom(roomId)
    }

    fun publishStream(roomId: Int, audioEnabled: Boolean, videoEnabled: Boolean) {
        checkInitialized()
        streamManager.publishStream(roomId, audioEnabled, videoEnabled)
    }

    fun unpublishStream(roomId: Int) {
        checkInitialized()
        streamManager.unpublishStream(roomId)
    }

    fun showLocalPreview(streamId: String, renderer: SurfaceViewRenderer, eglContext: EglBase.Context) {
        val track = streamManager.localVideoTrack ?: return
        renderer.init(eglContext, null)
        renderer.setMirror(true)
        track.addSink(renderer)
    }

    fun removeLocalPreview(renderer: SurfaceViewRenderer) {
        streamManager.localVideoTrack?.removeSink(renderer)
        renderer.clearImage()
        renderer.release()
    }

    fun showRemoteStream(feedId: BigInteger, renderer: SurfaceViewRenderer) {
        subscriptionManager?.attachRenderer(feedId, renderer)
    }

    fun removeRemoteStream(feedId: BigInteger) {
        subscriptionManager?.detachRenderer(feedId)
    }

    fun toggleAudio(roomId: Int, enabled: Boolean) {
        streamManager.localAudioTrack?.setEnabled(enabled)
    }

    fun toggleVideo(roomId: Int, enabled: Boolean) {
        streamManager.localVideoTrack?.setEnabled(enabled)
    }

    fun release() {
        if (isConnected.get()) disconnect()
        isInitialized.set(false)
    }

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
        }
        override fun onMessage(message: Map<String, Any>) {
            roomManager.handleSignalingMessage(message)
            streamManager.handleSignalingMessage(message)
            subscriptionManager?.handleSignalingMessage(message)
        }
    }

    private fun createRoomEventListener() = object : JanusRoomEventListener {
        override fun onRoomJoined(room: JanusRoom, existingPublishers: List<Pair<BigInteger, String>>?) {
            stateManager.addRoom(room.roomId)
            sdkEventListener?.onRoomJoined(room)
            existingPublishers?.forEach { (feedId, display) ->
                subscriptionManager?.subscribe(room.roomId, display, feedId)
            }
        }
        override fun onRoomLeft(roomId: Int) = sdkEventListener!!.onRoomLeft(roomId)
        override fun onParticipantJoined(participant: JanusParticipant) = sdkEventListener!!.onParticipantJoined(participant)
        override fun onParticipantLeft(participantId: String) = sdkEventListener!!.onParticipantLeft(participantId)
        override fun onError(error: String) = sdkEventListener!!.onError(error)
    }

    private fun createStreamEventListener() = object : JanusStreamEventListener {
        override fun onStreamPublished(streamId: String) = sdkEventListener!!.onStreamPublished(streamId)
        override fun onStreamUnpublished(streamId: String) = sdkEventListener!!.onStreamUnpublished(streamId)
        override fun onStreamSubscribed(streamId: String) {

        }

        override fun onStreamUnsubscribed(streamId: String) {
        }

        override fun onError(error: String) = sdkEventListener!!.onError(error)
    }

    private fun checkInitialized() {
        if (!isInitialized.get()) throw IllegalStateException("SDK not initialized")
    }

    fun getRoom(roomId: Int): JanusRoom? = roomManager.getRoom(roomId)
}