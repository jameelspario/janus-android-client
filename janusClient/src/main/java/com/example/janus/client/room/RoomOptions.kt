package com.example.janus.client.room

import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoProcessor
import java.io.Serializable

/**
 * TURN server configuration for WebRTC connectivity.
 */
data class TurnServer(
    val url: String,
    val username: String = "",
    val credential: String = ""
) : Serializable

enum class CameraPosition {
    FRONT,
    BACK
}

/**
 * Configuration options for initializing and connecting to a [Room].
 */
data class RoomOptions(
    val eglBaseContext: EglBase.Context? = null,
    val stunServers: List<String> = listOf("stun:stun.l.google.com:19302"),
    val turnServers: List<TurnServer> = emptyList(),
    val autoSubscribe: Boolean = true,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoFps: Int = 30,
    val videoBitrate: Int = 2000,
    val defaultCameraPosition: CameraPosition = CameraPosition.FRONT,
    val keepAliveIntervalMs: Long = 25_000L,
    val connectionTimeoutMs: Long = 15_000L,
    val requirePrivateId: Boolean = true,
    /** Max publish bitrate (bps) sent with the publisher `configure` - the app's own
     * JanusService.publishOwnFeed uses 256 kbps. */
    val publishBitrate: Long = 256_000L,
    /** Max publishers when this client has to create the room (app's own value). */
    val maxPublishers: Int = 100_000,
    /** How often (ms) the room samples WebRTC stats for per-participant audio levels - the
     * app's JanusService polls every 400 ms. 0 disables sampling. */
    val audioLevelIntervalMs: Long = 400L,
    /** Audio level (0..1) above which a participant counts as speaking. */
    val speakingThreshold: Float = 0.2f,
    /** Optional processor applied to every local camera frame before it's encoded/published
     * (e.g. an app-side filter) - null publishes the camera untouched. */
    val videoProcessor: VideoProcessor? = null,
) {
    fun toIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        stunServers.forEach { url ->
            servers.add(PeerConnection.IceServer.builder(url).createIceServer())
        }
        turnServers.forEach { turn ->
            servers.add(
                PeerConnection.IceServer.builder(turn.url)
                    .setUsername(turn.username)
                    .setPassword(turn.credential)
                    .createIceServer()
            )
        }
        return servers
    }
}
