package com.example.janus.client.webrtc

import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor

/**
 * [org.webrtc.VideoProcessor] base that processes every captured frame, instead of WebRTC's default of
 * dropping frames whenever it decides the track isn't being sent - that default made filtered
 * video stall/freeze intermittently.
 */
abstract class NoDropVideoProcessor : VideoProcessor {
    /** true restores WebRTC's default frame dropping. */
    var allowDropping = false

    override fun onFrameCaptured(frame: VideoFrame, parameters: VideoProcessor.FrameAdaptationParameters) {
        if (allowDropping) {
            super.onFrameCaptured(frame, parameters)
            return
        }
        val adaptedFrame = VideoProcessor.applyFrameAdaptationParameters(frame, parameters)
        if (adaptedFrame != null) {
            onFrameCaptured(adaptedFrame)
            adaptedFrame.release()
        } else {
            onFrameCaptured(frame)
        }
    }
}