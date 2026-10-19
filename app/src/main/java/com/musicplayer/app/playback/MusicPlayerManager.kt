package com.musicplayer.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Looper
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

    /**
     * 已播放曲目索引历史（不含当前曲目，栈顶 = 最近播放的一首）。
     * - next / 自动切歌 / 指定点歌 时压入当前索引
     * - previous 切歌时弹出，保证随机模式下"上一首"能回到真正听过的那首
     * 仅在主线程访问，无需额外同步。
     */
    private val playedHistory = ArrayDeque<Int>()

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

    /**
     * 当前是否持有音频焦点。切歌时只在未持有焦点时才请求，
     * 避免每切一首歌就向系统注册一个新的 AudioFocusRequest（泄漏系统侧焦点记录）。
     */
    private var hasAudioFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_playbackInfo.value.state == PlaybackState.PLAYING) {
                    hadAudioFocusBeforeTransientLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (hadAudioFocusBeforeTransientLoss) {
                    hadAudioFocusBeforeTransientLoss = false
                    play()
                }
            }
        }
    }

    /**
     * 所有播放控制（MediaPlayer、StateFlow、历史栈）都必须在主线程执行。
     * MediaSession 回调 / 按钮点击在主线程；若未来从其他线程（如 Agent IO 线程）
     * 调用，统一切回主线程，杜绝并发操作 MediaPlayer 导致的随机 native 崩溃。
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        hasAudioFocus = false
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

    fun playSong(songId: Long) = onMain {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index == -1) {
            // 列表可能尚未加载完成：挂到一次刷新之后再尝试
            scope.launch {
                playlist = repository.getAllSongs()
                _playbackInfo.value = _playbackInfo.value.copy(
                    playlist = playlist.map { it.id }
                )
                val newIndex = playlist.indexOfFirst { it.id == songId }
                if (newIndex != -1) playTrack(newIndex)
            }
        } else {
            playTrack(index)
        }
    }

    fun playPlaylist(songs: List<Song>, startIndex: Int = 0) = onMain {
        if (songs.isEmpty()) return@onMain
        val index = startIndex.coerceIn(0, songs.lastIndex)
        playlist = songs
        playedHistory.clear()
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

    /**
     * 下一首（主流播放器规则）：
     * - 随机模式：在列表中随机取一首，尽量不与当前曲重复
     * - 顺序 / 单曲循环模式：手动切歌一律向后走，列表末尾回到第 0 首
     * 切歌前当前索引压入播放历史，供 previous() 回退。
     */
    fun next() = onMain {
        if (playlist.isEmpty()) return@onMain
        val info = _playbackInfo.value
        val target = if (info.loopMode == LoopMode.RANDOM) {
            pickRandomIndex(info.currentIndex)
        } else {
            val current = info.currentIndex
            if (current !in playlist.indices) 0 else (current + 1) % playlist.size
        }
        playTrack(target)
    }

    /**
     * 上一首（主流播放器规则）：
     * 1. 当前曲目播放超过 [RESTART_THRESHOLD_MS]（3 秒）→ 回到本曲开头，不切歌
     * 2. 否则优先弹出播放历史，回到真正听过的上一首（随机模式同样有效）
     * 3. 无历史时：当前索引 -1，第 0 首再按则循环到列表末尾
     * 列表被刷新导致历史索引失效时自动丢弃失效项。
     */
    fun previous() = onMain {
        if (playlist.isEmpty()) return@onMain
        val info = _playbackInfo.value
        val current = info.currentIndex

        // 规则 1：超过 3 秒 → 重播当前曲（不改动历史）
        if (current in playlist.indices && info.currentPosition > RESTART_THRESHOLD_MS) {
            startPlaybackInner(current)
            return@onMain
        }

        // 规则 2：丢弃失效历史后取栈顶
        while (playedHistory.isNotEmpty() && playedHistory.last() !in playlist.indices) {
            playedHistory.removeLast()
        }
        val fromHistory = playedHistory.removeLastOrNull()

        // 规则 3：历史为空则按索引回退
        val target = fromHistory ?: when {
            current !in playlist.indices -> 0
            current <= 0 -> playlist.lastIndex
            else -> current - 1
        }
        startPlaybackInner(target)
    }

    /**
     * 切换到指定曲目并维护播放历史：
     * 仅当"有效当前曲 → 另一首曲目"时才把当前索引压栈（重播同曲不压栈）。
     */
    private fun playTrack(index: Int) {
        if (index !in playlist.indices) return
        val current = _playbackInfo.value.currentIndex
        if (current in playlist.indices && current != index) {
            playedHistory.addLast(current)
            if (playedHistory.size > MAX_HISTORY_SIZE) playedHistory.removeFirst()
        }
        startPlaybackInner(index)
    }

    /**
     * 随机选曲：排除当前索引；只有一首歌时直接返回 0。
     * 带重试上限，避免异常状态下死循环。
     */
    private fun pickRandomIndex(current: Int): Int {
        if (playlist.size == 1) return 0
        var r = Random.nextInt(playlist.size)
        var guard = 0
        while (r == current && guard++ < RANDOM_RETRY_MAX) {
            r = Random.nextInt(playlist.size)
        }
        return r
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
        if (!hasAudioFocus) requestAudioFocus()

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
                        try { player.release() } catch (_: Throwable) { }
                        return@setOnPreparedListener
                    }
                    try {
                        _playbackInfo.value = _playbackInfo.value.copy(
                            duration = player.duration.toLong(),
                            state = PlaybackState.PLAYING
                        )
                        player.start()
                        startProgressTracking()
                    } catch (_: Throwable) {
                        _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.ERROR)
                    }
                }

                setOnCompletionListener {
                    if (myToken != playbackToken) return@setOnCompletionListener
                    onSongCompleted()
                }

                setOnErrorListener { player, _, _ ->
                    if (myToken != playbackToken) {
                        try { player.release() } catch (_: Throwable) { }
                        return@setOnErrorListener true
                    }
                    _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.ERROR)
                    try { player.release() } catch (_: Throwable) { }
                    mediaPlayer = null
                    true
                }

                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Throwable) {
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
            try { if (mp.isPlaying) mp.stop() } catch (_: Throwable) { }
            try { mp.release() } catch (_: Throwable) { }
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
                // 单曲循环：从头重播当前曲，不写入播放历史
                if (currentInfo.currentIndex in playlist.indices) {
                    startPlaybackInner(currentInfo.currentIndex)
                }
            }
            LoopMode.RANDOM -> {
                // 自动随机切歌同样压栈，previous() 可回退
                playTrack(pickRandomIndex(currentInfo.currentIndex))
            }
            LoopMode.ORDER -> {
                val nextIndex = currentInfo.currentIndex + 1
                if (nextIndex in playlist.indices) {
                    playTrack(nextIndex)
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
                } catch (_: Throwable) { }
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
        playedHistory.clear()
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
        /** 上一首：当前曲播放超过该时长（毫秒）则回到本曲开头而非切歌 */
        private const val RESTART_THRESHOLD_MS = 3000L

        /** 播放历史最大长度，超出后丢弃最早的记录 */
        private const val MAX_HISTORY_SIZE = 500

        /** 随机选曲"避开当前曲"的最大重试次数，防止极端情况下死循环 */
        private const val RANDOM_RETRY_MAX = 8

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
