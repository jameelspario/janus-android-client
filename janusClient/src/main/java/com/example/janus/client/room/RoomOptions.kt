package com.example.janus.client.room

import org.webrtc.EglBase
import org.webrtc.PeerConnection
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
    val requirePrivateId: Boolean = true
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
