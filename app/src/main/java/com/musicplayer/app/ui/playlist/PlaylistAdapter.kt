package com.musicplayer.app.ui.playlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistWithCount
import com.musicplayer.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit
) : ListAdapter<PlaylistWithCount, PlaylistAdapter.PlaylistViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PlaylistWithCount>() {
            override fun areItemsTheSame(oldItem: PlaylistWithCount, newItem: PlaylistWithCount) =
                oldItem.playlist.id == newItem.playlist.id
            override fun areContentsTheSame(oldItem: PlaylistWithCount, newItem: PlaylistWithCount) =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaylistWithCount) {
            binding.playlistName.text = item.playlist.name
            binding.playlistCount.text = itemView.context.getString(
                com.musicplayer.app.R.string.playlist_song_count,
                item.count
            )
            binding.root.setOnClickListener { onClick(item.playlist) }
            binding.root.setOnLongClickListener { onLongClick(item.playlist); true }
        }
    }
}
