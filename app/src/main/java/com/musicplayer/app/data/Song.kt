package com.musicplayer.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,           // 音频文件绝对路径
    val title: String,          // 歌名
    val artist: String,         // 艺术家
    val duration: Long,         // 时长（毫秒）
    val lyricPath: String? = null,   // 歌词文件路径，无则为null
    val coverPath: String? = null,   // 图片文件路径，无则为null
    val addTime: Long = System.currentTimeMillis(),  // 添加时间
    val customBackgroundPath: String? = null  // 该歌曲的自定义播放页背景图，null 则用封面/主题
)
