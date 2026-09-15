package com.example.janus.client.webrtc

import android.content.Context
import com.example.janus.client.SDKLogger
import com.example.janus.client.room.RoomOptions
import com.example.janus.client.track.LocalAudioTrack
import com.example.janus.client.track.LocalVideoTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates the WebRTC core engine, managing the [PeerConnectionFactory],
 * hardware video codec factories, and media track lifecycles.
 */
class WebRtcEngine(
    val context: Context,
    val options: RoomOptions
) {
    private val TAG = "WebRtcEngine"
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val isInitialized = AtomicBoolean(false)

    val eglBaseContext: EglBase.Context? = options.eglBaseContext
    val cameraManager = CameraCapturerManager(context, options.defaultCameraPosition)
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun initialize(): Boolean {
        if (isInitialized.getAndSet(true)) return true

        return try {
            SDKLogger.info(TAG, "Initializing WebRtcEngine...")

            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            SDKLogger.info(TAG, "WebRtcEngine initialized successfully")
            true
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Failed to initialize WebRtcEngine", e)
            isInitialized.set(false)
            false
        }
    }

    fun createPeerConnection(
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val factory = peerConnectionFactory ?: run {
            SDKLogger.error(TAG, "PeerConnectionFactory is null")
            return null
        }

        val rtcConfig = PeerConnection.RTCConfiguration(options.toIceServers()).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return try {
            factory.createPeerConnection(rtcConfig, observer)
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Failed to create PeerConnection", e)
            null
        }
    }

    fun createLocalAudioTrack(id: String): LocalAudioTrack {
        val factory = checkNotNull(peerConnectionFactory) { "WebRtcEngine not initialized" }
        val audioSource = factory.createAudioSource(MediaConstraints())
        val rtcTrack = factory.createAudioTrack(id, audioSource)
        return LocalAudioTrack(rtcTrack)
    }

    fun createLocalVideoTrack(id: String): LocalVideoTrack? {
        val factory = checkNotNull(peerConnectionFactory) { "WebRtcEngine not initialized" }
        val capturer = cameraManager.createVideoCapturer() ?: return null

        return try {
            val videoSource = factory.createVideoSource(false)
            surfaceTextureHelper = SurfaceTextureHelper.create("JanusCaptureThread", eglBaseContext)
            capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer.startCapture(options.videoWidth, options.videoHeight, options.videoFps)

            val rtcTrack = factory.createVideoTrack(id, videoSource)
            LocalVideoTrack(rtcTrack)
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Failed to create local video track", e)
            null
        }
    }

    fun switchCamera(onSuccess: ((Boolean) -> Unit)? = null) {
        cameraManager.switchCamera(onSuccess)
    }

    fun dispose() {
        try {
            cameraManager.dispose()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            isInitialized.set(false)
            SDKLogger.info(TAG, "WebRtcEngine disposed successfully")
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error disposing WebRtcEngine", e)
        }
    }
}
