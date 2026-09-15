package com.example.janus.client.room

import com.example.janus.client.participant.Participant
import com.example.janus.client.participant.RemoteParticipant
import com.example.janus.client.track.RemoteTrackPublication
import com.example.janus.client.track.Track
import com.example.janus.client.track.TrackPublication

/**
 * Optional listener interface mirroring [RoomEvent] for non-coroutine or traditional callback usage.
 */
interface RoomListener {
    fun onConnected(room: Room) {}
    fun onDisconnected(room: Room, error: Throwable?) {}
    fun onReconnecting(room: Room) {}
    fun onReconnected(room: Room) {}
    fun onFailedToConnect(room: Room, error: Throwable) {}

    fun onParticipantConnected(room: Room, participant: RemoteParticipant) {}
    fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}

    fun onTrackPublished(participant: Participant, publication: TrackPublication) {}
    fun onTrackUnpublished(participant: Participant, publication: TrackPublication) {}

    fun onTrackSubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant
    ) {}

    fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant
    ) {}

    fun onTrackMuted(participant: Participant, publication: TrackPublication) {}
    fun onTrackUnmuted(participant: Participant, publication: TrackPublication) {}
}
