package com.example.janus.client.participant

import com.example.janus.client.UserRole
import com.example.janus.client.track.RemoteAudioTrack
import com.example.janus.client.track.RemoteTrackPublication
import com.example.janus.client.track.RemoteVideoTrack
import com.example.janus.client.track.Track
import java.math.BigInteger

/**
 * Represents a remote participant in the Janus VideoRoom.
 */
class RemoteParticipant(
    val feedId: BigInteger,
    identity: String,
    name: String,
    role: UserRole = UserRole.GUEST
) : Participant(identity, name, role) {

    val videoTrack: RemoteVideoTrack?
        get() = videoTracks.firstOrNull()?.track as? RemoteVideoTrack

    val audioTrack: RemoteAudioTrack?
        get() = audioTracks.firstOrNull()?.track as? RemoteAudioTrack

    internal fun addPublication(publication: RemoteTrackPublication) {
        trackMap[publication.sid] = publication
        syncTracks()
    }

    internal fun removePublication(sid: String): RemoteTrackPublication? {
        val pub = trackMap.remove(sid) as? RemoteTrackPublication
        syncTracks()
        return pub
    }

    internal fun setTrackForPublication(sid: String, track: Track) {
        val pub = trackMap[sid] as? RemoteTrackPublication
        if (pub != null) {
            pub.track = track
            syncTracks()
        }
    }
}
