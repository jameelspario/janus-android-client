package com.example.janus.client

import android.util.Log

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/** SDK logger - Android Log output, filtered by [setLogLevel], with an optional [setLogCallback]
 * for remote logging. */
object SDKLogger {
    private var minLevel = LogLevel.DEBUG
    private var logCallback: ((level: String, tag: String, message: String) -> Unit)? = null

    fun setLogLevel(level: LogLevel) {
        minLevel = level
    }

    fun setLogCallback(callback: ((level: String, tag: String, message: String) -> Unit)?) {
        logCallback = callback
    }

    fun debug(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.DEBUG, tag, message, throwable)

    fun info(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.INFO, tag, message, throwable)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARNING, tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARNING -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }

        try {
            logCallback?.invoke(level.name.first().toString(), tag, message)
        } catch (e: Exception) {
            Log.e("SDKLogger", "Error in log callback", e)
        }
    }
}

object SDKUtils {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Random alphanumeric string - used for Janus transaction ids. */
    fun randomString(length: Int): String =
        buildString(length) { repeat(length) { append(CHARS.random()) } }
}
