package com.musicplayer.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class MusicRepository(context: Context) {
    private val songDao = MusicDatabase.getDatabase(context).songDao()
    private val playlistDao = MusicDatabase.getDatabase(context).playlistDao()

    fun getSongsSortedByName(): Flow<List<Song>> = songDao.getAllSongsSortedByName()

    fun getSongsSortedByArtist(): Flow<List<Song>> = songDao.getAllSongsSortedByArtist()

    fun getSongsSortedByAddTime(): Flow<List<Song>> = songDao.getAllSongsSortedByAddTime()

    fun searchSongs(keyword: String): Flow<List<Song>> = songDao.searchSongs(keyword)

    fun observeAllSongs(): Flow<List<Song>> = songDao.observeAllSongs()

    suspend fun getAllSongs(): List<Song> = songDao.getAllSongs()

    suspend fun getSongById(id: Long): Song? = songDao.getSongById(id)

    suspend fun getSongByPath(path: String): Song? = songDao.getSongByPath(path)

    suspend fun insertSong(song: Song): Long = songDao.insertSong(song)

    suspend fun insertSongs(songs: List<Song>): List<Long> = songDao.insertSongs(songs)

    suspend fun updateSong(song: Song) = songDao.updateSong(song)

    suspend fun deleteSong(song: Song) = songDao.deleteSong(song)

    suspend fun existsByPath(path: String): Boolean = songDao.existsByPath(path) > 0

    suspend fun existsByTitleArtistDuration(title: String, artist: String, duration: Long): Boolean =
        songDao.existsByTitleArtistDuration(title, artist, duration) > 0

    suspend fun deleteSongById(songId: Long) = songDao.deleteSongById(songId)

    /** 设置/清除歌曲的自定义播放页背景图。 */
    suspend fun updateCustomBackgroundPath(songId: Long, path: String?) =
        songDao.updateCustomBackgroundPath(songId, path)

    // ---------- 播放列表 ----------

    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithCount>> =
        playlistDao.getAllPlaylistsWithCount()

    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>> =
        playlistDao.getSongsInPlaylist(playlistId)

    suspend fun getSongsInPlaylistSnapshot(playlistId: Long): List<Song> =
        playlistDao.getSongsInPlaylistSnapshot(playlistId)

    suspend fun getPlaylistById(id: Long): Playlist? = playlistDao.getPlaylistById(id)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(Playlist(name = name.trim()))

    suspend fun renamePlaylist(playlistId: Long, name: String) =
        playlistDao.renamePlaylist(playlistId, name.trim())

    suspend fun deletePlaylist(playlist: Playlist) {
        playlistDao.deleteRefsByPlaylist(playlist.id)
        playlistDao.deletePlaylist(playlist)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val nextOrder = (playlistDao.getMaxAddOrder(playlistId) ?: -1) + 1
        playlistDao.addSongToPlaylist(
            PlaylistSongCrossRef(playlistId = playlistId, songId = songId, addOrder = nextOrder)
        )
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        playlistDao.removeSongFromPlaylist(playlistId, songId)
}
