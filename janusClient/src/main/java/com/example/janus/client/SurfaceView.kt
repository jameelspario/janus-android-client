package com.example.janus.client

import android.content.Context
import android.util.AttributeSet
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class JanusSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceViewRenderer(context, attrs) {

    private var eglBase: EglBase? = null
    private var isRendererInitialized = false

    fun initRenderer(sharedContext: EglBase.Context) {
        if (isRendererInitialized) return

        init(sharedContext, null)
        setEnableHardwareScaler(true)
        setMirror(false)
        setZOrderMediaOverlay(true)
        isRendererInitialized = true
    }

    fun renderVideo(videoTrack: VideoTrack?) {
        videoTrack?.addSink(this)
    }

    fun clearVideo(videoTrack: VideoTrack?) {
        videoTrack?.removeSink(this)
    }

    fun releaseRenderer() {
        try {
            release()
            eglBase?.release()
            eglBase = null
            isRendererInitialized = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}