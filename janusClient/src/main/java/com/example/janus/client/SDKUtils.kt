package com.example.janus.client

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SDK State Manager - Tracks current state of SDK
 */
class SDKStateManager {
    private var isConnected = false
    private var currentRooms = mutableSetOf<Int>()
    private var activeStreams = mutableSetOf<String>()
    private var currentPKMode: PKModeConfig? = null
    private val lock = Any()

    fun setConnected(connected: Boolean) {
        synchronized(lock) {
            this.isConnected = connected
        }
    }

    fun isConnected(): Boolean = synchronized(lock) { isConnected }

    fun addRoom(roomId: Int) {
        synchronized(lock) {
            currentRooms.add(roomId)
        }
    }

    fun removeRoom(roomId: Int) {
        synchronized(lock) {
            currentRooms.remove(roomId)
        }
    }

    fun getRooms(): Set<Int> = synchronized(lock) { currentRooms.toSet() }

    fun addStream(streamId: String) {
        synchronized(lock) {
            activeStreams.add(streamId)
        }
    }

    fun removeStream(streamId: String) {
        synchronized(lock) {
            activeStreams.remove(streamId)
        }
    }

    fun getStreams(): Set<String> = synchronized(lock) { activeStreams.toSet() }

    fun setPKMode(pkMode: PKModeConfig?) {
        synchronized(lock) {
            this.currentPKMode = pkMode
        }
    }

    fun getPKMode(): PKModeConfig? = synchronized(lock) { currentPKMode }

    fun reset() {
        synchronized(lock) {
            isConnected = false
            currentRooms.clear()
            activeStreams.clear()
            currentPKMode = null
        }
    }
}

/**
 * Statistics collector for monitoring SDK performance
 */
class StatisticsCollector {
    private val statistics = mutableMapOf<String, StreamStatistic>()
    private val lock = Any()

    data class StreamStatistic(
        val streamId: String,
        var videoFramesEncoded: Long = 0,
        var videoFramesDecoded: Long = 0,
        var audioLevel: Float = 0f,
        var videoBitrate: Int = 0,
        var audioBitrate: Int = 0,
        var roundTripTime: Long = 0,
        var packetsLost: Long = 0,
        var lastUpdateTime: Long = System.currentTimeMillis()
    )

    fun updateStatistic(streamId: String, updater: (StreamStatistic) -> Unit) {
        synchronized(lock) {
            val stat = statistics.getOrPut(streamId) {
                StreamStatistic(streamId)
            }
            updater(stat)
            stat.lastUpdateTime = System.currentTimeMillis()
        }
    }

    fun getStatistic(streamId: String): StreamStatistic? {
        synchronized(lock) {
            return statistics[streamId]
        }
    }

    fun getAllStatistics(): Map<String, StreamStatistic> {
        synchronized(lock) {
            return statistics.toMap()
        }
    }

    fun removeStatistic(streamId: String) {
        synchronized(lock) {
            statistics.remove(streamId)
        }
    }

    fun clear() {
        synchronized(lock) {
            statistics.clear()
        }
    }
}

/**
 * Room state tracker
 */
class RoomStateTracker {
    private val roomStates = ConcurrentHashMap<String, RoomState>()

    data class RoomState(
        val roomId: String,
        var isHostPresent: Boolean = false,
        var participantCount: Int = 0,
        var publishingCount: Int = 0,
        var subscribingCount: Int = 0,
        var createdAt: Long = System.currentTimeMillis(),
        var lastActivityTime: Long = System.currentTimeMillis()
    )

    fun createRoomState(roomId: String): RoomState {
        val state = RoomState(roomId)
        roomStates[roomId] = state
        return state
    }

    fun updateRoomState(roomId: String, updater: (RoomState) -> Unit) {
        val state = roomStates.getOrPut(roomId) { RoomState(roomId) }
        updater(state)
        state.lastActivityTime = System.currentTimeMillis()
    }

    fun getRoomState(roomId: String): RoomState? = roomStates[roomId]

    fun getAllRoomStates(): Map<String, RoomState> = roomStates.toMap()

    fun removeRoomState(roomId: String) {
        roomStates.remove(roomId)
    }

    fun clear() {
        roomStates.clear()
    }
}

/**
 * Connection retry manager with exponential backoff
 */
class ConnectionRetryManager(
    private val maxRetries: Int = 5,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30000
) {
    private var retryCount = 0
    private var lastRetryTime = 0L

    fun canRetry(): Boolean = retryCount < maxRetries

    fun getNextRetryDelayMs(): Long {
        retryCount++
        val delay = (initialDelayMs * Math.pow(2.0, (retryCount - 1).toDouble())).toLong()
        return Math.min(delay, maxDelayMs)
    }

    fun recordRetry() {
        lastRetryTime = System.currentTimeMillis()
    }

    fun reset() {
        retryCount = 0
        lastRetryTime = 0L
    }

    fun getRetryCount(): Int = retryCount
}

/**
 * Event bus for SDK events
 */
class SDKEventBus {
    private val subscribers = ConcurrentHashMap<Class<*>, MutableList<(Any) -> Unit>>()
    private val lock = Any()

    fun <T : Any> subscribe(eventClass: Class<T>, handler: (T) -> Unit) {
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            subscribers.getOrPut(eventClass) { mutableListOf() }
                .add(handler as (Any) -> Unit)
        }
    }

    fun <T : Any> unsubscribe(eventClass: Class<T>, handler: (T) -> Unit) {
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            subscribers[eventClass]?.remove(handler as (Any) -> Unit)
        }
    }

    fun <T : Any> publish(event: T) {
        val handlers = synchronized(lock) {
            subscribers[event!!::class.java]?.toList() ?: emptyList()
        }
        handlers.forEach { handler ->
            try {
                handler(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            subscribers.clear()
        }
    }
}

/**
 * Logger utility
 */
object SDKLogger {
    private var minLevel = LogLevel.INFO
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logCallback: ((level: String, tag: String, message: String) -> Unit)? = null

    fun setLogLevel(level: LogLevel) {
        minLevel = level
    }

    fun setLogCallback(callback: ((level: String, tag: String, message: String) -> Unit)?) {
        logCallback = callback
    }

    fun debug(tag: String, message: String, throwable: Throwable? = null) {
//        if (LogLevel.DEBUG.ordinal >= minLevel.ordinal) {
        log("D", tag, message, throwable)
//        }
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) {
        if (LogLevel.INFO.ordinal >= minLevel.ordinal) {
            log("I", tag, message, throwable)
        }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (LogLevel.WARNING.ordinal >= minLevel.ordinal) {
            log("W", tag, message, throwable)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (LogLevel.ERROR.ordinal >= minLevel.ordinal) {
            log("E", tag, message, throwable)
        }
    }

    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logMessage = "[$timestamp] $level/$tag: $message"

        // Android Log
        when (level) {
            "D" -> Log.d(tag, message)
            "I" -> Log.i(tag, message)
            "W" -> Log.w(tag, message, throwable)
            "E" -> Log.e(tag, message, throwable)
        }

        // Console output
//        println(logMessage)

        // Custom callback for analytics/remote logging
        try {
            logCallback?.invoke(level, tag, message)
        } catch (e: Exception) {
            Log.e("SDKLogger", "Error in log callback", e)
        }
    }
}
/**
 * Validation utilities
 */
object ValidationUtils {
    fun isValidRoomId(roomId: Int): Boolean {
        return roomId in 1000..999_999_999
    }

    fun isValidUserId(userId: String): Boolean {
        return userId.isNotEmpty() && userId.length <= 64
    }

    fun isValidDisplayName(displayName: String): Boolean {
        return displayName.isNotEmpty() && displayName.length <= 64
    }

    fun isValidServerUrl(url: String): Boolean {
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    fun isValidVideoBitrate(bitrate: Int): Boolean {
        return bitrate in 100..8000
    }

    fun isValidVideoResolution(width: Int, height: Int): Boolean {
        return width in 240..4096 && height in 240..4096
    }
}

/**
 * Configuration validator
 */
object ConfigurationValidator {
    fun validate(config: JanusSDKConfig): List<String> {
        val errors = mutableListOf<String>()

        if (!ValidationUtils.isValidServerUrl(config.janusServerUrl)) {
            errors.add("Invalid Janus server URL. Must start with ws:// or wss://")
        }

        if (config.stunServers.isEmpty()) {
            errors.add("At least one STUN server is required")
        }

        if (!ValidationUtils.isValidVideoBitrate(config.videoBitrate)) {
            errors.add("Video bitrate must be between 100-8000 kbps")
        }

        if (!ValidationUtils.isValidVideoResolution(config.videoWidth, config.videoHeight)) {
            errors.add("Invalid video resolution")
        }

        return errors
    }

    fun validateAndThrow(config: JanusSDKConfig) {
        val errors = validate(config)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("Invalid SDK configuration:\n" + errors.joinToString("\n"))
        }
    }
}

/**
 * Permission helper
 */
object PermissionHelper {
    fun getRequiredPermissions(): List<String> {
        return listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    fun hasPermission(context: android.content.Context, permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasAllPermissions(context: android.content.Context): Boolean {
        return getRequiredPermissions().all { hasPermission(context, it) }
    }
}

/**
 * Time utilities
 */
object TimeUtils {
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds % 60)
            else -> String.format("%02d s", seconds)
        }
    }

    fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }
}

/**
 * Memory and performance utilities
 */
object PerformanceUtils {
    fun getAvailableMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return (maxMemory - usedMemory) / (1024 * 1024)
    }

    fun getMemoryUsagePercentage(): Float {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return (usedMemory.toFloat() / maxMemory) * 100
    }

    fun isLowMemory(): Boolean {
        return getAvailableMemoryMb() < 50
    }
}

fun consoleLogE(vararg msg:String){
    Log.e("TAG", msg.joinToString(" | "))
}


object SDKUtils{
    fun randomString(length: Int): String {
        val str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = Random()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(str[random.nextInt(str.length)])
        }
        return sb.toString()
    }

    fun getRandomId(): Int {
        return (100_000_000..999_999_999).random()
    }
}

enum class JanusPlugin(val pluginName: String) {
    VIDEOROOM("janus.plugin.videoroom")
}