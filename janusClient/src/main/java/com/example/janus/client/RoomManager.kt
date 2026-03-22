package com.example.janus.client

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages room lifecycle and participant operations
 */
class RoomManager(private val signalingManager: SignalingManager) {
    private val rooms = ConcurrentHashMap<Int, JanusRoom>()
    private var listener: JanusRoomEventListener? = null
    private val currentUser = mutableMapOf<Int, JanusParticipant>()

    private var privateId:Int? = null
    /**
     * Set event listener
     */
    fun setEventListener(listener: JanusRoomEventListener) {
        this.listener = listener
    }

    /**
     * Join (or create+join) a room with proper role-based behavior
     *
     * @param roomId Unique room identifier (usually numeric string in Janus)
     * @param userId Application-level user identifier
     * @param userRole HOST or GUEST
     * @param displayName Name shown to other participants
     * @param roomOptions Optional creation parameters (only used when user is HOST and room doesn't exist)
     * @return true if join/create succeeded (or at least the request was sent correctly)
     */
    fun joinRoom(
        roomId: Int,
        userId: String,
        userRole: UserRole,
        displayName: String,
        roomOptions: RoomCreateOptions? = null
    ): Boolean {
        if (!ValidationUtils.isValidRoomId(roomId)) {
            consoleLogE("RoomManager", "Invalid room ID format: $roomId")
            listener?.onError("Invalid room ID")
            return false
        }

        if (!ValidationUtils.isValidDisplayName(displayName)) {
            consoleLogE("RoomManager", "Invalid display name: $displayName")
            listener?.onError("Display name too short or invalid")
            return false
        }

        consoleLogE("RoomManager", "Attempting to join room $roomId as $userRole - $displayName")

        val participant = JanusParticipant(
            participantId = "",           // will be filled by Janus later
            userId = userId,
            displayName = displayName,
            userRole = userRole,
            joinedAt = System.currentTimeMillis()
        )

        // ──────────────────────────────────────────────────────────────
        //  Different flow for HOST vs GUEST
        // ──────────────────────────────────────────────────────────────
        return when (userRole) {
            UserRole.HOST -> handleHostJoinOrCreate(roomId, participant, roomOptions)
            UserRole.GUEST -> handleGuestJoin(roomId, participant)
        }
    }

    /**
     * Logic for HOST: try to create room if it doesn't exist, then join
     */
    private fun handleHostJoinOrCreate(
        roomId: Int,
        participant: JanusParticipant,
        roomOptions: RoomCreateOptions?
    ): Boolean {
        // First try to join (most common case - room already exists)
        sendJoinRequest(roomId, participant) { success, errorMessage, janusRoomId, ownParticipantId ->

            if (success) {
                // Room existed → joined successfully
                onSuccessfulJoin(roomId, participant, janusRoomId, ownParticipantId, isNewRoom = false)
                return@sendJoinRequest
            }

            // Join failed → check if it's because room doesn't exist
            if (errorMessage?.contains("Room", ignoreCase = true) == true) {
                // Try to create the room
                createRoomAndThenJoin(roomId, participant, roomOptions)
            } else {
                // Some other error (permission, wrong pin, etc.)
                consoleLogE("RoomManager", "Join failed and not because room missing: $errorMessage")
                listener?.onError("Cannot join room: $errorMessage")
            }
        }

        return true  // request sent
    }

    /**
     * Logic for GUEST: only join, never create
     */
    private fun handleGuestJoin(
        roomId: Int,
        participant: JanusParticipant
    ): Boolean {
        sendJoinRequest(roomId, participant) { success, errorMessage, janusRoomId, ownParticipantId ->

            if (success) {
                onSuccessfulJoin(roomId, participant, janusRoomId, ownParticipantId, isNewRoom = false)
            } else {
                val msg = errorMessage ?: "Unknown error"
                consoleLogE("RoomManager", "Guest join failed: $msg")
                listener?.onError("Cannot join room: $msg")
            }
        }

        return true  // request sent
    }

    /**
     * Sends "join" request to Janus VideoRoom plugin
     */
    private fun sendJoinRequest(
        roomId: Int,
        participant: JanusParticipant,
        onResult: (Boolean, String?, Int?, String?) -> Unit
    ) {
        val body = mapOf<String, Any>(
            "request" to "join",
            "room" to roomId,           // Janus usually expects numeric room ID
            "ptype" to "publisher",                    // most common case
            "display" to participant.displayName
        )

        // Optional: add pin, token, etc. if your app uses them
        // body["pin"] = "..."

        signalingManager.sendMessage(
            body = body,
            callback = { response, _ ->

                val janus = response["videoroom"] as? String
                when (janus) {
                    "event" -> {
                        val error = response["error"] as? String
                        val errorCode = response["error_code"] as? Int
                        if (errorCode == 426) {
                            onResult(false, "Missing id/room in success response", null, null)
                        }else{
                            onResult(false, "$error: $errorCode", null, null)
                        }
                    }
                    "joined" ->{
                        val participantId = response["id"]?.toString()
                        val roomFromServer = response["room"] as? Int
                        privateId = response["private_id"] as? Int
                        onResult(true, null, roomFromServer, participantId)
                    }
                    "error" -> {
                        val err = response["error"] as? String ?: "Unknown Janus error"
                        onResult(false, err, null, null)
                    }
                    else -> {
                        onResult(false, "Unexpected response type: $janus", null, null)
                    }
                }
            }
        )
    }

    /**
     * HOST-only: create room → then join it
     */
    private fun createRoomAndThenJoin(
        roomId: Int,
        participant: JanusParticipant,
        roomOptions: RoomCreateOptions?
    ) {
        val createBody = mapOf<String, Any>(
            "request" to "create",
            "room" to roomId,
            "ptype" to "publisher",
            "display" to participant.displayName,
            "description" to (roomOptions?.description ?: "Room $roomId"),
            "publishers" to (roomOptions?.maxPublishers ?: 100000),
            "bitrate" to (roomOptions?.videoBitrate ?: 2000000),
            "fir_freq" to 10,
            "require_pvtid" to true   // good default for modern apps
            // "pin": "...", "secret": "...", etc.
        )

        signalingManager.sendMessage(
            body = createBody,
            callback = { response, _ ->
                consoleLogE("-- $response")

                val janus = response["videoroom"] as? String

                if (janus == "created") {
                    consoleLogE("RoomManager", "Room $roomId created successfully → now joining")

                    // Now try to join the freshly created room
                    sendJoinRequest(roomId, participant) { success, err, r, pId ->
                        if (success) {
                            onSuccessfulJoin(roomId, participant, r, pId, isNewRoom = true)
                        } else {
                            listener?.onError("Room created but join failed: ${err ?: "unknown"}")
                        }
                    }
                } else {
                    val err = response["error"] as? String ?: "Unknown create error"
                    consoleLogE("RoomManager", "Failed to create room $roomId : $err")
                    listener?.onError("Failed to create room: $err")
                }
            }
        )
    }

    /**
     * Common success path after join
     */
    private fun onSuccessfulJoin(
        roomId: Int,
        participant: JanusParticipant,
        serverRoomId: Int?,
        participantServerId: String?,
        isNewRoom: Boolean
    ) {
        val finalRoomId = serverRoomId ?: roomId

        // Update participant with real Janus ID
        val updatedParticipant = participant.copy(
            participantId = participantServerId ?: participant.participantId
        )

        val room = rooms.getOrPut(finalRoomId) {
            JanusRoom(
                roomId = finalRoomId,
                roomName = finalRoomId, // you may want to fetch real name later
                maxParticipants = 100,  // update later if needed
                host = if (participant.userRole == UserRole.HOST) updatedParticipant else null
            )
        }

        // Add participant
        if (!room.participants.any { it.participantId == updatedParticipant.participantId }) {
            room.participants.add(updatedParticipant)
        }

        if (participant.userRole == UserRole.HOST) {
            room.host = updatedParticipant
        }

        currentUser[finalRoomId] = updatedParticipant

        consoleLogE("RoomManager", "Successfully ${if (isNewRoom) "created & joined" else "joined"} room $finalRoomId")

        listener?.onRoomJoined(room)
        broadcastParticipantJoined(finalRoomId, updatedParticipant)
    }

    /*********************************************
    * Leave Room
    **********************************************/

    /**
     * ENHANCED: Leave Room Implementation
     * This file contains the enhanced leaveRoom functionality with proper Janus server communication
     */

    /**
     * Enhanced leaveRoom function with Janus server communication
     *
     * Flow:
     * 1. Verify room exists
     * 2. Get current user/participant
     * 3. Unpublish all streams from this room
     * 4. Unsubscribe from all remote streams
     * 5. Send "leave" request to Janus server
     * 6. Wait for server confirmation
     * 7. Clean up local state
     * 8. Notify listeners
     */
    fun leaveRoomEnhanced(
        roomId: Int,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        return try {
            consoleLogE("RoomManager", "=== Starting enhanced leave room flow for room: $roomId ===")

            // Step 1: Verify room exists
            val room = getRoom(roomId)
            if (room == null) {
                consoleLogE("RoomManager", "Room not found: $roomId")
                onError("Room not found")
                return false
            }

            // Step 2: Get current user in this room
            val currentUser = getCurrentUser(roomId)
            if (currentUser == null) {
                consoleLogE("RoomManager", "Current user not found in room: $roomId")
                onError("Not in room")
                return false
            }

            consoleLogE("RoomManager", "Current user: ${currentUser.displayName} (${currentUser.participantId})")

            // Step 3 & 4: Unpublish and unsubscribe (requires stream manager access)
            // Note: This should be called from JanusSDK level
            consoleLogE("RoomManager", "Streams should be cleaned up before calling leaveRoom")

            // Step 5: Send leave request to Janus server
            leaveRoom(roomId) { success, errorMsg ->
                if (success) {
                    consoleLogE("RoomManager", "Server accepted leave request")

                    // Step 6: Clean up local state
                    cleanupLocalRoomState(roomId, currentUser)

                    // Step 7: Notify listeners
                    notifyRoomLeft(roomId)

                    consoleLogE("RoomManager", "=== Leave room completed successfully ===")
                    onSuccess()
                } else {
                    val error = errorMsg ?: "Unknown error during leave"
                    consoleLogE("RoomManager", "Leave request failed: $error")

                    // Still cleanup locally even if server request failed
                    cleanupLocalRoomState(roomId, currentUser)
                    onError(error)
                }
            }

            true // Request sent
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Leave room error: ${e.message}")
            e.printStackTrace()
            onError("Exception: ${e.message}")
            false
        }
    }

    /**
     * Send "leave" request to Janus VideoRoom plugin
     *
     * @param roomId The room to leave
     * @param participant Current participant info
     * @param callback Callback with (success, errorMessage)
     */
    fun leaveRoom(
        roomId: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        val body = mapOf<String, Any>(
            "request" to "leave",
            "room" to roomId
            // Optionally include participant ID if your Janus setup requires it
            // "id" to (participant.participantId.toLongOrNull() ?: 0)
        )

        try {
            signalingManager.sendMessage(
                body = body,
                callback = { response, _ ->
                    handleLeaveResponse(response, roomId, callback)
                }
            )
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Failed to send leave request: ${e.message}")
            callback(false, e.message)
        }
    }

    /**
     * Handle the response from Janus server to the leave request
     */
    private fun handleLeaveResponse(
        response: Map<String, Any>,
        roomId: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        consoleLogE("--- $response")
        try {
            val janus = response["videoroom"] as? String

            when (janus) {
                "success" -> {
                    consoleLogE("RoomManager", "Leave request successful")
                    callback(true, null)
                }
                "event" -> {
                    // Event response - check for errors
                    val error = response["error"] as? String
                    val errorCode = response["error_code"] as? Int

                    if (errorCode != null) {
                        val errorMsg = when (errorCode) {
                            426 -> "Missing room ID in leave request"
                            427, 454 -> "Room not found (may already be left)"
                            456 -> "Not authorized to leave this room"
                            else -> "Server error: $error ($errorCode)"
                        }
                        consoleLogE("RoomManager", "Leave event error: $errorMsg")
                        // Still consider this success since room/participant may not exist
                        callback(true, null)
                    } else {
                        consoleLogE("RoomManager", "Leave event success")
                        callback(true, null)
                    }
                }
                "error" -> {
                    val error = response["error"] as? String ?: "Unknown error"
                    consoleLogE("RoomManager", "Leave request error: $error")
                    callback(false, error)
                }
                else -> {
                    consoleLogE("RoomManager", "Unexpected response type: $janus")
                    callback(false, "Unexpected response: $janus")
                }
            }
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Error handling leave response: ${e.message}")
            callback(false, e.message)
        }
    }

    /**
     * Clean up local state after successful leave
     */
    private fun cleanupLocalRoomState(
        roomId: Int,
        participant: JanusParticipant
    ) {
        try {
            // Remove current user
            currentUser.remove(roomId)

            // Get room and remove participant
            val room = rooms[roomId]
            if (room != null) {
                room.participants.remove(participant)

                // If room is now empty, remove it
                if (room.participants.isEmpty()) {
                    rooms.remove(roomId)
                    consoleLogE("RoomManager", "Room removed (empty): $roomId")
                } else {
                    // Mark room as inactive if we were the host
                    if (participant.isHost()) {
                        room.isActive = false
                        room.host = null
                        consoleLogE("RoomManager", "Room marked inactive (host left): $roomId")
                    }
                }
            }

            consoleLogE("RoomManager", "Local state cleaned up for room: $roomId")
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Error cleaning up local state: ${e.message}")
        }
    }

    /**
     * Notify listeners about room left event
     */
    private fun notifyRoomLeft(roomId: Int) {
        try {
            listener?.onRoomLeft(roomId)
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Error notifying listener: ${e.message}")
        }
    }

// ════════════════════════════════════════════════════════════════════════════════
// HELPER EXTENSION: For complete room exit with stream cleanup
// ════════════════════════════════════════════════════════════════════════════════

    /**
     * Complete leave room flow including stream cleanup
     * This should be called from JanusSDK or higher level
     *
     * @param roomId Room to leave
     * @param streamManager Stream manager for cleanup
     * @param onComplete Callback when complete
     */
    fun leaveRoomComplete(
        roomId: Int,
        streamManager: StreamManager,
        onComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }
    ) {
        consoleLogE("RoomManager", "Starting complete room leave for: $roomId")

        try {
            // Step 1: Unpublish local stream if publishing
            streamManager.getStreamsForRoom(roomId).forEach { streamInfo ->
                if (streamInfo.isPublisher) {
                    consoleLogE("RoomManager", "Unpublishing stream: ${streamInfo.streamId}")
                    streamManager.unpublishStream(roomId)
                }
            }

            // Step 2: Unsubscribe from all remote streams
            streamManager.getStreamsForRoom(roomId).forEach { streamInfo ->
                if (!streamInfo.isPublisher && streamInfo.participantId != null) {
                    consoleLogE("RoomManager", "Unsubscribing from stream: ${streamInfo.streamId}")
                    streamManager.unsubscribeFromStream(roomId, streamInfo.participantId)
                }
            }

            // Step 3: Send leave to server
            leaveRoomEnhanced(
                roomId,
                onSuccess = {
                    onComplete(true, "Room left successfully")
                },
                onError = { error ->
                    onComplete(false, error)
                }
            )
        } catch (e: Exception) {
            consoleLogE("RoomManager", "Complete leave failed: ${e.message}")
            onComplete(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Get room by ID
     */
    fun getRoom(roomId: Int): JanusRoom? = rooms[roomId]

    /**
     * Get all active rooms
     */
    fun getAllRooms(): List<JanusRoom> = rooms.values.filter { it.isActive }

    /**
     * Get participants in a room
     */
    fun getParticipants(roomId: Int): List<JanusParticipant> =
        rooms[roomId]?.participants?.toList() ?: emptyList()

    /**
     * Get participant by ID
     */
    fun getParticipant(roomId: Int, participantId: String): JanusParticipant? =
        rooms[roomId]?.participants?.find { it.participantId == participantId }

    /**
     * Get host of a room
     */
    fun getHost(roomId: Int): JanusParticipant? = rooms[roomId]?.host

    /**
     * Update participant info
     */
    fun updateParticipant(roomId: Int, participantId: String, updates: (JanusParticipant) -> Unit) {
        val participant = getParticipant(roomId, participantId)
        if (participant != null) {
            updates(participant)
        }
    }

    /**
     * Get current user in a specific room
     */
    fun getCurrentUser(roomId: Int): JanusParticipant? = currentUser[roomId]

    /**
     * Handle signaling messages related to rooms
     */
    fun handleSignalingMessage(message: Map<String, Any>) {
        try {
            val eventType = message["event"] as? String ?: return

            when (eventType) {
                "participant_joined" -> {
                    val roomId = message["room"] as? Int
                    val participantId = message["participant_id"] as? String
                    val displayName = message["display_name"] as? String
                    val userId = message["user_id"] as? String

                    if (roomId != null && participantId != null && displayName != null && userId != null) {
                        val participant = JanusParticipant(
                            participantId = participantId,
                            userId = userId,
                            displayName = displayName
                        )
                        rooms[roomId]?.participants?.add(participant)
                        listener?.onParticipantJoined(participant)
                    }
                }

                "participant_left" -> {
                    val roomId = message["room"] as? Int
                    val participantId = message["participant_id"] as? String

                    if (roomId != null && participantId != null) {
                        rooms[roomId]?.participants?.removeAll { it.participantId == participantId }
                        listener?.onParticipantLeft(participantId)
                    }
                }

                "room_closed" -> {
                    val roomId = message["room"] as? Int
                    if (roomId != null) {
                        val room = rooms[roomId]
                        if (room != null) {
                            room.isActive = false
                            listener?.onRoomLeft(roomId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            consoleLogE("JanusRoomManager", "Message handling error: ${e.message}")
        }
    }

    /**
     * Broadcast participant joined event
     */
    private fun broadcastParticipantJoined(roomId: Int, participant: JanusParticipant) {
        val room = rooms[roomId] ?: return
        room.participants.forEach { p ->
            if (p.participantId != participant.participantId) {
                listener?.onParticipantJoined(participant)
            }
        }
    }

    /**
     * Broadcast participant left event
     */
    private fun broadcastParticipantLeft(roomId: Int, participantId: String) {
        listener?.onParticipantLeft(participantId)
    }

    /**
     * Broadcast room closed event
     */
    private fun broadcastRoomClosed(roomId: Int) {
        listener?.onRoomLeft(roomId)
    }

    /**
     * Generate unique participant ID
     */
    private fun generateParticipantId(): String = "participant-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"

    /**
     * Clear all rooms
     */
    fun clearAll() {
        rooms.clear()
        currentUser.clear()
    }
}

/**
 * Room event listener interface
 */
interface JanusRoomEventListener {
    fun onRoomJoined(room: JanusRoom)
    fun onRoomLeft(roomId: Int)
    fun onParticipantJoined(participant: JanusParticipant)
    fun onParticipantLeft(participantId: String)
    fun onError(error: String)
}

data class RoomCreateOptions(
    val description: String? = null,
    val maxPublishers: Int = 20,
    val videoBitrate: Int = 2_000_000,
    val requirePrivateId: Boolean = true
    // pin, secret, allowed codecs, etc.
)


// ════════════════════════════════════════════════════════════════════════════════
// DATA CLASSES FOR LEAVE OPERATIONS
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Result of leave room operation
 */
data class LeaveRoomResult(
    val success: Boolean,
    val roomId: Int,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Leave room operation options
 */
data class LeaveRoomOptions(
    val gracefulShutdown: Boolean = true,
    val notifyOthers: Boolean = true,
    val clearState: Boolean = true,
    val timeoutMs: Long = 5000
)