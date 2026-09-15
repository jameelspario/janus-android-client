package com.example.janus.client.signaling

import com.example.janus.client.SDKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coroutine-based KeepAlive scheduler for keeping the Janus session active.
 */
class KeepAliveManager(
    private val intervalMs: Long = 25_000L,
    private val onKeepAlive: suspend () -> Unit
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    delay(intervalMs)
                    try {
                        onKeepAlive()
                    } catch (e: Exception) {
                        SDKLogger.warn("KeepAliveManager", "Keep-alive ping failed: ${e.message}")
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Normal job cancellation
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
