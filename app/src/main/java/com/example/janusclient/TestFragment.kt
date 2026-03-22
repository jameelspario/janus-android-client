package com.example.janusclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.janus.client.JanusParticipant
import com.example.janus.client.JanusRoom
import com.example.janus.client.JanusSDK
import com.example.janus.client.JanusSDKConfigBuilder
import com.example.janus.client.JanusSDKEventListener
import com.example.janus.client.UserRole
import com.example.janus.client.consoleLogE
import com.example.janusclient.databinding.FragmentTestBinding
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.math.BigInteger


class TestFragment : Fragment() {

    lateinit var binding: FragmentTestBinding
    lateinit var sdk: JanusSDK
    private lateinit var remoteAdapter: RemoteStreamAdapter

    val eglBase = EglBase.create()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.txtJoin.setOnClickListener {
            sdk.joinRoom(1234, "123", UserRole.HOST, "tst")
        }
        binding.txtLeave.setOnClickListener {
            sdk.leaveRoom(1234)
        }
        binding.txtPublish.setOnClickListener {
            sdk.publishStream(1234, true, true)
        }
        binding.txtUnpublish.setOnClickListener {
            sdk.unpublishStream(1234)
        }

        val config = JanusSDKConfigBuilder(requireContext())
            .eglContext(eglBase.eglBaseContext)
//             .serverUrl("wss://bindaslive.com/janus")
             .serverUrl("wss://janus.conf.meetecho.com/ws")
             .build()

        sdk = JanusSDK(requireContext(), config)
        sdk.initialize(callback())
        sdk.enableSubscription()
        sdk.connect()

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        remoteAdapter = RemoteStreamAdapter(eglBase.eglBaseContext, sdk = sdk)   // sdk not ready yet — set below
        binding.recyclerView.apply {
            // 2 columns; change to 1 for full-width tiles
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            adapter = remoteAdapter
        }
    }

    inner class callback :JanusSDKEventListener{
        override fun onSignalingConnected() {

        }

        override fun onSignalingDisconnected() {

        }

        override fun onRoomJoined(room: JanusRoom) {
            consoleLogE("FRAG", "onRoomJoined", "${room.roomId}")
        }

        override fun onRoomLeft(roomId: Int) {
            consoleLogE("FRAG", "onRoomLeft", "$roomId")
        }

        override fun onParticipantJoined(participant: JanusParticipant) {
            consoleLogE("FRAG", "onParticipantJoined", "${participant.participantId}")
        }

        override fun onParticipantLeft(participantId: String) {
            consoleLogE("FRAG", "onParticipantLeft", "${participantId}")
        }

        override fun onStreamPublished(streamId: String) {
            consoleLogE("FRAG", "onStreamPublished", "$streamId")
            requireActivity().runOnUiThread {
                sdk.showLocalPreview(streamId, binding.localView, eglBase.eglBaseContext)
            }
        }

        override fun onStreamUnpublished(streamId: String) {
            consoleLogE("FRAG", "onStreamUnpublished", "$streamId")
            requireActivity().runOnUiThread {
                sdk.removeLocalPreview( binding.localView)
//                binding.localView.release()
            }
        }

        override fun onRemoteStreamAvailable(
            feedId: BigInteger,
            display: String?,
            track: VideoTrack
        ) {
            consoleLogE("FRAG", "onStreamSubscribed", "$feedId")
            requireActivity().runOnUiThread {
                remoteAdapter.addOrUpdate(
                    RemoteStream(feedId, display ?: "", track)
                )
            }
        }

        override fun onRemoteStreamRemoved(feedId: BigInteger?) {
            consoleLogE("FRAG", "onStreamSubscribed", "$feedId")
            feedId ?: return
            requireActivity().runOnUiThread {
                remoteAdapter.remove(feedId)
            }
        }

        override fun onError(error: String) {
            consoleLogE("FRAG", "onError", "$error")
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release all SurfaceViewRenderers held by the adapter
        remoteAdapter.submitList(emptyList())
        // Release local preview renderer
        binding.localView.release()
        // Disconnect SDK and tear down all subscriptions
        sdk.release()
        eglBase.release()
    }


}

fun TextView.color(@ColorInt col: Int){
    setTextColor(col)
}