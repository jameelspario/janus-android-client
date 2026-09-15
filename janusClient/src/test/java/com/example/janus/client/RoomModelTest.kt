package com.example.janus.client

import com.example.janus.client.participant.LocalParticipant
import com.example.janus.client.participant.LocalParticipantController
import com.example.janus.client.participant.RemoteParticipant
import com.example.janus.client.room.ConnectionState
import com.example.janus.client.room.RoomEvent
import com.example.janus.client.room.RoomOptions
import com.example.janus.client.room.TurnServer
import com.example.janus.client.signaling.JanusResponse
import com.example.janus.client.signaling.toPlainMap
import com.example.janus.client.track.RemoteTrackPublication
import com.example.janus.client.track.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class RoomModelTest {

    @Test
    fun roomOptions_iceServers_mappedCorrectly() {
        val options = RoomOptions(
            stunServers = listOf("stun:stun1.example.com", "stun:stun2.example.com"),
            turnServers = listOf(
                TurnServer(url = "turn:turn.example.com:3478", username = "user", credential = "pwd")
            )
        )

        val iceServers = options.toIceServers()
        assertEquals(3, iceServers.size)
        assertEquals("user", iceServers[2].username)
        assertEquals("pwd", iceServers[2].password)
    }

    @Test
    fun localParticipant_controls_invokesController() = runBlocking {
        var micToggled: Boolean? = null
        var cameraToggled: Boolean? = null
        var cameraSwitched = false

        val controller = object : LocalParticipantController {
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {
                micToggled = enabled
            }

            override suspend fun setCameraEnabled(enabled: Boolean) {
                cameraToggled = enabled
            }

            override fun switchCamera() {
                cameraSwitched = true
            }
        }

        val local = LocalParticipant("user1", "Alice", UserRole.HOST, controller)

        assertEquals("user1", local.identity)
        assertEquals("Alice", local.name)
        assertTrue(local.isHost())
        assertFalse(local.isMicrophoneEnabled)

        local.setMicrophoneEnabled(true)
        assertEquals(true, micToggled)
        assertTrue(local.isMicrophoneEnabled)

        local.setCameraEnabled(true)
        assertEquals(true, cameraToggled)
        assertTrue(local.isCameraEnabled)

        local.switchCamera()
        assertTrue(cameraSwitched)
    }

    @Test
    fun remoteParticipant_publications_syncTracksFlow() {
        val feedId = BigInteger.valueOf(12345678)
        val remote = RemoteParticipant(feedId, "feed-123", "Bob")

        assertEquals(feedId, remote.feedId)
        assertEquals("Bob", remote.name)
        assertFalse(remote.isHost())
        assertEquals(0, remote.tracks.value.size)

        val pub = RemoteTrackPublication("video-feed-123", Track.Kind.VIDEO, null)
        remote.addPublication(pub)

        assertEquals(1, remote.tracks.value.size)
        assertEquals(1, remote.videoTracks.size)
        assertEquals(0, remote.audioTracks.size)
        assertFalse(pub.isSubscribed)
        assertNull(remote.videoTrack)

        val removed = remote.removePublication("video-feed-123")
        assertNotNull(removed)
        assertEquals(0, remote.tracks.value.size)
    }

    @Test
    fun jsonToPlainMap_parsesNestedObjectsCorrectly() {
        val json = JSONObject("""
            {
                "janus": "event",
                "transaction": "tx123",
                "plugindata": {
                    "plugin": "janus.plugin.videoroom",
                    "data": {
                        "videoroom": "joined",
                        "room": 1234,
                        "id": 8888,
                        "display": "Tester"
                    }
                }
            }
        """.trimIndent())

        val map = json.toPlainMap()
        assertEquals("event", map["janus"])
        assertEquals("tx123", map["transaction"])

        val plugindata = map["plugindata"] as Map<*, *>
        assertEquals("janus.plugin.videoroom", plugindata["plugin"])

        val data = plugindata["data"] as Map<*, *>
        assertEquals("joined", data["videoroom"])
        assertEquals(1234, data["room"])
    }

    @Test
    fun janusResponse_isSuccess_verified() {
        val successJson = JSONObject("""{"janus": "success", "transaction": "abc"}""")
        val resp = JanusResponse(
            janus = "success",
            transaction = "abc",
            rawJson = successJson
        )
        assertTrue(resp.isSuccess)

        val errJson = JSONObject("""{"janus": "error", "transaction": "abc", "error_code": 426}""")
        val errResp = JanusResponse(
            janus = "error",
            transaction = "abc",
            errorCode = 426,
            rawJson = errJson
        )
        assertFalse(errResp.isSuccess)

        // Publish event response test
        val publishEventJson = JSONObject("""
            {
                "janus": "event",
                "transaction": "tx_pub",
                "plugindata": {
                    "plugin": "janus.plugin.videoroom",
                    "data": {
                        "videoroom": "event",
                        "configured": "ok",
                        "room": 1234
                    }
                },
                "jsep": {
                    "type": "answer",
                    "sdp": "v=0..."
                }
            }
        """.trimIndent())
        val publishResp = JanusResponse(
            janus = "event",
            transaction = "tx_pub",
            plugindata = publishEventJson.getJSONObject("plugindata").toPlainMap(),
            jsep = publishEventJson.getJSONObject("jsep").toPlainMap(),
            rawJson = publishEventJson
        )
        assertTrue(publishResp.isSuccess)
        assertNotNull(publishResp.jsep)
        assertEquals("answer", publishResp.jsep?.get("type"))
        assertEquals("ok", publishResp.pluginDataMap["configured"])
    }
}
