package com.example.janus.client

import org.webrtc.VideoTrack
import java.io.Serializable
import java.math.BigInteger

/**
 * Represents a chat room
 */
data class JanusRoom(
    val roomId: Int,
    val roomName: Int,
    val maxParticipants: Int = 100,
    val bitrate: Int = 2000,
    val videoCodec: String = "vp8",
    val audioCodec: String = "opus",
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val participants: MutableList<JanusParticipant> = mutableListOf(),
    var host: JanusParticipant? = null,
    var isActive: Boolean = true,
    var description: String = ""
) : Serializable {
    fun getParticipantCount(): Int = participants.size

    fun getPublishingParticipants(): List<JanusParticipant> =
        participants.filter { it.isPublishing }

    fun getSubscribingParticipants(): List<JanusParticipant> =
        participants.filter { it.isSubscribed && !it.isPublishing }
}

/**
 * Represents a participant/user in a room
 */
data class JanusParticipant(
    val participantId: String,
    val userId: String,
    val displayName: String,
    val userRole: UserRole = UserRole.GUEST,
    val joinedAt: Long = System.currentTimeMillis(),
    var isPublishing: Boolean = false,
    var isSubscribed: Boolean = false,
    var audioEnabled: Boolean = false,
    var videoEnabled: Boolean = false,
    var streamId: String? = null,
    var peerId: String? = null,
    var isOnMute: Boolean = false,
    var cameraEnabled: Boolean = false
) : Serializable {
    fun isHost(): Boolean = userRole == UserRole.HOST
    fun isGuest(): Boolean = userRole == UserRole.GUEST
}

/**
 * Represents a media stream
 */
data class JanusStream(
    val streamId: String,
    val participantId: String,
    val participantName: String,
    val type: StreamType = StreamType.WEBRTC,
    val audioCodec: String = "opus",
    val videoCodec: String = "vp8",
    val createdAt: Long = System.currentTimeMillis(),
    var videoTracks: Int = 0,
    var audioTracks: Int = 0,
    var isActive: Boolean = true,
    var videoBitrate: Int = 2000,
    var audioBitrate: Int = 128,
    var lastUpdateTime: Long = System.currentTimeMillis()
) : Serializable

/**
 * Statistics for a stream
 */
data class StreamStats(
    val streamId: String,
    val participantId: String,
    val videoFramesDecoded: Long = 0,
    val videoFramesEncoded: Long = 0,
    val videoFrameRate: Int = 0,
    val videoBitrate: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioLevel: Float = 0f,
    val audioBitrate: Int = 0,
    val audioJitterBufferMs: Int = 0,
    val roundTripTime: Long = 0,
    val packetsLost: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * Represents local stream configuration
 */
data class LocalStreamConfig(
    val roomId: String,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoFps: Int = 30,
    val videoBitrate: Int = 2000,
    val audioSampleRate: Int = 48000,
    val audioChannels: Int = 1,
    val audioBitrate: Int = 128,
    val frontCamera: Boolean = true,
    val useSpeaker: Boolean = true
) : Serializable

// ==================== PK MODE MODELS ====================

/**
 * PK mode configuration
 */
data class PKModeConfig(
    val pkId: String,
    val room1Id: Int,
    val room2Id: Int,
    val room1Name: String,
    val room2Name: String,
    val startTime: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val isActive: Boolean = true,
    val createdBy: String = ""
) : Serializable {
    fun isExpired(): Boolean = durationMs > 0 && (System.currentTimeMillis() - startTime) > durationMs
}

/**
 * PK Mode Status
 */
data class PKModeStatus(
    val pkId: String = "",
    val room1Id: Int = 0,
    val room1Name: String = "",
    val room1ParticipantCount: Int = 0,
    val room1Host: String = "",
    val room2Id: Int = 0,
    val room2Name: String = "",
    val room2ParticipantCount: Int = 0,
    val room2Host: String = "",
    val startTime: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val elapsedMs: Long = 0,
    val isActive: Boolean = false
) : Serializable {
    fun getElapsedSeconds(): Long = elapsedMs / 1000
    fun getRemainingSeconds(): Long = if (durationMs > 0) (durationMs - elapsedMs) / 1000 else -1
}

/**
 * PK Mode Statistics
 */
data class PKModeStatistics(
    val pkId: String = "",
    val room1Id: Int = 0,
    val room1ParticipantCount: Int = 0,
    val room1PublishingCount: Int = 0,
    val room1SubscribingCount: Int = 0,
    val room2Id: Int = 0,
    val room2ParticipantCount: Int = 0,
    val room2PublishingCount: Int = 0,
    val room2SubscribingCount: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val elapsedMs: Long = 0,
    val isActive: Boolean = false
) : Serializable {
    fun getTotalParticipants(): Int = room1ParticipantCount + room2ParticipantCount
    fun getTotalPublishers(): Int = room1PublishingCount + room2PublishingCount
}

/**
 * Signaling message types
 */
enum class SignalingMessageType {
    // Room signaling
    CREATE_ROOM,
    JOIN_ROOM,
    LEAVE_ROOM,
    ROOM_JOINED,
    ROOM_LEFT,
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,

    // Stream signaling
    PUBLISH_STREAM,
    UNPUBLISH_STREAM,
    SUBSCRIBE_STREAM,
    UNSUBSCRIBE_STREAM,

    // WebRTC signaling
    OFFER,
    ANSWER,
    ICE_CANDIDATE,

    // Control messages
    MUTE_AUDIO,
    UNMUTE_AUDIO,
    MUTE_VIDEO,
    UNMUTE_VIDEO,

    // PK mode
    START_PK_MODE,
    STOP_PK_MODE,

    // Status/Health
    KEEP_ALIVE,
    PING,
    PONG,
    ERROR,
    UNKNOWN
}

/**
 * Generic signaling message wrapper
 */
data class SignalingMessage(
    val type: SignalingMessageType,
    val sender: String,
    val receiver: String? = null,
    val roomId: String? = null,
    val participantId: String? = null,
    val streamId: String? = null,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * Error model
 */
data class JanusError(
    val code: Int,
    val message: String,
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {
    companion object {
        const val CODE_ROOM_NOT_FOUND = 400
        const val CODE_PARTICIPANT_NOT_FOUND = 401
        const val CODE_STREAM_NOT_FOUND = 402
        const val CODE_PUBLISH_FAILED = 403
        const val CODE_SUBSCRIBE_FAILED = 404
        const val CODE_CONNECTION_FAILED = 500
        const val CODE_UNKNOWN = 999
    }
}

// ==================== ENUMS ====================

enum class StreamType {
    WEBRTC, RTMP, HLS, FILE
}

enum class UserRole {
    HOST,
    GUEST
}

// ==================== CONFIGURATION ====================

/**
 * SDK Configuration
 */
data class JanusSDKConfig(
    val janusServerUrl: String,
    val eglBaseContext: org.webrtc.EglBase.Context? = null,
    val stunServers: List<String> = listOf("stun:stun.l.google.com:19302"),
    val turnServers: List<String> = emptyList(),
    val turnUsername: String = "",
    val turnPassword: String = "",
    val enableAudio: Boolean = true,
    val enableVideo: Boolean = true,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoBitrate: Int = 2000,
    val videoFps: Int = 30,
    val logLevel: LogLevel = LogLevel.INFO,
    val keepAliveInterval: Long = 30000,
    val connectionTimeoutMs: Long = 10000
)

/**
 * Builder pattern for JanusSDKConfig
 */
class JanusSDKConfigBuilder(private val context: android.content.Context) {
    private var janusServerUrl: String = ""
    private var eglBaseContext: org.webrtc.EglBase.Context? = null
    private var stunServers: List<String> = listOf("stun:stun.l.google.com:19302")
    private var turnServers: List<String> = emptyList()
    private var turnUsername: String = ""
    private var turnPassword: String = ""
    private var enableAudio: Boolean = true
    private var enableVideo: Boolean = true
    private var videoWidth: Int = 1280
    private var videoHeight: Int = 720
    private var videoBitrate: Int = 2000
    private var videoFps: Int = 30
    private var logLevel: LogLevel = LogLevel.INFO
    private var keepAliveInterval: Long = 30000
    private var connectionTimeoutMs: Long = 10000

    fun serverUrl(url: String) = apply { this.janusServerUrl = url }
    fun eglContext(ctx: org.webrtc.EglBase.Context?) = apply { this.eglBaseContext = ctx }
    fun stunServers(servers: List<String>) = apply { this.stunServers = servers }
    fun addStunServer(server: String) = apply { this.stunServers = this.stunServers + server }
    fun turnServers(servers: List<String>, username: String, password: String) = apply {
        this.turnServers = servers
        this.turnUsername = username
        this.turnPassword = password
    }
    fun addTurnServer(server: String, username: String, password: String) = apply {
        this.turnServers = this.turnServers + server
        this.turnUsername = username
        this.turnPassword = password
    }
    fun audio(enabled: Boolean) = apply { this.enableAudio = enabled }
    fun video(enabled: Boolean) = apply { this.enableVideo = enabled }
    fun videoResolution(width: Int, height: Int) = apply {
        this.videoWidth = width
        this.videoHeight = height
    }
    fun videoBitrate(bitrate: Int) = apply {
        require(bitrate in 100..8000) { "Bitrate must be 100-8000 kbps" }
        this.videoBitrate = bitrate
    }
    fun videoFps(fps: Int) = apply {
        require(fps in 15..60) { "FPS must be 15-60" }
        this.videoFps = fps
    }
    fun logLevel(level: LogLevel) = apply { this.logLevel = level }
    fun keepAliveInterval(intervalMs: Long) = apply { this.keepAliveInterval = intervalMs }
    fun connectionTimeout(timeoutMs: Long) = apply { this.connectionTimeoutMs = timeoutMs }

    fun build(): JanusSDKConfig {
        validate()
        return JanusSDKConfig(
            janusServerUrl = janusServerUrl,
            eglBaseContext = eglBaseContext,
            stunServers = stunServers,
            turnServers = turnServers,
            turnUsername = turnUsername,
            turnPassword = turnPassword,
            enableAudio = enableAudio,
            enableVideo = enableVideo,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoBitrate = videoBitrate,
            videoFps = videoFps,
            logLevel = logLevel,
            keepAliveInterval = keepAliveInterval,
            connectionTimeoutMs = connectionTimeoutMs
        )
    }

    private fun validate() {
        check(janusServerUrl.isNotEmpty()) { "Server URL is required" }
        check(janusServerUrl.startsWith("ws://") || janusServerUrl.startsWith("wss://")) {
            "Server URL must start with ws:// or wss://"
        }
        check(stunServers.isNotEmpty()) { "At least one STUN server is required" }
    }
}

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

// ==================== EVENT LISTENERS ====================

/**
 * Main SDK event listener
 */
interface JanusSDKEventListener {
    fun onSignalingConnected()
    fun onSignalingDisconnected()
    fun onRoomJoined(room: JanusRoom)
    fun onRoomLeft(roomId: Int)
    fun onParticipantJoined(participant: JanusParticipant)
    fun onParticipantLeft(participantId: String)
    fun onStreamPublished(streamId: String)
    fun onStreamUnpublished(streamId: String)
    fun onRemoteStreamAvailable(feedId: BigInteger, display: String?, track: VideoTrack)
    fun onRemoteStreamRemoved(feedId: BigInteger?)
    fun onError(error: String)
}

/**
 * Signaling event listener
 */
interface JanusSignalingEventListener {
    fun onConnected()
    fun onDisconnected()
    fun onError(error: String)
    fun onMessage(message: Map<String, Any>)
}

/**
 * PK Mode listener
 */
interface PKModeListener {
    fun onPKModeStarted(pkMode: PKModeConfig)
    fun onPKModeStopped(pkMode: PKModeConfig)
}

// ==================== INTERCEPTOR PATTERN ====================

/**
 * SDK Interceptor for middleware pattern
 */
interface SDKInterceptor {
    fun onPreConnect(): Boolean = true
    fun onPostConnect() {}
    fun onPreDisconnect(): Boolean = true
    fun onPostDisconnect() {}
    fun onError(error: String) {}
}

/**
 * Logging Interceptor Example
 */
class LoggingInterceptor : SDKInterceptor {
    override fun onPreConnect(): Boolean {
        SDKLogger.debug("LoggingInterceptor", "Pre-connect phase")
        return true
    }

    override fun onPostConnect() {
        SDKLogger.debug("LoggingInterceptor", "Post-connect phase")
    }

    override fun onPreDisconnect(): Boolean {
        SDKLogger.debug("LoggingInterceptor", "Pre-disconnect phase")
        return true
    }

    override fun onError(error: String) {
        SDKLogger.error("LoggingInterceptor", "Error: $error")
    }
}

/**
 * Retry Interceptor Example
 */
class RetryInterceptor(private val maxRetries: Int = 3) : SDKInterceptor {
    private val retryManager = ConnectionRetryManager(maxRetries)

    override fun onError(error: String) {
        if (retryManager.canRetry()) {
            val delayMs = retryManager.getNextRetryDelayMs()
            SDKLogger.warn("RetryInterceptor", "Will retry in ${delayMs}ms")
            retryManager.recordRetry()
        }
    }
}