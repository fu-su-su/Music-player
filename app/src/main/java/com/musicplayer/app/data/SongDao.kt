package com.musicplayer.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsSortedByName(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY artist ASC")
    fun getAllSongsSortedByArtist(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY addTime DESC")
    fun getAllSongsSortedByAddTime(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :keyword || '%' OR artist LIKE '%' || :keyword || '%' ORDER BY title ASC")
    fun searchSongs(keyword: String): Flow<List<Song>>

    /** 设置/清除歌曲的自定义播放页背景图（path 为 null 时清除）。 */
    @Query("UPDATE songs SET customBackgroundPath = :path WHERE id = :songId")
    suspend fun updateCustomBackgroundPath(songId: Long, path: String?)

    @Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE path = :path")
    suspend fun getSongByPath(path: String): Song?

    @Query("SELECT * FROM songs ORDER BY id")
    fun observeAllSongs(): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>): List<Long>

    @Update
    suspend fun updateSong(song: Song)

    @Delete
    suspend fun deleteSong(song: Song)

    @Query("SELECT COUNT(*) FROM songs WHERE path = :path")
    suspend fun existsByPath(path: String): Int

    @Query("SELECT COUNT(*) FROM songs WHERE title = :title AND artist = :artist AND duration = :duration")
    suspend fun existsByTitleArtistDuration(title: String, artist: String, duration: Long): Int

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: Long)
}
