package com.example.janus.client

import android.content.Context
import com.example.janus.client.room.Room
import com.example.janus.client.room.RoomOptions

/**
 * Entry point factory for creating LiveKit-style Janus [Room] instances.
 *
 * Example usage:
 * ```kotlin
 * val room = Janus.create(context, RoomOptions(eglBaseContext = eglBase.eglBaseContext))
 *
 * lifecycleScope.launch {
 *     room.events.collect { event ->
 *         when (event) {
 *             is RoomEvent.Connected -> ...
 *             is RoomEvent.TrackSubscribed -> ...
 *         }
 *     }
 * }
 *
 * room.connect("wss://janus.conf.meetecho.com/ws", roomId = 1234, userId = "user1", displayName = "Alice")
 * ```
 */
object Janus {
    fun create(
        context: Context,
        options: RoomOptions = RoomOptions()
    ): Room {
        return Room(context, options)
    }
}
