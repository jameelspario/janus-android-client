package com.example.janusclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.janus.client.Janus
import com.example.janus.client.UserRole
import com.example.janus.client.room.ConnectionState
import com.example.janus.client.room.Room
import com.example.janus.client.room.RoomEvent
import com.example.janus.client.room.RoomOptions
import com.example.janus.client.track.VideoTrack
import com.example.janusclient.databinding.FragmentConferenceBinding
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import java.util.UUID

/**
 * ConferenceFragment demonstrating the LiveKit-style Janus Room SDK pattern using Kotlin Coroutines & Flow.
 */
class ConferenceFragment : Fragment() {

    private var _binding: FragmentConferenceBinding? = null
    private val binding get() = _binding!!

    private lateinit var room: Room
    private lateinit var remoteAdapter: RemoteStreamAdapter
    private val eglBase = EglBase.create()

    private var roomId: Int = 0
    private var displayName: String = ""
    private var role: UserRole = UserRole.GUEST

    private var isMicEnabled = true
    private var isCameraEnabled = true

    companion object {
        private const val ARG_ROOM_ID = "room_id"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_ROLE = "role"

        fun newInstance(roomId: Int, displayName: String, role: UserRole) =
            ConferenceFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_ROOM_ID, roomId)
                    putString(ARG_DISPLAY_NAME, displayName)
                    putSerializable(ARG_ROLE, role)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            roomId = it.getInt(ARG_ROOM_ID)
            displayName = it.getString(ARG_DISPLAY_NAME) ?: "User"
            role = it.getSerializable(ARG_ROLE) as? UserRole ?: UserRole.GUEST
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConferenceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        binding.txtRoomInfo.text = "Room: $roomId ($role) - $displayName"

        remoteAdapter = RemoteStreamAdapter(eglBase.eglBaseContext)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            adapter = remoteAdapter
        }

        binding.btnMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            viewLifecycleOwner.lifecycleScope.launch {
                room.setMicrophoneEnabled(isMicEnabled)
            }
            binding.btnMic.setImageResource(
                if (isMicEnabled) android.R.drawable.ic_btn_speak_now
                else android.R.drawable.stat_notify_call_mute
            )
        }

        binding.btnCamera.setOnClickListener {
            isCameraEnabled = !isCameraEnabled
            viewLifecycleOwner.lifecycleScope.launch {
                room.setCameraEnabled(isCameraEnabled)
            }
            binding.btnCamera.setImageResource(
                if (isCameraEnabled) android.R.drawable.ic_menu_camera
                else android.R.drawable.ic_menu_close_clear_cancel
            )
        }

        binding.btnLeave.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                room.disconnect()
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val neededPermissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missingPermissions = neededPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initRoom()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            initRoom()
        } else {
            Toast.makeText(requireContext(), "Permissions denied", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
        }
    }

    private fun initRoom() {
        val options = RoomOptions(
            eglBaseContext = eglBase.eglBaseContext,
            audioEnabled = true,
            videoEnabled = true
        )

        // 1. Create Room instance
        room = Janus.create(requireContext(), options)
        room.initVideoRenderer(binding.localView, mirror = true)

        // 2. Observe Room events using Kotlin Flow
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    room.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                // Attach local video track to preview
                                val localVideo = room.localParticipant.videoTracks.firstOrNull()?.track as? VideoTrack
                                localVideo?.addRenderer(binding.localView)
                            }
                            is RoomEvent.Disconnected -> {
                                Toast.makeText(requireContext(), "Disconnected from room", Toast.LENGTH_SHORT).show()
                            }
                            is RoomEvent.TrackSubscribed -> {
                                val remoteVideo = event.track as? VideoTrack
                                if (remoteVideo != null) {
                                    remoteAdapter.addOrUpdate(
                                        RemoteStream(
                                            feedId = event.participant.feedId,
                                            display = event.participant.name.ifEmpty { "Remote" },
                                            track = remoteVideo.rtcVideoTrack,
                                            isHost = event.participant.isHost()
                                        )
                                    )
                                }
                            }
                            is RoomEvent.TrackUnsubscribed -> {
                                remoteAdapter.remove(event.participant.feedId)
                            }
                            is RoomEvent.FailedToConnect -> {
                                Toast.makeText(requireContext(), "Connection error: ${event.error.message}", Toast.LENGTH_SHORT).show()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        // 3. Connect to the room
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userId = UUID.randomUUID().toString().substring(0, 8)
                room.connect(
                    url = "wss://janus.conf.meetecho.com/ws",
//                    url = "ws://192.168.1.160:8188",
                    roomId = roomId,
                    userId = userId,
                    displayName = displayName,
                    role = role
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to connect: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::room.isInitialized) {
            room.release()
        }
        eglBase.release()
        _binding = null
    }
}