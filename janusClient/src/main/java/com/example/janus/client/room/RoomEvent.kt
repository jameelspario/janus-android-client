package com.example.janus.client.room

import com.example.janus.client.participant.Participant
import com.example.janus.client.participant.RemoteParticipant
import com.example.janus.client.track.RemoteTrackPublication
import com.example.janus.client.track.Track
import com.example.janus.client.track.TrackPublication

/**
 * Sealed class representing all events emitted by a [Room], matching LiveKit's event architecture.
 * Can be observed via Kotlin Flow: `room.events.collect { event -> ... }`
 */
sealed class RoomEvent {
    data class Connected(val room: Room) : RoomEvent()
    data class Disconnected(val room: Room, val error: Throwable? = null) : RoomEvent()
    data class Reconnecting(val room: Room) : RoomEvent()
    data class Reconnected(val room: Room) : RoomEvent()
    data class FailedToConnect(val room: Room, val error: Throwable) : RoomEvent()

    data class ParticipantConnected(val room: Room, val participant: RemoteParticipant) : RoomEvent()
    data class ParticipantDisconnected(val room: Room, val participant: RemoteParticipant) : RoomEvent()

    data class TrackPublished(val participant: Participant, val publication: TrackPublication) : RoomEvent()
    data class TrackUnpublished(val participant: Participant, val publication: TrackPublication) : RoomEvent()

    data class TrackSubscribed(
        val track: Track,
        val publication: RemoteTrackPublication,
        val participant: RemoteParticipant
    ) : RoomEvent()

    data class TrackUnsubscribed(
        val track: Track,
        val publication: RemoteTrackPublication,
        val participant: RemoteParticipant
    ) : RoomEvent()

    data class TrackMuted(val participant: Participant, val publication: TrackPublication) : RoomEvent()
    data class TrackUnmuted(val participant: Participant, val publication: TrackPublication) : RoomEvent()
}
