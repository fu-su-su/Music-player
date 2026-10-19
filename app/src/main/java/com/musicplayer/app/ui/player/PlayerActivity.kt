package com.musicplayer.app.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
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
import kotlinx.coroutines.Job
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

    /** 封面/歌词加载任务：切歌时取消上一首未完成的加载，防止并发解码大图 OOM */
    private var coverLoadJob: Job? = null
    private var lyricLoadJob: Job? = null

    /** 切歌按钮防抖间隔：间隔内忽略重复点击，降低快速连切时的资源压力 */
    private val switchClickDebounceMs = 300L

    // ---------- 拖词选时播放 ----------
    /** 是否正在拖动歌词选择播放位置（拖动期间暂停歌词自动跟随） */
    private var isLyricDragging = false
    private var lyricDragDownY = 0f
    private var lyricDragLastY = 0f
    private var lyricDragIndex = -1
    private val lyricTouchSlop: Int by lazy {
        ViewConfiguration.get(this).scaledTouchSlop
    }

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
            // 上下渐隐/渐显由 FadingLyricRecyclerView 自绘遮罩保证对称
            // 关闭条目动画：歌词高亮/切歌时不需要插入与 change 动画。
            // 必须置空，否则 submitList 与平滑滚动并发时 ItemAnimator
            // 可能 reattach 未 detach 的 ViewHolder 导致崩溃。
            itemAnimator = null
        }

        // 拖词选时：上下拖动歌词 → 两侧播放线出现，松手从选中句播放
        setupLyricDragSeek()
        // 让任意一句歌词（包括第一句和最后一句）都能滚动到垂直中线被选中：
        // 上下各留出半个视图高度的空白，配合 clipToPadding=false，首尾句也能到达
        // 播放线所在的中线位置；否则列表滚到尽头时最后几行永远够不到中线。
        binding.lyricRecyclerView.doOnLayout { rv ->
            val half = rv.height / 2
            rv.setPadding(0, half, 0, half)
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
        // 切歌按钮：临时禁用做防抖，避免快速连点导致短时间内大量重建播放器/加载封面
        binding.previousButton.setOnClickListener { button ->
            button.isEnabled = false
            playerManager.previous()
            button.postDelayed({ button.isEnabled = true }, switchClickDebounceMs)
        }
        binding.nextButton.setOnClickListener { button ->
            button.isEnabled = false
            playerManager.next()
            button.postDelayed({ button.isEnabled = true }, switchClickDebounceMs)
        }
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

        // 取消上一首未完成的封面/歌词加载，并立即释放旧封面位图，
        // 避免快速切歌时多张全尺寸位图同时驻留内存导致 OOM 闪退
        coverLoadJob?.cancel()
        lyricLoadJob?.cancel()
        Glide.with(this).clear(binding.coverImageView)

        // 复位拖词选时状态，防止上一首的播放线/选中态残留
        isLyricDragging = false
        lyricDragIndex = -1
        hideLyricDragIndicator()

        // 封面按屏幕宽度采样解码（centerCrop 铺满宽度即可），不再加载原始大图
        val targetSize = resources.displayMetrics.widthPixels.coerceAtLeast(1)

        // 加载背景/封面（子线程采样读取文件，主线程更新UI）
        // 优先级：该歌曲的自定义背景 > 封面文件 > 内嵌封面
        coverLoadJob = lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    when {
                        song.customBackgroundPath != null ->
                            ImageUtils.loadBitmapFromPath(
                                song.customBackgroundPath, targetSize, targetSize
                            )
                        song.coverPath != null ->
                            ImageUtils.loadBitmapFromPath(
                                song.coverPath, targetSize, targetSize
                            )
                        else -> {
                            val bytes = AudioUtils.getEmbeddedCover(song.path)
                            ImageUtils.loadBitmapFromBytes(
                                bytes, targetSize, targetSize
                            )
                        }
                    }
                } catch (e: Throwable) {
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
        lyricLoadJob = lifecycleScope.launch {
            lyrics = withContext(Dispatchers.IO) {
                try {
                    val lyricPath = song.lyricPath
                    if (lyricPath != null) LyricParser.parseLyricFile(lyricPath) else emptyList()
                } catch (e: Throwable) {
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
                // 记录本次提交归属：回调执行时若已切到别的歌或页面已销毁则丢弃，
                // 防止旧歌词的延迟回调拿着失效索引刷新新列表
                val targetSongId = songId
                lyricAdapter.submitList(lyrics) {
                    binding.lyricRecyclerView.post {
                        if (isFinishing || isDestroyed || loadedSongId != targetSongId) return@post
                        lyricAdapter.updateHighlight(lastHighlightIndex)
                    }
                }
            }
        }
    }

    private fun updateLyricHighlight(currentPosition: Long) {
        if (lyrics.isEmpty()) return
        if (binding.lyricRecyclerView.visibility != View.VISIBLE) return
        // 用户正在拖动歌词选时：不自动跟随播放进度高亮
        if (isLyricDragging) return

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
        if (position < 0 || isFinishing || isDestroyed) return
        // 防止 RecyclerView 正在布局计算时启动新的滚动 → attach 竞态崩溃
        if (binding.lyricRecyclerView.isComputingLayout) {
            binding.lyricRecyclerView.post { smoothScrollToCenter(position) }
            return
        }
        // 索引已失效（快速切歌后歌词列表已更换）→ 放弃本次滚动
        if (position >= lyricAdapter.itemCount) return
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

    // ================================================================
    //  拖词选时播放
    // ================================================================

    /**
     * 在歌词区上下拖动：歌词随手势滚动，两侧播放线出现，垂直居中的歌词句
     * 被选中并高亮、左侧显示该句时间；松手后从该句时间点开始播放，
     * 选中句平滑居中，随后恢复歌词自动跟随。
     */
    private fun setupLyricDragSeek() {
        binding.lyricRecyclerView.setOnTouchListener { _, event ->
            if (lyrics.isEmpty()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lyricDragDownY = event.rawY
                    lyricDragLastY = event.rawY
                    // 返回 true 才能持续接收后续 MOVE/UP 事件
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isLyricDragging &&
                        kotlin.math.abs(event.rawY - lyricDragDownY) > lyricTouchSlop
                    ) {
                        isLyricDragging = true
                        binding.lyricRecyclerView.stopScroll()
                        showLyricDragIndicator()
                    }
                    if (isLyricDragging) {
                        // 手指下移 → 歌词内容向下滚动（查看前面的歌词）
                        val dy = event.rawY - lyricDragLastY
                        lyricDragLastY = event.rawY
                        try {
                            binding.lyricRecyclerView.scrollBy(0, (-dy).toInt())
                        } catch (_: Exception) { }
                        updateLyricDragSelection()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLyricDragging) finishLyricDrag()
                    true
                }
                else -> true
            }
        }
    }

    /** 显示两侧播放线与时间标签。 */
    private fun showLyricDragIndicator() {
        binding.lyricDragLineLeft.visibility = View.VISIBLE
        binding.lyricDragLineRight.visibility = View.VISIBLE
        binding.lyricDragTimeText.visibility = View.VISIBLE
    }

    /** 隐藏两侧播放线与时间标签。 */
    private fun hideLyricDragIndicator() {
        binding.lyricDragLineLeft.visibility = View.GONE
        binding.lyricDragLineRight.visibility = View.GONE
        binding.lyricDragTimeText.visibility = View.GONE
    }

    /**
     * 根据当前滚动位置，找出最靠近歌词区垂直中线的歌词句并高亮，
     * 同时把该句时间显示在左侧时间标签上。
     */
    private fun updateLyricDragSelection() {
        val rv = binding.lyricRecyclerView
        if (rv.childCount == 0) return
        val centerY = rv.height / 2
        var bestChild: View? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val childCenter = (child.top + child.bottom) / 2
            val distance = kotlin.math.abs(childCenter - centerY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestChild = child
            }
        }
        val child = bestChild ?: return
        val index = try {
            lyricLayoutManager.getPosition(child)
        } catch (_: Exception) {
            -1
        }
        if (index == lyricDragIndex || index !in lyrics.indices) return
        lyricDragIndex = index
        lastHighlightIndex = index
        lyricAdapter.updateHighlight(index)
        binding.lyricDragTimeText.text = AudioUtils.formatDuration(lyrics[index].time)
    }

    /**
     * 松手：从选中句的时间点开始（或继续）播放，隐藏播放线，选中句居中，
     * 随后恢复播放进度自动跟随。
     */
    private fun finishLyricDrag() {
        val index = lyricDragIndex
        isLyricDragging = false
        lyricDragIndex = -1
        hideLyricDragIndicator()

        if (index in lyrics.indices) {
            val targetTime = lyrics[index].time
            playerManager.seekTo(targetTime)
            // 暂停/停止状态下也应从该句开始播放
            if (playerManager.playbackInfo.value.state != PlaybackState.PLAYING) {
                playerManager.play()
            }
            // 等本次滚动落稳后再把选中句平滑居中
            binding.lyricRecyclerView.post { smoothScrollToCenter(index) }
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
