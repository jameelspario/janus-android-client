package com.example.janusclient

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

// ─────────────────────────────────────────────────────────────────────────────
//  Data model for one remote stream tile
// ─────────────────────────────────────────────────────────────────────────────

data class RemoteStream(
    val feedId: BigInteger,
    val display: String,
    val track: VideoTrack            // live track from onRemoteStreamAvailable
)

// ─────────────────────────────────────────────────────────────────────────────
//  Adapter
// ─────────────────────────────────────────────────────────────────────────────

class RemoteStreamAdapter(
    private val eglBaseContext: EglBase.Context,
    private val sdk: JanusSDK
) : ListAdapter<RemoteStream, RemoteStreamAdapter.ViewHolder>(DIFF) {

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val renderer: SurfaceViewRenderer = root.findViewById(R.id.remoteRenderer)
        val txtDisplay: TextView          = root.findViewById(R.id.txtDisplay)
        val progress: ProgressBar         = root.findViewById(R.id.progressBar)

        private var boundFeedId: BigInteger? = null

        fun bind(stream: RemoteStream) {
            // Release previous binding if we are being recycled onto a different feed
            if (boundFeedId != null && boundFeedId != stream.feedId) {
                detach(boundFeedId!!)
            }
            boundFeedId = stream.feedId

            txtDisplay.text = stream.display

//            // Init renderer once (guard against double-init on rebind)
//            try {
//                renderer.init(eglBaseContext, null)
//            } catch (_: Exception) { /* already initialised */ }

            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
//            renderer.setEnableHardwareScaler(true)

            // Hide spinner once the first frame arrives
            renderer.addFrameListener({
                itemView.post { progress.visibility = View.GONE }
            }, 1f)

            // This calls track.addSink(renderer) inside SubscriptionManager
            sdk.showRemoteStream(stream.feedId, renderer)
        }

        fun recycle() {
            boundFeedId?.let { detach(it) }
            boundFeedId = null
        }

        private fun detach(feedId: BigInteger) {
            sdk.removeRemoteStream(feedId)   // calls track.removeSink(renderer)
            try { renderer.clearImage() } catch (_: Exception) {}
        }
    }

    // ── Adapter overrides ─────────────────────────────────────────────────────

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

    // ── Public helpers called from the Fragment ───────────────────────────────

    /** Add or update a stream. Thread-safe — post to main thread before calling. */
    fun addOrUpdate(stream: RemoteStream) {
        val updated = currentList.toMutableList()
        val idx = updated.indexOfFirst { it.feedId == stream.feedId }
        if (idx >= 0) updated[idx] = stream else updated.add(stream)
        submitList(updated)
    }

    /** Remove a stream by feedId. Thread-safe — post to main thread before calling. */
    fun remove(feedId: BigInteger) {
        val updated = currentList.filter { it.feedId != feedId }
        submitList(updated)
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RemoteStream>() {
            override fun areItemsTheSame(a: RemoteStream, b: RemoteStream) =
                a.feedId == b.feedId
            override fun areContentsTheSame(a: RemoteStream, b: RemoteStream) =
                a.feedId == b.feedId && a.display == b.display
        }
    }
}