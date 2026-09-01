package com.musicplayer.app.playback

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
    ERROR
}

enum class LoopMode {
    ORDER,    // 顺序播放
    SINGLE,   // 单曲循环
    RANDOM;   // 随机播放

    fun next(): LoopMode = when (this) {
        ORDER -> SINGLE
        SINGLE -> RANDOM
        RANDOM -> ORDER
    }
}

data class PlaybackInfo(
    val currentSongId: Long? = null,
    val currentSongPath: String? = null,
    val currentSongTitle: String = "",
    val currentSongArtist: String = "",
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val state: PlaybackState = PlaybackState.IDLE,
    val loopMode: LoopMode = LoopMode.ORDER,
    val playlist: List<Long> = emptyList(),
    val currentIndex: Int = -1
)
