package com.musicplayer.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createTime DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    /** 歌单列表 + 各自歌曲数（子查询计数） */
    @Query(
        "SELECT playlists.*, " +
            "(SELECT COUNT(*) FROM playlist_song_ref WHERE playlistId = playlists.id) AS count " +
            "FROM playlists ORDER BY createTime DESC"
    )
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    /** 删除歌单时级联清理关联记录 */
    @Query("DELETE FROM playlist_song_ref WHERE playlistId = :playlistId")
    suspend fun deleteRefsByPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_song_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    /** 当前歌单内最大 addOrder，用于追加歌曲时计算顺序 */
    @Query("SELECT MAX(addOrder) FROM playlist_song_ref WHERE playlistId = :playlistId")
    suspend fun getMaxAddOrder(playlistId: Long): Int?

    /** 歌单内歌曲（按加入顺序，Flow 观察） */
    @Query(
        "SELECT songs.* FROM songs INNER JOIN playlist_song_ref " +
            "ON songs.id = playlist_song_ref.songId " +
            "WHERE playlist_song_ref.playlistId = :playlistId " +
            "ORDER BY playlist_song_ref.addOrder ASC"
    )
    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>>

    /** 歌单内歌曲快照（一次性，用于播放） */
    @Query(
        "SELECT songs.* FROM songs INNER JOIN playlist_song_ref " +
            "ON songs.id = playlist_song_ref.songId " +
            "WHERE playlist_song_ref.playlistId = :playlistId " +
            "ORDER BY playlist_song_ref.addOrder ASC"
    )
    suspend fun getSongsInPlaylistSnapshot(playlistId: Long): List<Song>
}
