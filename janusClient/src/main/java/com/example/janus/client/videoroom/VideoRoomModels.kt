package com.example.janus.client.videoroom

import java.math.BigInteger

/**
 * Per-stream (per-mid) information for a single published feed.
 * Maps to one entry inside the "streams" array that Janus returns per publisher
 * in the "joined" / "event" (publishers) response.
 */
data class PublisherStreamInfo(
    val mid: String,        // e.g. "0" for audio, "1" for video
    val type: String,       // "audio" or "video"
    val codec: String?      // e.g. "opus", "vp8", "h264"
)

/**
 * Information regarding a published feed from Janus VideoRoom.
 * Updated to carry per-mid [streams] info from the modern multi-stream Janus API.
 */
data class PublisherFeedInfo(
    val feedId: BigInteger,
    val display: String,
    val streams: List<PublisherStreamInfo> = emptyList()
)

/**
 * Maps a subscriber-side transceiver mid to the originating publisher's metadata.
 *
 * Populated from the "streams[]" array in "attached" / "updated" events.
 * Equivalent to the JS `subStreams` map in videoroomtest.js:
 *   subStreams[mid] = msg["streams"][i]
 */
data class SubStreamInfo(
    val mid: String,            // subscriber-side mid (transceiver.mid)
    val feedId: BigInteger,     // feed_id in the Janus response
    val feedDisplay: String,    // feed_display
    val type: String,           // "audio" or "video"
    val codec: String?
)

/**
 * Result returned upon joining a VideoRoom as a publisher.
 */
data class VideoRoomJoinResult(
    val roomId: Int,
    val participantId: String,
    val privateId: Int?,
    val publishers: List<PublisherFeedInfo>
)
