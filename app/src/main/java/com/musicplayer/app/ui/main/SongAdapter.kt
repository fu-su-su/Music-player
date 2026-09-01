package com.musicplayer.app.ui.main

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.musicplayer.app.R
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemSongBinding
import com.musicplayer.app.util.AudioUtils
import com.musicplayer.app.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAssociateClick: (Song) -> Unit,
    private val onDeleteClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun destroy() {
        adapterScope.cancel()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: SongViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
            Glide.with(binding.root.context).clear(binding.coverImageView)
        }

        fun bind(song: Song) {
            cancelLoad()

            binding.titleTextView.text = song.title
            binding.artistTextView.text = song.artist
            binding.durationOverlayTextView.text = AudioUtils.formatDuration(song.duration)

            val context = binding.root.context
            val placeholder = ColorDrawable(
                ContextCompat.getColor(context, R.color.surface_light)
            )

            // 优先加载自定义封面
            val coverPath = song.coverPath
            if (coverPath != null) {
                Glide.with(context)
                    .load(coverPath)
                    .apply(
                        RequestOptions()
                            .centerCrop()
                            .placeholder(placeholder)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                    )
                    .into(binding.coverImageView)
            } else {
                // 尝试内嵌封面
                loadJob = adapterScope.launch {
                    val bytes: ByteArray? = withContext(Dispatchers.IO) {
                        try {
                            AudioUtils.getEmbeddedCover(song.path)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val bitmap: Bitmap? = withContext(Dispatchers.Default) {
                        ImageUtils.loadBitmapFromBytes(bytes, 150, 150)
                    }
                    if (bitmap != null) {
                        Glide.with(context)
                            .load(bitmap)
                            .apply(
                                RequestOptions()
                                    .centerCrop()
                                    .placeholder(placeholder)
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                            )
                            .into(binding.coverImageView)
                    } else {
                        binding.coverImageView.setImageDrawable(placeholder)
                    }
                }
            }

            binding.root.setOnClickListener { onSongClick(song) }
            binding.associateButton.setOnClickListener { onAssociateClick(song) }
            binding.deleteButton.setOnClickListener { onDeleteClick(song) }
        }
    }
}
