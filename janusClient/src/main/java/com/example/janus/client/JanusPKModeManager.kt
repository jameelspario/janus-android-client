package com.example.janus.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages PK (Player Kill/Battle) mode between two rooms
 */
class JanusPKModeManager(private val roomManager: RoomManager) {
    private var currentPKMode: PKModeConfig? = null
    private val pkModeActive = AtomicBoolean(false)
    private var pkTimer: Thread? = null
    private val listeners = ConcurrentHashMap<String, PKModeListener>()

    /**
     * Start PK mode between two rooms
     */
    fun startPKMode(
        roomId1: Int,
        roomId2: Int,
        pkDurationMs: Long = 0
    ): Boolean {
        return try {
            // Check if rooms exist
            val room1 = roomManager.getRoom(roomId1)
            val room2 = roomManager.getRoom(roomId2)

            if (room1 == null || room2 == null) {
                consoleLogE("JanusPKModeManager", "One or both rooms not found")
                return false
            }

            // Check if rooms are active and have hosts
            if (!room1.isActive || !room2.isActive) {
                consoleLogE("JanusPKModeManager", "One or both rooms are not active")
                return false
            }

            if (room1.host == null || room2.host == null) {
                consoleLogE("JanusPKModeManager", "One or both rooms don't have hosts")
                return false
            }

            // Stop any existing PK mode
            stopPKMode()

            // Create PK mode configuration
            currentPKMode = PKModeConfig(
                pkId = generatePKId(),
                room1Id = roomId1,
                room2Id = roomId2,
                room1Name = room1.roomName.toString(),
                room2Name = room2.roomName.toString(),
                startTime = System.currentTimeMillis(),
                durationMs = pkDurationMs,
                isActive = true,
                createdBy = room1.host?.userId ?: ""
            )

            pkModeActive.set(true)
            consoleLogE("JanusPKModeManager", "PK mode started: ${currentPKMode?.pkId}")

            // Notify listeners
            notifyPKStarted(currentPKMode!!)

            // Start duration timer if specified
            if (pkDurationMs > 0) {
                startPKTimer(pkDurationMs)
            }

            true
        } catch (e: Exception) {
            consoleLogE("JanusPKModeManager", "Start PK mode failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Stop PK mode
     */
    fun stopPKMode(): Boolean {
        return try {
            if (!pkModeActive.getAndSet(false)) {
                return false
            }

            val pkMode = currentPKMode
            if (pkMode != null) {
                consoleLogE("JanusPKModeManager", "PK mode stopped: ${pkMode.pkId}")
                currentPKMode = null

                // Cancel timer if running
                pkTimer?.interrupt()
                pkTimer = null

                // Notify listeners
                notifyPKStopped(pkMode)
            }

            true
        } catch (e: Exception) {
            consoleLogE("JanusPKModeManager", "Stop PK mode error: ${e.message}")
            false
        }
    }

    /**
     * Get current PK mode
     */
    fun getCurrentPKMode(): PKModeConfig? = currentPKMode

    /**
     * Check if PK mode is active
     */
    fun isPKModeActive(): Boolean = pkModeActive.get() && currentPKMode?.isActive == true

    /**
     * Get PK mode status
     */
    fun getPKModeStatus(): PKModeStatus? {
        val pkMode = currentPKMode ?: return null

        val room1 = roomManager.getRoom(pkMode.room1Id)
        val room2 = roomManager.getRoom(pkMode.room2Id)

        if (room1 == null || room2 == null) return null

        return PKModeStatus(
            pkId = pkMode.pkId,
            room1Id = pkMode.room1Id,
            room1Name = pkMode.room1Name,
            room1ParticipantCount = room1.getParticipantCount(),
            room1Host = room1.host?.displayName ?: "Unknown",
            room2Id = pkMode.room2Id,
            room2Name = pkMode.room2Name,
            room2ParticipantCount = room2.getParticipantCount(),
            room2Host = room2.host?.displayName ?: "Unknown",
            startTime = pkMode.startTime,
            durationMs = pkMode.durationMs,
            elapsedMs = System.currentTimeMillis() - pkMode.startTime,
            isActive = pkModeActive.get()
        )
    }

    /**
     * Register PK mode listener
     */
    fun registerPKModeListener(listenerId: String, listener: PKModeListener) {
        listeners[listenerId] = listener
    }

    /**
     * Unregister PK mode listener
     */
    fun unregisterPKModeListener(listenerId: String) {
        listeners.remove(listenerId)
    }

    /**
     * Get participating rooms in PK mode
     */
    fun getPKRooms(): Pair<JanusRoom?, JanusRoom?>? {
        val pkMode = currentPKMode ?: return null
        return Pair(
            roomManager.getRoom(pkMode.room1Id),
            roomManager.getRoom(pkMode.room2Id)
        )
    }

    /**
     * Get PK mode statistics
     */
    fun getPKModeStatistics(): PKModeStatistics {
        val pkMode = currentPKMode
        if (pkMode != null && isPKModeActive()) {
            val room1 = roomManager.getRoom(pkMode.room1Id)
            val room2 = roomManager.getRoom(pkMode.room2Id)

            return PKModeStatistics(
                pkId = pkMode.pkId,
                room1Id = pkMode.room1Id,
                room1ParticipantCount = room1?.getParticipantCount() ?: 0,
                room1PublishingCount = room1?.getPublishingParticipants()?.size ?: 0,
                room1SubscribingCount = room1?.getSubscribingParticipants()?.size ?: 0,
                room2Id = pkMode.room2Id,
                room2ParticipantCount = room2?.getParticipantCount() ?: 0,
                room2PublishingCount = room2?.getPublishingParticipants()?.size ?: 0,
                room2SubscribingCount = room2?.getSubscribingParticipants()?.size ?: 0,
                startTime = pkMode.startTime,
                elapsedMs = System.currentTimeMillis() - pkMode.startTime,
                isActive = true
            )
        }

        return PKModeStatistics()
    }

    /**
     * Start auto-stop timer
     */
    private fun startPKTimer(durationMs: Long) {
        pkTimer = Thread {
            try {
                Thread.sleep(durationMs)
                if (pkModeActive.get()) {
                    stopPKMode()
                    consoleLogE("JanusPKModeManager", "PK mode auto-stopped after timeout")
                }
            } catch (e: InterruptedException) {
                consoleLogE("JanusPKModeManager", "PK timer interrupted")
            }
        }.apply { isDaemon = true }
        pkTimer?.start()
    }

    /**
     * Notify PK started
     */
    private fun notifyPKStarted(pkMode: PKModeConfig) {
        listeners.values.forEach { listener ->
            try {
                listener.onPKModeStarted(pkMode)
            } catch (e: Exception) {
                consoleLogE("JanusPKModeManager", "Listener error: ${e.message}")
            }
        }
    }

    /**
     * Notify PK stopped
     */
    private fun notifyPKStopped(pkMode: PKModeConfig) {
        listeners.values.forEach { listener ->
            try {
                listener.onPKModeStopped(pkMode)
            } catch (e: Exception) {
                consoleLogE("JanusPKModeManager", "Listener error: ${e.message}")
            }
        }
    }

    /**
     * Generate unique PK ID
     */
    private fun generatePKId(): String = "pk-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
}
