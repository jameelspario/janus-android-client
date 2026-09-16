package com.example.janus.client.room

import android.content.Context
import com.example.janus.client.SDKLogger
import com.example.janus.client.UserRole
import com.example.janus.client.participant.LocalParticipant
import com.example.janus.client.participant.LocalParticipantController
import com.example.janus.client.participant.RemoteParticipant
import com.example.janus.client.signaling.JanusSignalingClient
import com.example.janus.client.signaling.KeepAliveManager
import com.example.janus.client.signaling.SignalingEvent
import com.example.janus.client.track.LocalAudioTrack
import com.example.janus.client.track.LocalTrackPublication
import com.example.janus.client.track.LocalVideoTrack
import com.example.janus.client.track.RemoteAudioTrack
import com.example.janus.client.track.RemoteTrackPublication
import com.example.janus.client.track.RemoteVideoTrack
import com.example.janus.client.track.Track
import com.example.janus.client.videoroom.RemoteSubscriptionListener
import com.example.janus.client.videoroom.RemoteSubscriptionManager
import com.example.janus.client.videoroom.VideoRoomPlugin
import com.example.janus.client.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The central coordinator for a Janus video session, following the LiveKit SDK Room pattern.
 *
 * Provides connection controls, reactive Kotlin [StateFlow] and [SharedFlow] states,
 * and high-level camera and microphone manipulation.
 */
class Room(
    val context: Context,
    val options: RoomOptions = RoomOptions()
) : LocalParticipantController, RemoteSubscriptionListener {

    private val TAG = "Room"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connectionMutex = Mutex()

    // ── Connection State ──
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // ── Event Stream ──
    private val _events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RoomEvent> = _events.asSharedFlow()

    // ── Room Info ──
    var roomId: Int? = null
        private set
    var name: String? = null
        private set

    // ── Participants ──
    lateinit var localParticipant: LocalParticipant
        private set

    private val remoteParticipantMap = ConcurrentHashMap<String, RemoteParticipant>()
    private val _remoteParticipants = MutableStateFlow<Map<String, RemoteParticipant>>(emptyMap())
    val remoteParticipants: StateFlow<Map<String, RemoteParticipant>> = _remoteParticipants.asStateFlow()

    // ── Engine & Signaling ──
    private val webRtcEngine = WebRtcEngine(context, options)
    private val signalingClient = JanusSignalingClient(scope)
    private val subscriptionManager = RemoteSubscriptionManager(scope, signalingClient, webRtcEngine, this)
    private val videoRoomPlugin = VideoRoomPlugin(scope, signalingClient, webRtcEngine, subscriptionManager)
    private val keepAliveManager = KeepAliveManager(options.keepAliveIntervalMs) {
        val sId = videoRoomPlugin.sessionId
        if (sId != null && signalingClient.isConnected()) {
            signalingClient.sendKeepAlive(sId)
        }
    }

    private val listeners = CopyOnWriteArrayList<RoomListener>()
    private var signalingEventsJob: Job? = null

    // ── Local Media Tracks ──
    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null

    init {
        localParticipant = LocalParticipant("local", "Me", UserRole.GUEST, this)
    }

    /**
     * Connects to a Janus VideoRoom.
     *
     * @param url WebSocket URL (e.g. wss://janus.conf.meetecho.com/ws)
     * @param roomId Janus room identifier
     * @param userId Application-level user identifier
     * @param displayName Name visible to other participants
     * @param role [UserRole.HOST] or [UserRole.GUEST]
     */
    suspend fun connect(
        url: String,
        roomId: Int,
        userId: String,
        displayName: String,
        role: UserRole = UserRole.GUEST
    ) {
        connectionMutex.withLock {
            if (_state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.CONNECTING) {
                SDKLogger.warn(TAG, "Already connected or connecting to room")
                return
            }

            try {
                _state.value = ConnectionState.CONNECTING
                this.roomId = roomId
                this.name = displayName

                localParticipant = LocalParticipant(userId, displayName, role, this)

                // 1. Initialize WebRTC
                check(webRtcEngine.initialize()) { "Failed to initialize WebRtcEngine" }

                // 2. Connect Signaling
                signalingClient.connect(url, options.connectionTimeoutMs)
                observeSignalingEvents()

                // 3. Create Janus Session
                val sessionId = signalingClient.createSession(options.connectionTimeoutMs)
                videoRoomPlugin.sessionId = sessionId

                // 4. Start Keep-Alive
                keepAliveManager.start(scope)

                // 5. Attach VideoRoom Plugin
                val publisherHandleId = signalingClient.attachPlugin(sessionId, "janus.plugin.videoroom")
                videoRoomPlugin.publisherHandleId = publisherHandleId

                // 6. Join (or create and join) Room
                val joinResult = videoRoomPlugin.joinRoom(roomId, userId, displayName, role)
                SDKLogger.info(TAG, "Successfully joined room $roomId with participantId: ${joinResult.participantId}")

                // 7. Auto-subscribe to all existing publishers in one shot
                // (single subscriber handle for all feeds — matches videoroomtest.js)
                if (options.autoSubscribe && joinResult.publishers.isNotEmpty()) {
                    subscriptionManager.subscribeTo(
                        sessionId, roomId, joinResult.privateId, joinResult.publishers
                    )
                }

                // 8. Publish Local Stream if enabled
                if (options.audioEnabled || options.videoEnabled) {
                    publishLocalMedia(options.audioEnabled, options.videoEnabled)
                }

                _state.value = ConnectionState.CONNECTED
                emitEvent(RoomEvent.Connected(this))

            } catch (e: Exception) {
                SDKLogger.error(TAG, "Connection failed", e)
                _state.value = ConnectionState.DISCONNECTED
                emitEvent(RoomEvent.FailedToConnect(this, e))
                cleanup()
                throw e
            }
        }
    }

    /**
     * Publishes local audio and video tracks to the connected room.
     */
    suspend fun publishLocalMedia(audio: Boolean, video: Boolean) {
        val rId = roomId ?: return

        if (audio && localAudioTrack == null) {
            val audioTrack = webRtcEngine.createLocalAudioTrack("audio-local")
            localAudioTrack = audioTrack
            val pub = LocalTrackPublication(audioTrack.sid, Track.Kind.AUDIO, audioTrack)
            localParticipant.addPublication(pub)
            localParticipant.isMicrophoneEnabled = true
            emitEvent(RoomEvent.TrackPublished(localParticipant, pub))
        }

        if (video && localVideoTrack == null) {
            val videoTrack = webRtcEngine.createLocalVideoTrack("video-local")
            if (videoTrack != null) {
                localVideoTrack = videoTrack
                val pub = LocalTrackPublication(videoTrack.sid, Track.Kind.VIDEO, videoTrack)
                localParticipant.addPublication(pub)
                localParticipant.isCameraEnabled = true
                emitEvent(RoomEvent.TrackPublished(localParticipant, pub))
            }
        }

        videoRoomPlugin.publishLocalStream(rId, localAudioTrack, localVideoTrack)
    }

    /**
     * Disconnects from the current room and cleans up all active tracks and peer connections.
     */
    suspend fun disconnect() {
        connectionMutex.withLock {
            if (_state.value == ConnectionState.DISCONNECTED) return

            SDKLogger.info(TAG, "Disconnecting from room ${roomId}...")
            try {
                roomId?.let { videoRoomPlugin.leaveRoom(it) }
            } catch (e: Exception) {
                SDKLogger.warn(TAG, "Error leaving room: ${e.message}")
            }

            cleanup()
            _state.value = ConnectionState.DISCONNECTED
            emitEvent(RoomEvent.Disconnected(this, null))
        }
    }

    private fun cleanup() {
        keepAliveManager.stop()
        signalingEventsJob?.cancel()
        signalingEventsJob = null

        videoRoomPlugin.sessionId?.let { sId ->
            subscriptionManager.unsubscribeAll(sId)
        }

        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localParticipant.clearPublications()

        remoteParticipantMap.clear()
        _remoteParticipants.value = emptyMap()

        signalingClient.disconnect()
        webRtcEngine.dispose()
    }

    // ── LocalParticipantController Implementation ──

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        val pub = localParticipant.audioTracks.firstOrNull() as? LocalTrackPublication
        pub?.updateMuted(!enabled)
        if (pub != null) {
            if (enabled) emitEvent(RoomEvent.TrackUnmuted(localParticipant, pub))
            else emitEvent(RoomEvent.TrackMuted(localParticipant, pub))
        }
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        val pub = localParticipant.videoTracks.firstOrNull() as? LocalTrackPublication
        pub?.updateMuted(!enabled)
        if (pub != null) {
            if (enabled) emitEvent(RoomEvent.TrackUnmuted(localParticipant, pub))
            else emitEvent(RoomEvent.TrackMuted(localParticipant, pub))
        }
    }

    override fun switchCamera() {
        webRtcEngine.switchCamera()
    }

    /**
     * Initializes a [SurfaceViewRenderer] with this room's EGL context and optimal scaling.
     */
    fun initVideoRenderer(renderer: SurfaceViewRenderer, mirror: Boolean = false) {
        try {
            renderer.init(webRtcEngine.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(mirror)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Renderer already initialized or error: ${e.message}")
        }
    }

    // ── RemoteSubscriptionListener Implementation ──

    override fun onRemoteTrackSubscribed(feedId: BigInteger, display: String, track: Track) {
        val identity = feedId.toString()
        val participant = remoteParticipantMap.getOrPut(identity) {
            RemoteParticipant(feedId, identity, display).also {
                emitEvent(RoomEvent.ParticipantConnected(this, it))
            }
        }

        val publication = RemoteTrackPublication(track.sid, track.kind, track)
        participant.addPublication(publication)
        _remoteParticipants.value = remoteParticipantMap.toMap()

        emitEvent(RoomEvent.TrackSubscribed(track, publication, participant))
    }

    override fun onRemoteTrackUnsubscribed(feedId: BigInteger, track: Track?) {
        val identity = feedId.toString()
        val participant = remoteParticipantMap.remove(identity)
        if (participant != null) {
            _remoteParticipants.value = remoteParticipantMap.toMap()
            val existingPubs = participant.tracks.value.values.filterIsInstance<RemoteTrackPublication>()
            if (existingPubs.isNotEmpty()) {
                existingPubs.forEach { pub ->
                    pub.track?.let { trk ->
                        emitEvent(RoomEvent.TrackUnsubscribed(trk, pub, participant))
                    }
                }
            } else if (track != null) {
                val pub = RemoteTrackPublication(track.sid, track.kind, track)
                emitEvent(RoomEvent.TrackUnsubscribed(track, pub, participant))
            }
            emitEvent(RoomEvent.ParticipantDisconnected(this, participant))
        }
    }

    private fun observeSignalingEvents() {
        signalingEventsJob?.cancel()
        signalingEventsJob = scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.UnsolicitedEvent -> {
                        videoRoomPlugin.handleUnsolicitedEvent(event.message, roomId)
                    }

                    is SignalingEvent.Disconnected -> {
                        if (_state.value == ConnectionState.CONNECTED) {
                            _state.value = ConnectionState.DISCONNECTED
                            emitEvent(RoomEvent.Disconnected(this@Room, RuntimeException("Signaling disconnected: ${event.reason}")))
                        }
                    }
                    is SignalingEvent.Error -> {
                        emitEvent(RoomEvent.FailedToConnect(this@Room, event.error))
                    }
                    else -> {}
                }
            }
        }
    }

    private fun emitEvent(event: RoomEvent) {
        _events.tryEmit(event)
        // Notify listeners
        listeners.forEach { listener ->
            try {
                when (event) {
                    is RoomEvent.Connected -> listener.onConnected(event.room)
                    is RoomEvent.Disconnected -> listener.onDisconnected(event.room, event.error)
                    is RoomEvent.Reconnecting -> listener.onReconnecting(event.room)
                    is RoomEvent.Reconnected -> listener.onReconnected(event.room)
                    is RoomEvent.FailedToConnect -> listener.onFailedToConnect(event.room, event.error)
                    is RoomEvent.ParticipantConnected -> listener.onParticipantConnected(event.room, event.participant)
                    is RoomEvent.ParticipantDisconnected -> listener.onParticipantDisconnected(event.room, event.participant)
                    is RoomEvent.TrackPublished -> listener.onTrackPublished(event.participant, event.publication)
                    is RoomEvent.TrackUnpublished -> listener.onTrackUnpublished(event.participant, event.publication)
                    is RoomEvent.TrackSubscribed -> listener.onTrackSubscribed(event.track, event.publication, event.participant)
                    is RoomEvent.TrackUnsubscribed -> listener.onTrackUnsubscribed(event.track, event.publication, event.participant)
                    is RoomEvent.TrackMuted -> listener.onTrackMuted(event.participant, event.publication)
                    is RoomEvent.TrackUnmuted -> listener.onTrackUnmuted(event.participant, event.publication)
                }
            } catch (e: Exception) {
                SDKLogger.error(TAG, "Error in RoomListener callback", e)
            }
        }
    }

    fun addListener(listener: RoomListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: RoomListener) {
        listeners.remove(listener)
    }

    fun release() {
        scope.launch { disconnect() }
    }
}
