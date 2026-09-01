package com.musicplayer.app.ui.playlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ItemSongBinding
import com.musicplayer.app.util.AudioUtils

/**
 * 歌单详情页歌曲适配器：复用 ItemSongBinding。
 * - 整行点击 → 播放
 * - deleteButton → 从歌单移除（隐藏 associateButton）
 */
class PlaylistSongAdapter(
    private val onSongClick: (Song, Int) -> Unit,
    private val onRemoveClick: (Song) -> Unit
) : ListAdapter<Song, PlaylistSongAdapter.SongViewHolder>(DIFF_CALLBACK) {

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
        holder.bind(getItem(position), position)
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song, position: Int) {
            binding.titleTextView.text = song.title
            binding.artistTextView.text = song.artist
            binding.durationOverlayTextView.text = AudioUtils.formatDuration(song.duration)
            // 不加载封面以保持轻量，仅占位
            binding.coverImageView.setImageResource(0)
            // 隐藏关联按钮，删除按钮用作"移除"
            binding.associateButton.visibility = android.view.View.GONE
            binding.deleteButton.setOnClickListener { onRemoveClick(song) }
            binding.root.setOnClickListener { onSongClick(song, position) }
        }
    }
}
