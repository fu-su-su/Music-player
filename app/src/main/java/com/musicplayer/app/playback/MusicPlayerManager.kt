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

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    private var playlist: List<Song> = emptyList()

    // ---------- 睡眠定时 ----------
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)

    /** 睡眠定时剩余毫秒；null 表示未开启。每秒更新，供设置面板显示。 */
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()
    private var sleepAfterCurrent = false

    // ---------- 音频焦点：来电/其他音频抢占 ----------
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hadAudioFocusBeforeTransientLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点：停止播放并放弃
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 来电等短暂抢占：暂停，待恢复
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

    /** 请求音频焦点，返回是否成功。 */
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

    /** 启动媒体前台服务（通知栏/锁屏控制）。 */
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

    /**
     * 更新某歌曲在内存播放队列中的自定义背景路径（与 DB 更新配套调用），
     * 使 getCurrentSong() 立即返回最新值，播放页据此切换背景。
     */
    fun updateSongCustomBackground(songId: Long, path: String?) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index == -1) return
        playlist = playlist.toMutableList().apply {
            set(index, this[index].copy(customBackgroundPath = path))
        }
    }

    fun getPlaylist(): List<Song> = playlist

    fun playSong(songId: Long) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index == -1) {
            // 不在播放列表中，尝试刷新
            refreshPlaylist()
            val newIndex = playlist.indexOfFirst { it.id == songId }
            if (newIndex == -1) return
            startPlayback(newIndex)
        } else {
            startPlayback(index)
        }
    }

    /**
     * 以指定歌曲列表作为播放队列播放（用于自定义歌单）。
     * 临时替换内部 playlist，调用 [refreshPlaylist] 可恢复全量队列。
     */
    fun playPlaylist(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        playlist = songs
        _playbackInfo.value = _playbackInfo.value.copy(
            playlist = playlist.map { it.id }
        )
        startPlayback(index)
    }

    private fun startPlayback(index: Int) {
        if (index < 0 || index >= playlist.size) return
        val song = playlist[index]

        stopInternal()
        // 请求音频焦点
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

        // 启动前台服务（通知栏/锁屏控制），此时 playbackInfo 已含新歌曲信息
        startPlaybackService()

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(song.path)
                setOnPreparedListener {
                    _playbackInfo.value = _playbackInfo.value.copy(
                        duration = it.duration.toLong(),
                        state = PlaybackState.PLAYING
                    )
                    it.start()
                    startProgressTracking()
                }
                setOnCompletionListener {
                    onSongCompleted()
                }
                setOnErrorListener { _, _, _ ->
                    _playbackInfo.value = _playbackInfo.value.copy(
                        state = PlaybackState.ERROR
                    )
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _playbackInfo.value = _playbackInfo.value.copy(
                state = PlaybackState.ERROR
            )
        }
    }

    private fun onSongCompleted() {
        // 睡眠定时"播完当前"：无论循环模式，本曲结束后暂停并清除标志
        if (sleepAfterCurrent) {
            sleepAfterCurrent = false
            pause()
            return
        }
        val currentInfo = _playbackInfo.value
        when (currentInfo.loopMode) {
            LoopMode.SINGLE -> {
                // 单曲循环：重新播放当前
                seekTo(0L)
                play()
            }
            LoopMode.RANDOM -> {
                // 随机：随机选择一首
                if (playlist.isNotEmpty()) {
                    val randomIndex = Random.nextInt(playlist.size)
                    startPlayback(randomIndex)
                }
            }
            LoopMode.ORDER -> {
                // 顺序：播放下一首
                val nextIndex = currentInfo.currentIndex + 1
                if (nextIndex < playlist.size) {
                    startPlayback(nextIndex)
                } else {
                    // 播放完了
                    _playbackInfo.value = _playbackInfo.value.copy(
                        state = PlaybackState.COMPLETED,
                        currentPosition = 0L
                    )
                    stopProgressTracking()
                }
            }
        }
    }

    fun play() {
        val currentInfo = _playbackInfo.value
        if (currentInfo.state == PlaybackState.PAUSED) {
            mediaPlayer?.start()
            _playbackInfo.value = _playbackInfo.value.copy(
                state = PlaybackState.PLAYING
            )
            startProgressTracking()
        } else if (currentInfo.state == PlaybackState.IDLE ||
            currentInfo.state == PlaybackState.STOPPED ||
            currentInfo.state == PlaybackState.COMPLETED ||
            currentInfo.state == PlaybackState.ERROR) {
            if (currentInfo.currentIndex >= 0 && currentInfo.currentIndex < playlist.size) {
                startPlayback(currentInfo.currentIndex)
            } else if (playlist.isNotEmpty()) {
                startPlayback(0)
            }
        }
    }

    fun pause() {
        if (_playbackInfo.value.state == PlaybackState.PLAYING) {
            mediaPlayer?.pause()
            _playbackInfo.value = _playbackInfo.value.copy(
                state = PlaybackState.PAUSED
            )
            stopProgressTracking()
        }
    }

    fun togglePlayPause() {
        if (_playbackInfo.value.state == PlaybackState.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun next() {
        val currentInfo = _playbackInfo.value
        val nextIndex = if (currentInfo.loopMode == LoopMode.RANDOM && playlist.isNotEmpty()) {
            Random.nextInt(playlist.size)
        } else {
            (currentInfo.currentIndex + 1).coerceIn(0, playlist.size - 1)
        }
        if (nextIndex >= 0 && nextIndex < playlist.size) {
            startPlayback(nextIndex)
        }
    }

    fun previous() {
        val currentInfo = _playbackInfo.value
        val prevIndex = if (currentInfo.currentPosition > 3000) {
            // 超过3秒则回到当前歌曲开头
            currentInfo.currentIndex
        } else {
            (currentInfo.currentIndex - 1).coerceIn(0, playlist.size - 1)
        }
        if (prevIndex >= 0 && prevIndex < playlist.size) {
            startPlayback(prevIndex)
        }
    }

    fun seekTo(position: Long) {
        mediaPlayer?.seekTo(position.toInt())
        _playbackInfo.value = _playbackInfo.value.copy(
            currentPosition = position
        )
    }

    fun setLoopMode(mode: LoopMode) {
        _playbackInfo.value = _playbackInfo.value.copy(
            loopMode = mode
        )
    }

    fun toggleLoopMode() {
        val currentMode = _playbackInfo.value.loopMode
        setLoopMode(currentMode.next())
    }

    // ---------- 睡眠定时 ----------

    /**
     * 启动睡眠定时：倒计时 [minutes] 分钟后自动暂停。
     * 重复调用会覆盖之前的定时。
     */
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

    /** 播完当前歌曲后暂停（下一次 onSongCompleted 触发时生效，无论循环模式）。 */
    fun setSleepAfterCurrentSong() {
        sleepAfterCurrent = true
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
        sleepAfterCurrent = false
    }

    /** 是否处于"播完当前后暂停"模式。 */
    fun isSleepAfterCurrent(): Boolean = sleepAfterCurrent

    private fun stopInternal() {
        stopProgressTracking()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
    }

    fun stop() {
        stopInternal()
        abandonAudioFocus()
        _playbackInfo.value = _playbackInfo.value.copy(
            state = PlaybackState.STOPPED,
            currentPosition = 0L
        )
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            while (_playbackInfo.value.state == PlaybackState.PLAYING) {
                try {
                    val position = mediaPlayer?.currentPosition?.toLong()
                        ?: _playbackInfo.value.currentPosition
                    _playbackInfo.value = _playbackInfo.value.copy(
                        currentPosition = position
                    )
                } catch (e: Exception) {
                    // ignore
                }
                delay(100)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopInternal()
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
