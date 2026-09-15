package com.example.janus.client.participant

import com.example.janus.client.UserRole
import com.example.janus.client.track.AudioTrack
import com.example.janus.client.track.Track
import com.example.janus.client.track.TrackPublication
import com.example.janus.client.track.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Base Participant representing a room member, following the LiveKit participant pattern.
 */
abstract class Participant(
    open val identity: String,
    open var name: String,
    open val role: UserRole = UserRole.GUEST
) {
    protected val trackMap = ConcurrentHashMap<String, TrackPublication>()
    protected val _tracks = MutableStateFlow<Map<String, TrackPublication>>(emptyMap())
    val tracks: StateFlow<Map<String, TrackPublication>> = _tracks.asStateFlow()

    protected val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    val audioTracks: List<TrackPublication>
        get() = trackMap.values.filter { it.kind == Track.Kind.AUDIO }

    val videoTracks: List<TrackPublication>
        get() = trackMap.values.filter { it.kind == Track.Kind.VIDEO }

    fun getTrackPublication(sid: String): TrackPublication? = trackMap[sid]

    fun isHost(): Boolean = role == UserRole.HOST

    internal fun syncTracks() {
        _tracks.value = trackMap.toMap()
    }
}
