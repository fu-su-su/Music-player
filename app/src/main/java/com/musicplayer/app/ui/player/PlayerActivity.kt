package com.musicplayer.app.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
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
    private var isPureMode = false  // 纯净模式：控件隐藏 + 沉浸式全屏

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applySystemBars(this, isPlayerActivity = true)

        playerManager = MusicPlayerManager.getInstance(this)

        setupViews()
        applyPlayerInsets()
        observePlayback()
    }

    override fun onResume() {
        super.onResume()
        // 从设置返回后立即生效：歌词字号与自定义背景都可能在离开期间被修改
        try { binding.lyricRecyclerView.stopScroll() } catch (_: Exception) { }
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
        binding.songDetailsButton.setOnClickListener { showSongDetails() }
        // 纯净模式：⌄ 收起控件 → 沉浸式全屏 + 右下角上箭头还原
        binding.collapseButton.setOnClickListener { setPureMode(true) }
        binding.expandButton.setOnClickListener { setPureMode(false) }
    }

    /**
     * 渐隐遮罩已移除：图片区域只保留匹配的封面图片，不再叠加黑色遮罩。
     */

    private fun observePlayback() {
        var lastSongId: Long? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerManager.playbackInfo.collect { info ->
                    // 切歌时：重置所有依赖旧 progress 的 UI/状态（防"变成快进/快退"）
                    if (info.currentSongId != lastSongId) {
                        lastSongId = info.currentSongId
                        isUserSeeking = false
                        binding.progressSeekBar.progress = 0
                        binding.currentTimeTextView.text =
                            com.musicplayer.app.util.AudioUtils.formatDuration(0L)
                    }

                    // 更新歌曲信息
                    binding.titleTextView.text = info.currentSongTitle
                    binding.artistTextView.text = info.currentSongArtist

                    // 更新总时长与进度条
                    binding.totalTimeTextView.text = AudioUtils.formatDuration(info.duration)
                    if (binding.progressSeekBar.max != info.duration.toInt()) {
                        binding.progressSeekBar.max = info.duration.toInt()
                    }

                    if (!isUserSeeking) {
                        binding.currentTimeTextView.text =
                            AudioUtils.formatDuration(info.currentPosition)
                        binding.progressSeekBar.progress = info.currentPosition.toInt()
                    }

                    // 播放/暂停按钮图标
                    val iconRes = if (info.state == PlaybackState.PLAYING) {
                        R.drawable.ic_pause
                    } else {
                        R.drawable.ic_play
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
                // 先清除高亮和滚动状态，再 submitList，避免 DiffUtil 布局时 attach 已分离的 ViewHolder
                lastHighlightIndex = -1
                lyricAdapter.updateHighlight(-1)
                // 先停止任何正在进行的滚动
                binding.lyricRecyclerView.stopScroll()
                lyricAdapter.submitList(lyrics) {
                    // submitList 完成回调：在下一帧恢复高亮
                    binding.lyricRecyclerView.post {
                        lyricAdapter.updateHighlight(lastHighlightIndex)
                    }
                }
            }
        }
    }

    private fun updateLyricHighlight(currentPosition: Long) {
        if (lyrics.isEmpty()) return
        if (binding.lyricRecyclerView.visibility != View.VISIBLE) return

        val index = LyricParser.findCurrentLineIndex(lyrics, currentPosition)
        if (index != -1 && index != lastHighlightIndex) {
            lastHighlightIndex = index
            try {
                lyricAdapter.updateHighlight(index)
            } catch (_: Exception) { }
            // 延迟滚动到下一帧，避免与 submitList 的 DiffUtil 布局计算冲突
            binding.lyricRecyclerView.post { smoothScrollToCenter(index) }
        }
    }

    /**
     * 平滑滚动到指定位置并居中显示。
     */
    private fun smoothScrollToCenter(position: Int) {
        if (position < 0) return
        // 防止 RecyclerView 正在布局计算时启动新的滚动 → attach 竞态崩溃
        if (binding.lyricRecyclerView.isComputingLayout) {
            binding.lyricRecyclerView.post { smoothScrollToCenter(position) }
            return
        }
        try {
            val smoothScroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_ANY

                override fun calculateDtToFit(
                    viewStart: Int,
                    viewEnd: Int,
                    boxStart: Int,
                    boxEnd: Int,
                    snapPreference: Int
                ): Int {
                    return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
                }
            }
            smoothScroller.targetPosition = position
            lyricLayoutManager.startSmoothScroll(smoothScroller)
        } catch (_: Exception) {
            // RecyclerView 状态不合法时忽略
        }
    }

    /**
     * 歌曲详情弹窗：纯黑圆角矩形背景，白字，居中排版。
     * 只显示：歌曲名、艺术家、时长、歌词状态（删除了封面路径和文件路径）。
     */
    private fun showSongDetails() {
        val song = playerManager.getCurrentSong() ?: return

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(R.layout.dialog_song_details)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 填入歌曲信息
        dialog.findViewById<TextView>(R.id.detailsSongName)?.text = song.title
        dialog.findViewById<TextView>(R.id.detailsArtist)?.text = song.artist
        dialog.findViewById<TextView>(R.id.detailsDuration)?.text = "时长：${AudioUtils.formatDuration(song.duration)}"
        dialog.findViewById<TextView>(R.id.detailsLyric)?.text = if (song.lyricPath != null) "歌词：已加载" else "歌词：无"
        dialog.findViewById<Button>(R.id.detailsCloseButton)?.setOnClickListener { dialog.dismiss() }
    }

    private fun updateLoopModeIcon(mode: LoopMode) {
        val iconRes = when (mode) {
            LoopMode.ORDER -> R.drawable.ic_loop_order
            LoopMode.SINGLE -> R.drawable.ic_loop_single
            LoopMode.RANDOM -> R.drawable.ic_loop_random
        }
        val tintColor = when (mode) {
            LoopMode.ORDER -> Color.WHITE       // 顺序：纯白
            LoopMode.SINGLE, LoopMode.RANDOM -> Color.WHITE  // 单曲/随机：纯白
        }
        val contentDesc = when (mode) {
            LoopMode.ORDER -> getString(R.string.loop_order)
            LoopMode.SINGLE -> getString(R.string.loop_single)
            LoopMode.RANDOM -> getString(R.string.loop_random)
        }

        binding.loopModeButton.setImageResource(iconRes)
        binding.loopModeButton.contentDescription = contentDesc
        binding.loopModeButton.setColorFilter(
            tintColor,
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * 播放页 insets：控制面板底部加导航栏高度 padding，避免按钮被导航栏遮挡。
     * 纯净模式下系统栏隐藏，insets 自动变 0，无需额外处理。
     */
    private fun applyPlayerInsets() {
        try {
            val controls = binding.controlsPanel
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                try {
                    val nb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    controls.setPadding(
                        controls.paddingStart,
                        controls.paddingTop,
                        controls.paddingEnd,
                        nb
                    )
                } catch (_: Throwable) {
                }
                insets
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 纯净模式切换：
     * - true：隐藏控件面板（歌曲信息+控件+底部占位），显示右下角展开箭头，
     *   并进入沉浸式全屏（状态栏/导航栏全部隐藏，图片铺满）
     * - false：还原控件、还原系统栏
     */
    private fun setPureMode(enabled: Boolean) {
        isPureMode = enabled
        val window = window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (enabled) {
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                // 还原后重新应用 player 专用系统栏颜色
                ThemeManager.applySystemBars(this, isPlayerActivity = true)
            }
        }
        // 控件显隐：纯净模式仅保留图片、歌词、右下角展开箭头
        val infoViews = listOf(
            binding.titleTextView.parent as View,   // 歌曲信息 LinearLayout
            binding.bottomSpacerView
        )
        if (enabled) {
            infoViews.forEach { it.visibility = View.GONE }
            binding.controlsPanel.visibility = View.GONE
            binding.expandButton.visibility = View.VISIBLE
        } else {
            infoViews.forEach { it.visibility = View.VISIBLE }
            binding.controlsPanel.visibility = View.VISIBLE
            binding.expandButton.visibility = View.GONE
        }
    }
}
