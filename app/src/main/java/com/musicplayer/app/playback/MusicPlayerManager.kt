package com.musicplayer.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.core.content.ContextCompat
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Song
import com.musicplayer.app.service.MusicPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class MusicPlayerManager private constructor(private val context: Context) {

    private val repository = MusicRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 当前活跃的 MediaPlayer 实例。
     * 每次 startPlaybackInner 会创建新的并赋值给此字段，旧的被 release。
     */
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    /**
     * 全局代币：每次 startPlaybackInner 递增。
     * onPrepared / onCompletion / onError 回调携带的 token 与当前值不同时，
     * 说明是旧 MediaPlayer 的延迟回调 → 直接丢弃，不执行任何操作。
     * 这是防止"慢速连点也闪退"的核心机制——即使重入锁放开后旧的异步回调到来也不会崩溃。
     */
    @Volatile
    private var playbackToken: Int = 0

    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    private var playlist: List<Song> = emptyList()

    // ---------- 睡眠定时 ----------
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()
    private var sleepAfterCurrent = false

    // ---------- 音频焦点 ----------
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hadAudioFocusBeforeTransientLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> { stop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_playbackInfo.value.state == PlaybackState.PLAYING) {
                    hadAudioFocusBeforeTransientLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (hadAudioFocusBeforeTransientLoss) {
                    hadAudioFocusBeforeTransientLoss = false
                    play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun startPlaybackService() {
        ContextCompat.startForegroundService(
            context, Intent(context, MusicPlaybackService::class.java)
        )
    }

    init {
        scope.launch {
            playlist = repository.getAllSongs()
            _playbackInfo.value = _playbackInfo.value.copy(
                playlist = playlist.map { it.id }
            )
        }
    }

    fun refreshPlaylist() {
        scope.launch {
            playlist = repository.getAllSongs()
            _playbackInfo.value = _playbackInfo.value.copy(
                playlist = playlist.map { it.id }
            )
        }
    }

    fun getCurrentSong(): Song? {
        val id = _playbackInfo.value.currentSongId ?: return null
        return playlist.find { it.id == id }
    }

    fun updateSongCustomBackground(songId: Long, path: String?) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index == -1) return
        playlist = playlist.toMutableList().apply {
            set(index, this[index].copy(customBackgroundPath = path))
        }
    }

    fun getPlaylist(): List<Song> = playlist

    // ================================================================
    //  公开 API
    // ================================================================

    fun playSong(songId: Long) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index == -1) {
            refreshPlaylist()
            val newIndex = playlist.indexOfFirst { it.id == songId }
            if (newIndex == -1) return
            startPlaybackInner(newIndex)
        } else {
            startPlaybackInner(index)
        }
    }

    fun playPlaylist(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        playlist = songs
        _playbackInfo.value = _playbackInfo.value.copy(
            playlist = playlist.map { it.id }
        )
        startPlaybackInner(index)
    }

    fun play() {
        val currentInfo = _playbackInfo.value
        when (currentInfo.state) {
            PlaybackState.PAUSED -> {
                try {
                    mediaPlayer?.let { mp ->
                        mp.start()
                        _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.PLAYING)
                        startProgressTracking()
                    }
                } catch (_: Exception) {
                    if (currentInfo.currentIndex in playlist.indices) {
                        startPlaybackInner(currentInfo.currentIndex)
                    }
                }
            }
            PlaybackState.IDLE, PlaybackState.STOPPED,
            PlaybackState.COMPLETED, PlaybackState.ERROR -> {
                if (playlist.isEmpty()) return
                val idx = if (currentInfo.currentIndex in playlist.indices)
                    currentInfo.currentIndex else 0
                startPlaybackInner(idx)
            }
            else -> { /* PLAYING 等状态不处理 */ }
        }
    }

    fun pause() {
        if (_playbackInfo.value.state == PlaybackState.PLAYING) {
            try {
                mediaPlayer?.pause()
                _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.PAUSED)
                stopProgressTracking()
            } catch (_: Exception) { }
        }
    }

    fun togglePlayPause() {
        if (_playbackInfo.value.state == PlaybackState.PLAYING) pause() else play()
    }

    fun next() {
        if (playlist.isEmpty()) return
        val currentInfo = _playbackInfo.value
        val nextIndex = if (currentInfo.loopMode == LoopMode.RANDOM) {
            if (playlist.size > 1) {
                var r: Int
                do { r = Random.nextInt(playlist.size) } while (r == currentInfo.currentIndex)
                r
            } else 0
        } else {
            val ni = currentInfo.currentIndex + 1
            if (ni >= playlist.size) 0 else ni   // 顺序末尾循环到第 0 首
        }
        startPlaybackInner(nextIndex)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        val currentInfo = _playbackInfo.value
        val prevIndex = if (currentInfo.currentPosition > 3000) {
            currentInfo.currentIndex      // 超过3秒回到当前歌曲开头
        } else {
            val pi = currentInfo.currentIndex - 1
            if (pi < 0) playlist.lastIndex else pi
        }
        startPlaybackInner(prevIndex)
    }

    fun seekTo(position: Long) {
        try {
            mediaPlayer?.seekTo(position.toInt())
        } catch (_: Exception) { }
        _playbackInfo.value = _playbackInfo.value.copy(currentPosition = position)
    }

    fun setLoopMode(mode: LoopMode) {
        _playbackInfo.value = _playbackInfo.value.copy(loopMode = mode)
    }

    fun toggleLoopMode() {
        setLoopMode(_playbackInfo.value.loopMode.next())
    }

    // ================================================================
    //  核心播放逻辑（重写）
    // ================================================================

    /**
     * 内部播放启动。使用代币机制保证：
     * - 旧 MediaPlayer 的 onPrepared/onCompletion/onError 回调到来时，
     *   代币不匹配 → 回调被丢弃，不会对已释放的 player 执行操作。
     * - 无论慢速还是快速点击，都不会崩溃。
     */
    private fun startPlaybackInner(index: Int) {
        if (index !in playlist.indices) return

        // 1. 递增代币 —— 旧的异步回调此后全部失效
        val myToken = ++playbackToken

        // 2. 释放旧 MediaPlayer（安全释放，吞掉所有异常）
        releasePlayerSafely()

        // 3. 更新状态
        val song = playlist[index]
        requestAudioFocus()

        _playbackInfo.value = _playbackInfo.value.copy(
            currentSongId = song.id,
            currentSongPath = song.path,
            currentSongTitle = song.title,
            currentSongArtist = song.artist,
            currentPosition = 0L,
            duration = song.duration,
            currentIndex = index,
            state = PlaybackState.IDLE
        )

        startPlaybackService()

        // 4. 创建新 MediaPlayer
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(song.path)

                setOnPreparedListener { player ->
                    // 代币不匹配 = 这是旧 MediaPlayer 的延迟回调 → 丢弃
                    if (myToken != playbackToken) {
                        try { player.release() } catch (_: Exception) { }
                        return@setOnPreparedListener
                    }
                    try {
                        _playbackInfo.value = _playbackInfo.value.copy(
                            duration = player.duration.toLong(),
                            state = PlaybackState.PLAYING
                        )
                        player.start()
                        startProgressTracking()
                    } catch (_: Exception) {
                        _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.ERROR)
                    }
                }

                setOnCompletionListener {
                    if (myToken != playbackToken) return@setOnCompletionListener
                    onSongCompleted()
                }

                setOnErrorListener { player, _, _ ->
                    if (myToken != playbackToken) {
                        try { player.release() } catch (_: Exception) { }
                        return@setOnErrorListener true
                    }
                    _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.ERROR)
                    try { player.release() } catch (_: Exception) { }
                    mediaPlayer = null
                    true
                }

                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            e.printStackTrace()
            _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.ERROR)
        }
    }

    /**
     * 安全释放 MediaPlayer：吞掉 stop/release 的所有异常，
     * 确保不会因为 MediaPlayer 状态不合法而崩溃。
     */
    private fun releasePlayerSafely() {
        stopProgressTracking()
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) { }
            try { mp.release() } catch (_: Exception) { }
        }
    }

    private fun onSongCompleted() {
        if (sleepAfterCurrent) {
            sleepAfterCurrent = false
            pause()
            return
        }
        val currentInfo = _playbackInfo.value
        when (currentInfo.loopMode) {
            LoopMode.SINGLE -> {
                // 单曲循环：从头播放当前歌曲（用代币机制重新启动）
                startPlaybackInner(currentInfo.currentIndex)
            }
            LoopMode.RANDOM -> {
                if (playlist.isNotEmpty()) {
                    val randomIndex = if (playlist.size > 1) {
                        var r: Int
                        do { r = Random.nextInt(playlist.size) } while (r == currentInfo.currentIndex)
                        r
                    } else 0
                    startPlaybackInner(randomIndex)
                }
            }
            LoopMode.ORDER -> {
                val nextIndex = currentInfo.currentIndex + 1
                if (nextIndex < playlist.size) {
                    startPlaybackInner(nextIndex)
                } else {
                    _playbackInfo.value = _playbackInfo.value.copy(
                        state = PlaybackState.COMPLETED,
                        currentPosition = 0L
                    )
                }
            }
        }
    }

    // ================================================================
    //  进度追踪
    // ================================================================

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            while (_playbackInfo.value.state == PlaybackState.PLAYING) {
                try {
                    val pos = mediaPlayer?.currentPosition?.toLong()
                    if (pos != null) {
                        _playbackInfo.value = _playbackInfo.value.copy(currentPosition = pos)
                    }
                } catch (_: Exception) { }
                delay(200)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    // ================================================================
    //  睡眠定时
    // ================================================================

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerJob = scope.launch {
            val totalMs = minutes * 60_000L
            var remaining = totalMs
            while (remaining > 0) {
                _sleepTimerRemaining.value = remaining
                delay(1_000)
                remaining -= 1_000
            }
            _sleepTimerRemaining.value = null
            pause()
        }
    }

    fun setSleepAfterCurrentSong() { sleepAfterCurrent = true }
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
        sleepAfterCurrent = false
    }
    fun isSleepAfterCurrent(): Boolean = sleepAfterCurrent

    // ================================================================
    //  生命周期
    // ================================================================

    fun stop() {
        releasePlayerSafely()
        abandonAudioFocus()
        _playbackInfo.value = _playbackInfo.value.copy(
            state = PlaybackState.STOPPED,
            currentPosition = 0L
        )
    }

    fun release() {
        releasePlayerSafely()
        abandonAudioFocus()
    }

    companion object {
        @Volatile
        private var INSTANCE: MusicPlayerManager? = null

        fun getInstance(context: Context): MusicPlayerManager {
            return INSTANCE ?: synchronized(this) {
                val instance = MusicPlayerManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
