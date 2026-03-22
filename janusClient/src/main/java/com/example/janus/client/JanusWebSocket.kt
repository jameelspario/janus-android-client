package com.example.janus.client

import okhttp3.*
import org.json.JSONObject

class JanusWebSocket(private val url: String) {
    private val client = OkHttpClient()
    private lateinit var webSocket: WebSocket

    fun connect(listener: WebSocketListener) {
        try {
            consoleLogE("----jnus--connecting--- $url")
            //  val request = Request.Builder().url(url).build()
            val client = OkHttpClient()
            val request = Request.Builder()
                .header("Sec-WebSocket-Protocol", "janus-protocol")
                .url(url)
                .build()
            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(message: JSONObject) {
        if (isConnected())
            webSocket.send(message.toString())
    }

    fun close() {
        webSocket.close(1000, "Closing")
    }

    fun isConnected(): Boolean {
        return try {
            webSocket.queueSize() >= 0
        } catch (e: Exception) {
            false
        }
    }
}

