package com.example.janus.client.track

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack as RtcAudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack as RtcVideoTrack
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Base Track abstraction wrapping a WebRTC [MediaStreamTrack].
 */
abstract class Track(
    val kind: Kind,
    val rtcTrack: MediaStreamTrack
) {
    enum class Kind {
        AUDIO,
        VIDEO
    }

    protected val _isMuted = MutableStateFlow(
        try { !rtcTrack.enabled() } catch (_: Exception) { false }
    )
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    open val sid: String = try {
        rtcTrack.id()
    } catch (_: Exception) {
        ""
    }

    open fun isEnabled(): Boolean = try {
        rtcTrack.enabled()
    } catch (_: Exception) {
        false
    }

    open fun setEnabled(enabled: Boolean) {
        try {
            rtcTrack.setEnabled(enabled)
            _isMuted.value = !enabled
        } catch (_: Exception) {}
    }

    open fun dispose() {
        try {
            rtcTrack.dispose()
        } catch (_: Exception) {}
    }
}

/**
 * Video track representing a local or remote video stream.
 */
abstract class VideoTrack(
    val rtcVideoTrack: RtcVideoTrack
) : Track(Kind.VIDEO, rtcVideoTrack) {

    private val sinks = CopyOnWriteArraySet<VideoSink>()

    /**
     * Attaches a [SurfaceViewRenderer] (or any [VideoSink]) to receive decoded video frames.
     */
    fun addRenderer(renderer: VideoSink) {
        if (sinks.add(renderer)) {
            try {
                rtcVideoTrack.addSink(renderer)
            } catch (_: Exception) {}
        }
    }

    /**
     * Removes a previously attached [VideoSink].
     */
    fun removeRenderer(renderer: VideoSink) {
        if (sinks.remove(renderer)) {
            try {
                rtcVideoTrack.removeSink(renderer)
            } catch (_: Exception) {}
        }
    }

    fun removeAllSinks() {
        sinks.forEach {
            try {
                rtcVideoTrack.removeSink(it)
            } catch (_: Exception) {}
        }
        sinks.clear()
    }

    override fun dispose() {
        removeAllSinks()
        super.dispose()
    }
}

/**
 * Audio track representing local microphone or remote audio.
 */
abstract class AudioTrack(
    val rtcAudioTrack: RtcAudioTrack
) : Track(Kind.AUDIO, rtcAudioTrack) {

    open fun setVolume(volume: Double) {
        try {
            rtcAudioTrack.setVolume(volume)
        } catch (_: Exception) {}
    }
}

/**
 * Local video track published by the device camera or screen share.
 */
class LocalVideoTrack(
    rtcVideoTrack: RtcVideoTrack
) : VideoTrack(rtcVideoTrack)

/**
 * Local audio track captured from the device microphone.
 */
class LocalAudioTrack(
    rtcAudioTrack: RtcAudioTrack
) : AudioTrack(rtcAudioTrack)

/**
 * Remote video track received from another participant.
 * Note: The native C++ MediaStreamTrack is owned by the subscriber PeerConnection.
 * We must only detach sinks and not call native dispose() directly to avoid native double-free (SIGABRT).
 */
class RemoteVideoTrack(
    rtcVideoTrack: RtcVideoTrack
) : VideoTrack(rtcVideoTrack) {
    override fun dispose() {
        removeAllSinks()
    }
}

/**
 * Remote audio track received from another participant.
 * The native track is disposed when the subscriber PeerConnection is disposed.
 */
class RemoteAudioTrack(
    rtcAudioTrack: RtcAudioTrack
) : AudioTrack(rtcAudioTrack) {
    override fun dispose() {
        // Owned by PeerConnection
    }
}
