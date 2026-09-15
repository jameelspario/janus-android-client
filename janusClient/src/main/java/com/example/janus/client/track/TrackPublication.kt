package com.example.janus.client.track

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a published track in the room, following the LiveKit publication model.
 */
abstract class TrackPublication(
    open val sid: String,
    open val kind: Track.Kind,
    open val track: Track?
) {
    protected val _isMuted = MutableStateFlow(track?.isMuted?.value ?: false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    open val isSubscribed: Boolean
        get() = track != null

    internal fun updateMuted(muted: Boolean) {
        _isMuted.value = muted
    }
}

/**
 * Publication of a locally published track.
 */
class LocalTrackPublication(
    override val sid: String,
    override val kind: Track.Kind,
    override val track: Track
) : TrackPublication(sid, kind, track)

/**
 * Publication of a remote participant's track.
 */
class RemoteTrackPublication(
    override val sid: String,
    override val kind: Track.Kind,
    track: Track? = null
) : TrackPublication(sid, kind, track) {

    private val _trackFlow = MutableStateFlow(track)
    val trackFlow: StateFlow<Track?> = _trackFlow.asStateFlow()

    override var track: Track? = track
        internal set(value) {
            field = value
            _trackFlow.value = value
            if (value != null) {
                _isMuted.value = value.isMuted.value
            }
        }

    val videoTrack: RemoteVideoTrack?
        get() = track as? RemoteVideoTrack

    val audioTrack: RemoteAudioTrack?
        get() = track as? RemoteAudioTrack

    override val isSubscribed: Boolean
        get() = track != null
}
