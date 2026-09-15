package com.example.janus.client.webrtc

import android.content.Context
import com.example.janus.client.SDKLogger
import com.example.janus.client.room.CameraPosition
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.VideoCapturer

/**
 * Manages video camera capture, hardware enumeration, and camera switching.
 */
class CameraCapturerManager(
    private val context: Context,
    private val preferredPosition: CameraPosition = CameraPosition.FRONT
) {
    private var capturer: CameraVideoCapturer? = null
    private var isFrontFacing: Boolean = (preferredPosition == CameraPosition.FRONT)

    fun createVideoCapturer(): VideoCapturer? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }

        val deviceNames = enumerator.deviceNames
        if (deviceNames.isEmpty()) {
            SDKLogger.error("CameraCapturerManager", "No camera devices found on device")
            return null
        }

        // Try preferred position first
        val targetFront = (preferredPosition == CameraPosition.FRONT)
        var selectedDevice: String? = deviceNames.firstOrNull { enumerator.isFrontFacing(it) == targetFront }

        // Fallback to any camera if preferred not found
        if (selectedDevice == null) {
            selectedDevice = deviceNames.firstOrNull()
        }

        if (selectedDevice == null) {
            SDKLogger.error("CameraCapturerManager", "Could not find suitable camera device")
            return null
        }

        isFrontFacing = enumerator.isFrontFacing(selectedDevice)
        val created = enumerator.createCapturer(selectedDevice, null) as? CameraVideoCapturer
        capturer = created
        return created
    }

    /**
     * Toggles between front-facing and back-facing camera.
     */
    fun switchCamera(onSuccess: ((Boolean) -> Unit)? = null) {
        val currentCapturer = capturer ?: run {
            SDKLogger.warn("CameraCapturerManager", "Cannot switch camera: capturer is null")
            return
        }

        currentCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                isFrontFacing = isFrontCamera
                SDKLogger.info("CameraCapturerManager", "Camera switched. Front facing: $isFrontCamera")
                onSuccess?.invoke(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                SDKLogger.error("CameraCapturerManager", "Camera switch failed: $errorDescription")
            }
        })
    }

    fun isFrontFacing(): Boolean = isFrontFacing

    fun getCapturer(): CameraVideoCapturer? = capturer

    fun dispose() {
        try {
            capturer?.stopCapture()
            capturer?.dispose()
        } catch (e: Exception) {
            SDKLogger.warn("CameraCapturerManager", "Error disposing capturer: ${e.message}")
        } finally {
            capturer = null
        }
    }
}
