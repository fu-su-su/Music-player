package com.musicplayer.app.data

import androidx.room.Entity

/**
 * 歌单与歌曲多对多关联表。
 * 复合主键 (playlistId, songId) 保证同一歌单内一首歌只关联一次。
 * addOrder 用于保留加入顺序。
 */
@Entity(tableName = "playlist_song_ref", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val addOrder: Int = 0
)
