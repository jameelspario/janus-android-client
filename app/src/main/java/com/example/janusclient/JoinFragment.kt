package com.example.janusclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.janus.client.UserRole
import com.example.janusclient.databinding.FragmentJoinBinding

class JoinFragment : Fragment() {

    private var _binding: FragmentJoinBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJoinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnJoinHost.setOnClickListener {
            joinRoom(UserRole.HOST)
        }

        binding.btnJoinGuest.setOnClickListener {
            joinRoom(UserRole.GUEST)
        }
    }

    private fun joinRoom(role: UserRole) {
        val roomIdStr = binding.etRoomId.text.toString()
        val displayName = binding.etDisplayName.text.toString()

        if (roomIdStr.isEmpty() || displayName.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter Room ID and Name", Toast.LENGTH_SHORT).show()
            return
        }

        val roomId = roomIdStr.toIntOrNull() ?: 1234
        
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ConferenceFragment.newInstance(roomId, displayName, role))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}