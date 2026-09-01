package com.musicplayer.app.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.data.PlaylistWithCount
import com.musicplayer.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    val playlistsWithCount: LiveData<List<PlaylistWithCount>> =
        repository.getAllPlaylistsWithCount().asLiveData()

    // 当前查看的歌单 id，驱动歌曲列表
    private val _currentPlaylistId = MutableStateFlow(-1L)

    val songsInPlaylist: LiveData<List<Song>> = _currentPlaylistId.flatMapLatest { id ->
        if (id <= 0L) flowOf(emptyList())
        else repository.getSongsInPlaylist(id)
    }.asLiveData()

    fun setCurrentPlaylist(id: Long) {
        _currentPlaylistId.value = id
    }

    fun createPlaylist(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.createPlaylist(name)
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renamePlaylist(id, name)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePlaylist(playlist)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    /** 获取全部歌曲，用于"添加歌曲"多选弹窗 */
    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        repository.getAllSongs()
    }

    suspend fun getPlaylistName(id: Long): String? = withContext(Dispatchers.IO) {
        repository.getPlaylistById(id)?.name
    }

    suspend fun getSongsInPlaylistSnapshot(id: Long): List<Song> = withContext(Dispatchers.IO) {
        // 用于从歌单播放：读取当前快照
        repository.getSongsInPlaylistSnapshot(id)
    }
}
