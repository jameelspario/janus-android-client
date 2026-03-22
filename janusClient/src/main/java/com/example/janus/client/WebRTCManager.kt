package com.example.janus.client

import android.content.Context
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(
    private val context: android.content.Context,
    val eglBaseContext: EglBase.Context?,
    private val config: WebRTCConfig = WebRTCConfig.default()

) {
    private val TAG = "WebRTCManager"
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private val activePeerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val mediaStreams = ConcurrentHashMap<String, MediaStream>()
    private val videoCapturers = ConcurrentHashMap<String, VideoCapturer>()
    private val isInitialized = AtomicBoolean(false)
    val track = TrackHelper(context)

//    val factory: PeerConnectionFactory
//        get() = peerConnectionFactory


    fun initialize(): Boolean {

        return try {
            if (isInitialized.getAndSet(true)) return true

            SDKLogger.info(TAG, "Initializing WebRTC...")

            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
//                .setInjectableLogger(config.logger, config.logLevel)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(options)

            val encoderFactory: VideoEncoderFactory =
                DefaultVideoEncoderFactory(eglBaseContext, true,  /* enableIntelVp8Encoder */true)
            val decoderFactory: VideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
//            .setAudioDeviceModule(audioDeviceModule.createAudioDeviceModule())
                .setOptions(config.createOptions())
                .createPeerConnectionFactory()

            SDKLogger.info(TAG, "WebRTC initialized successfully")

            true
        } catch (e:Exception){
            SDKLogger.error(TAG, "WebRTC initialization failed", e)
            isInitialized.set(false)
            false
        }

    }

//    val getIceServers: List<PeerConnection.IceServer> by lazy {
//        listOf(
//            PeerConnection.IceServer.builder(Appdata.STUNServersURL)
//                .createIceServer(),
//
////             PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
////            PeerConnection.IceServer.builder("stun:webrtc.encmed.cn:5349").createIceServer(),
////            PeerConnection.IceServer.builder("stun:webrtc.encmed.cn:5349").createIceServer(),
//
//            PeerConnection.IceServer.builder(Appdata.TURNServersURL)
//                .setUsername(Appdata.TURNUserName)
//                .setPassword(Appdata.TURNPassword)
//                .createIceServer(),
//
////            PeerConnection.IceServer.builder("relay1.expressturn.com:3478")
////                .setUsername("ef8J1D2QFZZYSBS1CP")
////                .setPassword("0ksesr4bmE3MW4jI")
////                .createIceServer()
//
////                PeerConnection.IceServer.builder("stun:askjitendra.com:3478").createIceServer(),
//
////                    PeerConnection.IceServer.builder("turn:askjitendra.com:3478?transport=tcp")
////                        .setUsername("letscmsturn")
////                        .setPassword("Letscms@2018")
////                        .createIceServer()
//
//        )
//    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer
    ): PeerConnection? {

        consoleLogE("rtc", "createPeerConnection", "iceServers: $iceServers")
        if (!isInitialized.get()) {
            SDKLogger.error(TAG, "WebRTC not initialized")
            return null
        }

        return try {
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
//            iceTransportsType = PeerConnection.IceTransportsType.ALL
//            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
//            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                iceTransportsType = PeerConnection.IceTransportsType.ALL // Forces TURN usage
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
//            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED  // Allow TCP fallback
//            networkPreference = PeerConnection.AdapterType.WIFI

//            enableDtlsSrtp = true
//            enableDscp = true
            }

            peerConnectionFactory.createPeerConnection(rtcConfig, observer).also { pc ->
                if(pc != null){
                    SDKLogger.debug(TAG, "Peer connection created")
                }
            }
        }catch (e:Exception){
            null
        }
//
//        return peerConnectionFactory.createPeerConnection(
//            rtcConfig,
//            object : PeerConnection.Observer {
//                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
//                }
//
//                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
//
//                }
//
//                override fun onIceConnectionReceivingChange(p0: Boolean) {
//
//                }
//
//                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
//                    /* handle ice change*/
//                    if (newState == PeerConnection.IceGatheringState.COMPLETE) {
//                        callback.onIceGatheringComplete()
//                    }
//                }
//
//                override fun onIceCandidate(candidate: IceCandidate) {
//                    // Send ICE candidates to Janus
//                    callback.onIceCandidate(candidate)
//                }
//
//                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {
//                    /* ***** */
//                    callback.onIceCandidatesRemoved(candidates)
//                }
//
//                override fun onAddStream(stream: MediaStream) {
//                    // Handle incoming streams
//                    testTrack(stream)
//                    consoleLogE("rtc", "onAddStream ${stream.videoTracks.size}")
//                    callback.onAddStream(stream)
//                }
//
//                override fun onRemoveStream(stream: MediaStream?) {
//                    /* remove stream */
//                    testTrack(stream)
//                    consoleLogE("rtc", "onRemoveStream ${stream?.videoTracks?.size}")
//                    callback.onRemoveStream(stream)
//                }
//
//                override fun onDataChannel(p0: DataChannel?) {
//
//                }
//
//                override fun onRenegotiationNeeded() {
//
//                }
//
//                // Other overridden methods omitted for brevity
//            })
    }

    fun createMediaStream(streamId: String): MediaStream {
        return peerConnectionFactory.createLocalMediaStream(streamId).also {
            mediaStreams[streamId] = it
            SDKLogger.debug(TAG, "Created media stream: $streamId")
        }
    }

    fun createAudioTrack(trackId: String): AudioTrack {
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        return peerConnectionFactory.createAudioTrack(trackId, audioSource)
    }

    fun createVideoTrack(trackId: String, videoCapturer: VideoCapturer? = null): VideoTrack {
        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer?.let {
            try {
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBaseContext
                )
                it.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
                it.startCapture(config.videoWidth, config.videoHeight, config.videoFps)
                videoCapturers[trackId] = it
                SDKLogger.debug(TAG, "Capturer initialized for $trackId")
            } catch (e: Exception) {
                SDKLogger.error(TAG, "Failed to initialize capturer", e)
            }
        }

        return peerConnectionFactory.createVideoTrack(trackId, videoSource).also {
            SDKLogger.debug(TAG, "Created video track: $trackId")
        }
    }

    fun registerPeerConnection(id: String, pc: PeerConnection) {
        activePeerConnections[id] = pc
    }

    fun unregisterPeerConnection(id: String) {
        activePeerConnections.remove(id)?.close()
    }

    fun closePeerConnection(id: String) {
        unregisterPeerConnection(id)
    }

    fun closeAll() {
        activePeerConnections.values.forEach { it.close() }
        activePeerConnections.clear()
    }

    fun testTrack(stream: MediaStream?) {
        if (stream == null) {
            return
        }
        val track = if (stream.videoTracks.size > 0) stream.videoTracks[0] else null
        consoleLogE("rtc", "testTrack ${track?.enabled()}")
    }

    fun createOffer(
        peerConnection: PeerConnection,
        callback: CreateOfferCallback?,
        videoOffer: String = "true",
        audioOffer: String = "true"
    ) {
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio",
                audioOffer
            )
        )
        mediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo",
                videoOffer
            )
        )
        mediaConstraints.optional.add(
            MediaConstraints.KeyValuePair(
                "DtlsSrtpKeyAgreement",
                "true"
            )
        );
        peerConnection.createOffer(SdpObserverCallback(object : CreateOfferCallback {
            override fun onCreateOfferSuccess(sdp: SessionDescription?) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {

                    }

                    override fun onSetSuccess() {
                    }

                    override fun onCreateFailure(p0: String?) {
                    }

                    override fun onSetFailure(p0: String?) { }

                }, sdp)
                consoleLogE("--sdp ${sdp?.description}")
                callback?.onCreateOfferSuccess(sdp)
            }

            override fun onCreateFailed(error: String?) {
                Log.d(TAG, "createOffer onCreateFailure $error")
                callback?.onCreateFailed(error)
            }
        }), mediaConstraints)
    }

    fun createAnswer(peerConnection: PeerConnection, callback: CreateAnswerCallback?) {
        val mediaConstraints = MediaConstraints()

        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        Log.d(TAG, "onCreateSuccess setLocalDescription $sdp")
                    }

                    override fun onSetSuccess() {
                        // send answer sdp
                        Log.d(TAG, "createAnswer setLocalDescription onSetSuccess")
                        callback?.onSetAnswerSuccess(sdp)
                    }

                    override fun onCreateFailure(s: String) {
                        Log.d(
                            TAG,
                            "createAnswer setLocalDescription onCreateFailure $s"
                        )
                        callback?.onSetAnswerFailed(s)
                    }

                    override fun onSetFailure(s: String) {
                        Log.d(
                            TAG,
                            "createAnswer setLocalDescription onSetFailure $s"
                        )
                        callback?.onSetAnswerFailed(s)
                    }
                }, sdp)
            }

            override fun onSetSuccess() {
                Log.d(TAG, "createAnswer onSetSuccess")
            }

            override fun onCreateFailure(s: String) {
                Log.d(TAG, "createAnswer onCreateFailure $s")
            }

            override fun onSetFailure(s: String) {
                Log.d(TAG, "createAnswer onSetFailure $s")
            }
        }, mediaConstraints)
    }

    fun createOffer(
        peerConnection: PeerConnection,
        callback: SdpObserver
    ) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection.createOffer(callback, constraints)
    }

    /**
     * Create SDP answer
     */
    fun createAnswer(
        peerConnection: PeerConnection,
        callback: SdpObserver
    ) {
        val constraints = MediaConstraints()
        peerConnection.createAnswer(callback, constraints)
    }

    /**
     * Add ICE candidate
     */
    fun addIceCandidate(
        peerConnection: PeerConnection,
        candidate: IceCandidate
    ) {
        try {
            peerConnection.addIceCandidate(candidate)
        } catch (e: Exception) {
            SDKLogger.warn(TAG, "Failed to add ICE candidate", e)
        }
    }

    /**
     * Get factory
     */
    fun getFactory(): PeerConnectionFactory = peerConnectionFactory

    /**
     * Get media stream
     */
    fun getMediaStream(streamId: String): MediaStream? = mediaStreams[streamId]

    /**
     * Get all media streams
     */
    fun getAllMediaStreams(): List<MediaStream> = mediaStreams.values.toList()

    /**
     * Close all resources
     */
    fun dispose() {
        try {
            // Stop video capturers
            videoCapturers.values.forEach {
                try {
                    it.stopCapture()
                    it.dispose()
                } catch (e: Exception) {
                    SDKLogger.warn(TAG, "Error disposing capturer", e)
                }
            }
            videoCapturers.clear()

            // Close peer connections
            activePeerConnections.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    SDKLogger.warn(TAG, "Error closing peer connection", e)
                }
            }
            activePeerConnections.clear()

            // Clear media streams
            mediaStreams.clear()

            // Dispose factory
            peerConnectionFactory.dispose()
            isInitialized.set(false)

            SDKLogger.info(TAG, "WebRTC disposed")
        } catch (e: Exception) {
            SDKLogger.error(TAG, "Error disposing WebRTC", e)
        }
    }

    /**
     * Check if initialized
     */
    fun isInitialized(): Boolean = isInitialized.get()

    fun toggleAudio(roomId:Int, enabled: Boolean){}
    fun toggleVideo(roomId:Int, enabled: Boolean){}
    fun switchCamera(){}
    fun setSpeakerEnabled(enabled: Boolean){}

    fun showLocalPreview(renderer: SurfaceViewRenderer){

    }
}


open class SdpObserverCallback(val callback: CreateOfferCallback?) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {
        callback?.onCreateOfferSuccess(sdp)
    }

    override fun onSetSuccess() {

    }

    override fun onCreateFailure(error: String?) {
        callback?.onCreateFailed(error)
    }

    override fun onSetFailure(error: String?) {
        callback?.onCreateFailed(error)
    }
}


/**
 * WebRTC Configuration
 */
data class WebRTCConfig(
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoFps: Int = 30,
    val iceTransportType: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL,
    val bundlePolicy: PeerConnection.BundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE,
    val rtcpMuxPolicy: PeerConnection.RtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
) {
    fun createOptions() = PeerConnectionFactory.Options()

    companion object {
        fun default() = WebRTCConfig()
    }
}

/**
 * Configuration builder for WebRTC
 */
class WebRTCConfigBuilder {
    private var videoWidth: Int = 1280
    private var videoHeight: Int = 720
    private var videoFps: Int = 30
    private var iceTransportType = PeerConnection.IceTransportsType.ALL
    private var bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    private var rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

    fun videoResolution(width: Int, height: Int) = apply {
        this.videoWidth = width
        this.videoHeight = height
    }

    fun videoFps(fps: Int) = apply {
        require(fps in 15..60) { "FPS must be 15-60" }
        this.videoFps = fps
    }

    fun iceTransportType(type: PeerConnection.IceTransportsType) = apply {
        this.iceTransportType = type
    }

    fun bundlePolicy(policy: PeerConnection.BundlePolicy) = apply {
        this.bundlePolicy = policy
    }

    fun rtcpMuxPolicy(policy: PeerConnection.RtcpMuxPolicy) = apply {
        this.rtcpMuxPolicy = policy
    }

    fun build() = WebRTCConfig(
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoFps = videoFps,
        iceTransportType = iceTransportType,
        bundlePolicy = bundlePolicy,
        rtcpMuxPolicy = rtcpMuxPolicy
    )
}

class TrackHelper(private val context: Context) {

    public var videoCapturer: VideoCapturer? = null

    companion object {
        private const val TAG = "TrackHelper"
    }

    fun createVideoCapturer(): VideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "Using Camera2 API")
            Camera2Enumerator(context)
        } else {
            Log.d(TAG, "Falling back to Camera1 API")
            Camera1Enumerator(true) // true = capture to texture
        }

        return createCameraCapturer(enumerator)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        if (deviceNames.isEmpty()) {
            Log.w(TAG, "No cameras available on this device")
            return null
        }

        // 1. Prefer front-facing camera first
        Log.d(TAG, "Looking for front-facing camera...")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let { capturer ->
                    Log.d(TAG, "Front camera selected: $deviceName")
                    return capturer
                }
            }
        }

        // 2. Fallback to any other available camera (usually rear)
        Log.d(TAG, "No front camera found — looking for any camera...")
        for (deviceName in deviceNames) {
            enumerator.createCapturer(deviceName, null)?.let { capturer ->
                Log.d(TAG, "Using camera: $deviceName")
                return capturer
            }
        }

        Log.e(TAG, "Failed to create any video capturer")
        return null
    }
}