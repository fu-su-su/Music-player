package com.musicplayer.app.ui.player

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivityPlayerBinding
import com.musicplayer.app.playback.LoopMode
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.playback.PlaybackState
import com.musicplayer.app.util.AudioUtils
import com.musicplayer.app.util.ImageUtils
import com.musicplayer.app.util.LyricParser
import com.musicplayer.app.util.LyricLine
import com.musicplayer.app.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: MusicPlayerManager
    private lateinit var lyricAdapter: LyricAdapter
    private lateinit var lyricLayoutManager: LinearLayoutManager

    private var lyrics: List<LyricLine> = emptyList()
    private var lastHighlightIndex = -1
    private var isUserSeeking = false
    private var loadedSongId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applySystemBars(this, isPlayerActivity = true)

        playerManager = MusicPlayerManager.getInstance(this)

        setupViews()
        observePlayback()
    }

    override fun onResume() {
        super.onResume()
        // 从设置返回后立即生效：歌词字号与自定义背景都可能在离开期间被修改
        lyricAdapter = LyricAdapter(ThemeManager.getLyricFontSize(this)).also {
            it.submitList(lyrics)
            it.updateHighlight(lastHighlightIndex)
        }
        binding.lyricRecyclerView.adapter = lyricAdapter
        loadedSongId = null
        playerManager.playbackInfo.value.currentSongId?.let { loadSongData(it) }
    }

    private fun setupViews() {
        // 歌词RecyclerView（字号三档由用户设置决定）
        lyricAdapter = LyricAdapter(ThemeManager.getLyricFontSize(this))
        lyricLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        binding.lyricRecyclerView.apply {
            layoutManager = lyricLayoutManager
            adapter = lyricAdapter
            setHasFixedSize(false)
        }

        // 进度条
        binding.progressSeekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.currentTimeTextView.text = AudioUtils.formatDuration(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    seekBar?.let { playerManager.seekTo(it.progress.toLong()) }
                    isUserSeeking = false
                }
            }
        )

        // 控制按钮
        binding.playPauseButton.setOnClickListener { playerManager.togglePlayPause() }
        binding.previousButton.setOnClickListener { playerManager.previous() }
        binding.nextButton.setOnClickListener { playerManager.next() }
        binding.loopModeButton.setOnClickListener { playerManager.toggleLoopMode() }
        binding.backButton.setOnClickListener { finish() }
    }

    /**
     * 渐隐遮罩已移除：图片区域只保留匹配的封面图片，不再叠加黑色遮罩。
     */

    private fun observePlayback() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerManager.playbackInfo.collect { info ->
                    // 更新歌曲信息
                    binding.titleTextView.text = info.currentSongTitle
                    binding.artistTextView.text = info.currentSongArtist

                    // 更新总时长与进度条
                    binding.totalTimeTextView.text = AudioUtils.formatDuration(info.duration)
                    binding.progressSeekBar.max = info.duration.toInt()

                    if (!isUserSeeking) {
                        binding.currentTimeTextView.text = AudioUtils.formatDuration(info.currentPosition)
                        binding.progressSeekBar.progress = info.currentPosition.toInt()
                    }

                    // 播放/暂停按钮图标
                    val iconRes = if (info.state == PlaybackState.PLAYING) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    }
                    binding.playPauseButton.setImageResource(iconRes)

                    // 循环模式图标
                    updateLoopModeIcon(info.loopMode)

                    // 歌词高亮跟随
                    updateLyricHighlight(info.currentPosition)

                    // 切歌时重新加载封面与歌词
                    loadSongData(info.currentSongId)
                }
            }
        }
    }

    private fun loadSongData(songId: Long?) {
        if (songId == null || songId == loadedSongId) return
        loadedSongId = songId

        val song = playerManager.getCurrentSong() ?: return

        // 加载背景/封面（子线程读取文件，主线程更新UI）
        // 优先级：该歌曲的自定义背景 > 封面文件 > 内嵌封面
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    when {
                        song.customBackgroundPath != null ->
                            ImageUtils.loadBitmapFromPath(song.customBackgroundPath)
                        song.coverPath != null ->
                            ImageUtils.loadBitmapFromPath(song.coverPath)
                        else -> {
                            val bytes = AudioUtils.getEmbeddedCover(song.path)
                            ImageUtils.loadBitmapFromBytes(bytes)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                Glide.with(this@PlayerActivity)
                    .load(bitmap)
                    .apply(
                        RequestOptions()
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                    )
                    .into(binding.coverImageView)
            } else {
                binding.coverImageView.setImageDrawable(null)
            }
        }

        // 加载歌词（子线程解析LRC文件）
        lifecycleScope.launch {
            lyrics = withContext(Dispatchers.IO) {
                try {
                    val lyricPath = song.lyricPath
                    if (lyricPath != null) LyricParser.parseLyricFile(lyricPath) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (lyrics.isEmpty()) {
                // 无歌词：歌词区保持完全空白
                binding.lyricRecyclerView.visibility = View.GONE
            } else {
                binding.lyricRecyclerView.visibility = View.VISIBLE
                lyricAdapter.submitList(lyrics)
            }
            lastHighlightIndex = -1
            lyricAdapter.updateHighlight(-1)
        }
    }

    private fun updateLyricHighlight(currentPosition: Long) {
        if (lyrics.isEmpty()) return

        val index = LyricParser.findCurrentLineIndex(lyrics, currentPosition)
        if (index != -1 && index != lastHighlightIndex) {
            lastHighlightIndex = index
            lyricAdapter.updateHighlight(index)
            smoothScrollToCenter(index)
        }
    }

    /**
     * 平滑滚动到指定位置并居中显示。
     */
    private fun smoothScrollToCenter(position: Int) {
        if (position < 0) return
        val smoothScroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_ANY

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                // 让目标行居中对齐
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        smoothScroller.targetPosition = position
        try {
            lyricLayoutManager.startSmoothScroll(smoothScroller)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateLoopModeIcon(mode: LoopMode) {
        val iconRes = when (mode) {
            LoopMode.ORDER -> R.drawable.ic_loop_order
            LoopMode.SINGLE -> R.drawable.ic_loop_single
            LoopMode.RANDOM -> R.drawable.ic_loop_random
        }
        val tintColor = when (mode) {
            LoopMode.ORDER -> R.color.text_secondary
            LoopMode.SINGLE, LoopMode.RANDOM -> R.color.primary
        }
        val contentDesc = when (mode) {
            LoopMode.ORDER -> getString(R.string.loop_order)
            LoopMode.SINGLE -> getString(R.string.loop_single)
            LoopMode.RANDOM -> getString(R.string.loop_random)
        }

        binding.loopModeButton.setImageResource(iconRes)
        binding.loopModeButton.contentDescription = contentDesc
        binding.loopModeButton.setColorFilter(
            ContextCompat.getColor(this, tintColor),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }
}
