package com.example.janus.client.signaling

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Parsed response from Janus Gateway.
 */
data class JanusResponse(
    val janus: String,
    val transaction: String,
    val sessionId: BigInteger? = null,
    val senderHandleId: BigInteger? = null,
    val data: Map<String, Any> = emptyMap(),
    val plugindata: Map<String, Any> = emptyMap(),
    val jsep: Map<String, Any>? = null,
    val error: String? = null,
    val errorCode: Int? = null,
    val rawJson: JSONObject
) {
    val isSuccess: Boolean
        get() = janus == "success" || (janus == "event" && errorCode == null)

    val pluginDataMap: Map<String, Any>
        get() {
            val d = plugindata["data"] as? Map<*, *> ?: return emptyMap()
            return d.entries.mapNotNull { (k, v) ->
                if (k is String && v != null) k to v else null
            }.toMap()
        }
}

/**
 * Utility functions for JSON conversions.
 */
fun JSONObject.toPlainMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = this.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = this.opt(key)
        if (value != null && value != JSONObject.NULL) {
            map[key] = when (value) {
                is JSONObject -> value.toPlainMap()
                is JSONArray -> value.toPlainList()
                else -> value
            }
        }
    }
    return map
}

fun JSONArray.toPlainList(): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until length()) {
        val value = this.opt(i)
        if (value != null && value != JSONObject.NULL) {
            list.add(
                when (value) {
                    is JSONObject -> value.toPlainMap()
                    is JSONArray -> value.toPlainList()
                    else -> value
                }
            )
        }
    }
    return list
}
