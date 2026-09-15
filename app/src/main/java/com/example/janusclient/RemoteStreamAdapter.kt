package com.example.janusclient

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.janus.client.JanusSDK
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.math.BigInteger

data class RemoteStream(
    val feedId: BigInteger,
    val display: String,
    val track: VideoTrack,
    val isHost: Boolean = false
)

class RemoteStreamAdapter(
    private val eglBaseContext: EglBase.Context,
    private val sdk: JanusSDK? = null
) : ListAdapter<RemoteStream, RemoteStreamAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val renderer: SurfaceViewRenderer = root.findViewById(R.id.remoteRenderer)
        val txtDisplay: TextView          = root.findViewById(R.id.txtDisplay)
        val progress: ProgressBar         = root.findViewById(R.id.progressBar)

        private var boundStream: RemoteStream? = null

        fun bind(stream: RemoteStream) {
            if (boundStream != null && boundStream?.feedId != stream.feedId) {
                detach()
            }
            boundStream = stream

            val displayName = if (stream.isHost) "${stream.display} (Host)" else stream.display
            txtDisplay.text = displayName
            txtDisplay.setTextColor(if (stream.isHost) Color.YELLOW else Color.WHITE)

            progress.visibility = View.VISIBLE

            try {
                renderer.init(eglBaseContext, null)
                renderer.setEnableHardwareScaler(true)
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer.setMirror(false)
            } catch (_: Exception) {}

            stream.track.addSink(renderer)
            renderer.addFrameListener({
                itemView.post { progress.visibility = View.GONE }
            }, 1f)
        }

        fun recycle() {
            detach()
        }

        private fun detach() {
            boundStream?.let { stream ->
                try {
                    stream.track.removeSink(renderer)
                    renderer.clearImage()
                } catch (_: Exception) {}
            }
            boundStream = null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_remote_stream, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    fun addOrUpdate(stream: RemoteStream) {
        val updated = currentList.toMutableList()
        val idx = updated.indexOfFirst { it.feedId == stream.feedId }
        if (idx >= 0) updated[idx] = stream else updated.add(stream)
        submitList(updated)
    }

    fun remove(feedId: BigInteger) {
        val updated = currentList.filter { it.feedId != feedId }
        submitList(updated)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RemoteStream>() {
            override fun areItemsTheSame(a: RemoteStream, b: RemoteStream) = a.feedId == b.feedId
            override fun areContentsTheSame(a: RemoteStream, b: RemoteStream) = a == b
        }
    }
}