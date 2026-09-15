package com.example.janus.client.videoroom

import java.math.BigInteger

/**
 * Information regarding a published feed from Janus VideoRoom.
 */
data class PublisherFeedInfo(
    val feedId: BigInteger,
    val display: String,
    val audioCodec: String? = null,
    val videoCodec: String? = null
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
