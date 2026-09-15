package com.example.janus.client.room

/**
 * Represents the current connection state of a [Room], matching LiveKit connection lifecycle.
 */
enum class ConnectionState {
    /** The room is not connected. */
    DISCONNECTED,

    /** The room is currently attempting to establish a connection. */
    CONNECTING,

    /** The room is connected and media/signaling is operational. */
    CONNECTED,

    /** The room connection was interrupted and is attempting reconnection. */
    RECONNECTING
}
