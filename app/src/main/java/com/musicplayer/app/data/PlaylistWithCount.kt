package com.musicplayer.app.data

import androidx.room.Embedded

/** 歌单 + 其内歌曲数（Room 查询结果 POJO） */
data class PlaylistWithCount(
    @Embedded val playlist: Playlist,
    val count: Int
)
