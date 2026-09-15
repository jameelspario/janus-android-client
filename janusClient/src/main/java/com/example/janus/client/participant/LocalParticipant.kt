package com.example.janus.client.participant

import com.example.janus.client.UserRole
import com.example.janus.client.track.LocalAudioTrack
import com.example.janus.client.track.LocalTrackPublication
import com.example.janus.client.track.LocalVideoTrack
import com.example.janus.client.track.Track

/**
 * Controller callback for [LocalParticipant] track actions handled by the Room/Engine.
 */
interface LocalParticipantController {
    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setCameraEnabled(enabled: Boolean)
    fun switchCamera()
}

/**
 * Represents the local user in the room, containing control functions for camera & microphone.
 */
class LocalParticipant(
    identity: String,
    name: String,
    role: UserRole = UserRole.GUEST,
    private val controller: LocalParticipantController
) : Participant(identity, name, role) {

    var isMicrophoneEnabled: Boolean = false
        internal set

    var isCameraEnabled: Boolean = false
        internal set

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        controller.setMicrophoneEnabled(enabled)
        isMicrophoneEnabled = enabled
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        controller.setCameraEnabled(enabled)
        isCameraEnabled = enabled
    }

    fun switchCamera() {
        controller.switchCamera()
    }

    internal fun addPublication(publication: LocalTrackPublication) {
        trackMap[publication.sid] = publication
        syncTracks()
    }

    internal fun removePublication(sid: String): LocalTrackPublication? {
        val pub = trackMap.remove(sid) as? LocalTrackPublication
        syncTracks()
        return pub
    }

    internal fun clearPublications() {
        trackMap.clear()
        syncTracks()
    }
}
