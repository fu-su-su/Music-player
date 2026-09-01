package com.musicplayer.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.R
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityMainBinding
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.playback.PlaybackState
import com.musicplayer.app.ui.agent.AgentActivity
import com.musicplayer.app.ui.player.PlayerActivity
import com.musicplayer.app.ui.settings.SettingsActionCallback
import com.musicplayer.app.ui.settings.SettingsSideSheetFragment
import com.musicplayer.app.util.AudioUtils
import com.musicplayer.app.util.ThemeManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SettingsActionCallback {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var playerManager: MusicPlayerManager
    private lateinit var songAdapter: SongAdapter

    private var currentSelectedSong: Song? = null
    // 标识权限授予后是否触发设备扫描（与导入文件区分）
    private var scanPendingPermission = false

    // 请求权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (scanPendingPermission) {
                scanPendingPermission = false
                viewModel.scanDeviceAudioFiles()
            } else {
                openAudioFilePicker()
            }
        }
    }

    // 选择音频文件
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let {
            if (it.isNotEmpty()) {
                viewModel.importAudioFiles(it)
            }
        }
    }

    // 选择歌词文件
    private val lyricPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            currentSelectedSong?.let { song ->
                viewModel.updateLyricPath(song.id, it)
            }
        }
    }

    // 选择图片文件
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            currentSelectedSong?.let { song ->
                viewModel.updateCoverPath(song.id, it)
            }
        }
    }

    // 选择歌词文件夹（用于批量自动匹配歌词）
    private val lyricFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignore
            }
            viewModel.autoMatchLyrics(it)
        }
    }

    // 选择图片文件夹（用于批量自动匹配封面）
    private val imageFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignore
            }
            viewModel.autoMatchCovers(it)
        }
    }

    // 选择自定义播放页背景图（对当前播放歌曲生效）
    private var customBackgroundSongId: Long? = null
    private val backgroundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val songId = customBackgroundSongId
        customBackgroundSongId = null
        if (uri != null && songId != null) {
            viewModel.setCustomBackgroundFromUri(songId, uri) { savedPath ->
                if (savedPath != null) {
                    playerManager.updateSongCustomBackground(songId, savedPath)
                    Toast.makeText(this, R.string.custom_bg_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.custom_bg_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 应用当前主题色渐变背景（日间/夜间由颜色资源自动适配）
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this)

        playerManager = MusicPlayerManager.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupMiniPlayer()
        observeData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                playerManager.refreshPlaylist()
                playerManager.playSong(song.id)
                openPlayerActivity()
            },
            onAssociateClick = { song ->
                showAssociateOptions(song)
            },
            onDeleteClick = { song ->
                showDeleteConfirmDialog(song)
            }
        )

        binding.songRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
            val dividerItemDecoration = androidx.recyclerview.widget.DividerItemDecoration(
                this@MainActivity,
                androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
            )
            val dividerColor = ContextCompat.getColor(this@MainActivity, R.color.divider)
            val dividerDrawable = ColorDrawable(dividerColor)
            dividerDrawable.setBounds(0, 0, 0, resources.displayMetrics.density.toInt()) // 1px高度
            dividerItemDecoration.setDrawable(dividerDrawable)
            addItemDecoration(dividerItemDecoration)
        }
    }

    private fun setupMiniPlayer() {
        binding.miniPlayer.root.setOnClickListener {
            openPlayerActivity()
        }

        binding.miniPlayer.miniPlayPauseButton.setOnClickListener {
            playerManager.togglePlayPause()
        }
    }

    private fun observeData() {
        viewModel.songs.observe(this) { songs ->
            songAdapter.submitList(songs)
            // 空列表时区分"无歌曲"与"搜索无结果"
            binding.emptyTextView.text =
                if (viewModel.searchKeyword.value.isNotBlank()) {
                    getString(R.string.no_search_result)
                } else {
                    getString(R.string.no_songs)
                }
            binding.emptyTextView.visibility =
                if (songs.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.importResult.observe(this) { (success, total) ->
            val message = if (success > 0) {
                "成功导入 $success / $total 首歌曲"
            } else {
                "导入失败，歌曲可能已存在或格式不支持"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }

        // 监听设备扫描结果（用于：从电脑拷贝音频后扫描导入）
        viewModel.scanResult.observe(this) { (imported, scanned) ->
            val message = when {
                scanned == 0 -> "未在设备中扫描到音频文件"
                imported == 0 -> "扫描到 $scanned 首歌曲，但均已存在，无新增"
                else -> "扫描到 $scanned 首歌曲，新增导入 $imported 首"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("扫描完成")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }

        // 监听歌词批量匹配结果
        viewModel.matchResult.observe(this) { (matched, total) ->
            val message = when {
                total == 0 -> "没有需要匹配歌词的歌曲（所有歌曲都已关联歌词）"
                matched == 0 -> "在所选目录中未匹配到任何歌词文件（共 $total 首待匹配）"
                else -> "共 $total 首待匹配歌曲，成功匹配 $matched 首"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("歌词匹配完成")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }

        // 监听封面批量匹配结果（覆盖模式，对所有歌曲生效）
        viewModel.coverMatchResult.observe(this) { (matched, total, imageCount) ->
            val message = when {
                imageCount == 0 -> "所选目录（含子文件夹）中未找到任何图片文件（jpg/png/webp）"
                matched == 0 -> "扫描到 $imageCount 张图片，但没有与 $total 首歌曲标题匹配的"
                else -> "扫描到 $imageCount 张图片，共 $total 首歌曲，成功匹配 $matched 张（已覆盖）"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("图片匹配完成")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }

        // 监听 Agent 发来的排序切换请求
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.musicplayer.app.util.AgentBus.sortRequest.collect { type ->
                    if (type != null) {
                        viewModel.setSortType(
                            when (type) {
                                "NAME" -> SortType.NAME
                                "ARTIST" -> SortType.ARTIST
                                "ADD_TIME" -> SortType.ADD_TIME
                                else -> SortType.NAME
                            }
                        )
                        com.musicplayer.app.util.AgentBus.consumeSort()
                    }
                }
            }
        }

        // 监听播放状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerManager.playbackInfo.collect { info ->
                    updateMiniPlayer(info)
                }
            }
        }
    }

    private fun updateMiniPlayer(info: com.musicplayer.app.playback.PlaybackInfo) {
        val hasSong = info.currentSongId != null

        if (hasSong) {
            binding.miniPlayer.root.visibility = View.VISIBLE
            binding.miniPlayer.miniTitleTextView.text = info.currentSongTitle
            binding.miniPlayer.miniArtistTextView.text = info.currentSongArtist

            val playIconRes = when (info.state) {
                PlaybackState.PLAYING -> android.R.drawable.ic_media_pause
                else -> android.R.drawable.ic_media_play
            }
            binding.miniPlayer.miniPlayPauseButton.setImageResource(playIconRes)

            // 加载迷你封面
            val song = playerManager.getCurrentSong()
            if (song != null) {
                val coverPath = song.coverPath
                if (coverPath != null) {
                    com.bumptech.glide.Glide.with(this)
                        .load(coverPath)
                        .centerCrop()
                        .placeholder(R.color.surface_light)
                        .into(binding.miniPlayer.miniCoverImageView)
                } else {
                    // 尝试内嵌封面
                    binding.miniPlayer.miniCoverImageView.setImageResource(0)
                    binding.miniPlayer.miniCoverImageView.setBackgroundColor(
                        ContextCompat.getColor(this, R.color.surface_light)
                    )
                }
            }
        } else {
            binding.miniPlayer.root.visibility = View.GONE
        }
    }

    private fun showAssociateOptions(song: Song) {
        currentSelectedSong = song
        val options = arrayOf(
            getString(R.string.associate_lyric),
            getString(R.string.associate_image)
        )

        AlertDialog.Builder(this)
            .setTitle("\"${song.title}\" - 关联内容")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openLyricPicker()
                    1 -> openImagePicker()
                }
            }
            .show()
    }

    private fun showDeleteConfirmDialog(song: Song) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_song_title)
            .setMessage(getString(R.string.delete_song_message, song.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                // 若删除的是当前播放歌曲，先停止播放
                if (playerManager.getCurrentSong()?.id == song.id) {
                    playerManager.stop()
                }
                viewModel.deleteSong(song.id)
                // 刷新播放列表，避免播放已删除的歌曲
                playerManager.refreshPlaylist()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 提示用户选择歌词文件夹，并尽量默认导航到 内部存储/Music 目录。
     * 用户给出的参考路径：
     *   - 歌词文件：内部存储设备/Music/歌词
     *   - 歌曲文件：内部存储设备/Music/歌
     */
    private fun showLyricFolderPickerHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.match_lyrics)
            .setMessage(getString(R.string.match_lyrics_hint))
            .setPositiveButton(R.string.select_folder) { _, _ ->
                openLyricFolderPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 提示用户选择图片文件夹（用于批量匹配封面），默认导航到 内部存储/Music 目录。
     */
    private fun showImageFolderPickerHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.match_images)
            .setMessage(getString(R.string.match_images_hint))
            .setPositiveButton(R.string.select_folder) { _, _ ->
                openImageFolderPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openLyricFolderPicker() {
        try {
            // 尝试让 SAF 默认导航到 内部存储/Music 目录
            val initialUri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Music"
            )
            lyricFolderPickerLauncher.launch(initialUri)
        } catch (e: Exception) {
            try {
                lyricFolderPickerLauncher.launch(null)
            } catch (e2: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("无法打开文件夹选择器，请检查系统版本")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun openImageFolderPicker() {
        try {
            val initialUri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Music"
            )
            imageFolderPickerLauncher.launch(initialUri)
        } catch (e: Exception) {
            try {
                imageFolderPickerLauncher.launch(null)
            } catch (e2: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("无法打开文件夹选择器，请检查系统版本")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun openAudioFilePicker() {
        try {
            audioPickerLauncher.launch(
                arrayOf(
                    "audio/mpeg",
                    "audio/flac",
                    "audio/wav",
                    "audio/mp4",
                    "audio/aac",
                    "audio/*"
                )
            )
        } catch (e: Exception) {
            // 某些设备可能不支持某些mimeType，使用audio/*兜底
            audioPickerLauncher.launch(arrayOf("audio/*"))
        }
    }

    private fun openLyricPicker() {
        try {
            lyricPickerLauncher.launch(arrayOf("text/plain", "text/lrc", "application/octet-stream"))
        } catch (e: Exception) {
            lyricPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun openImagePicker() {
        try {
            imagePickerLauncher.launch(arrayOf("image/jpeg", "image/png", "image/*"))
        } catch (e: Exception) {
            imagePickerLauncher.launch(arrayOf("image/*"))
        }
    }

    private fun checkPermissionsAndImport() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            openAudioFilePicker()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * 检查音频读取权限后触发设备扫描。
     * 用户将电脑上的音频拷贝到手机后，MediaStore 需要读取权限才能查询到这些文件。
     */
    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.scanDeviceAudioFiles()
        } else {
            // 权限拒绝后再次申请；用户授予后由 requestPermissionLauncher 回调触发扫描
            scanPendingPermission = true
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun openPlayerActivity() {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu?.findItem(R.id.action_search) ?: return true
        val searchView = searchItem.actionView as? androidx.appcompat.widget.SearchView
        searchView?.apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearchKeyword(newText.orEmpty())
                    return true
                }
            })
        }
        // 收起搜索框时清空关键字，恢复完整歌曲列表
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.setSearchKeyword("")
                return true
            }
        })
        // 搜索框文字/提示颜色跟随主题（纯黑/纯白主题自动反色）
        val searchEditText = searchView?.findViewById<android.widget.EditText>(
            androidx.appcompat.R.id.search_src_text
        )
        searchEditText?.setTextColor(ThemeManager.resolveAttrColor(this, R.attr.mpTextPrimary))
        searchEditText?.setHintTextColor(ThemeManager.resolveAttrColor(this, R.attr.mpTextSecondary))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                SettingsSideSheetFragment().show(supportFragmentManager, "settings_sheet")
                true
            }
            R.id.action_search -> {
                // 第一批：仅占位，搜索逻辑在第三批实现
                Toast.makeText(this, R.string.search_feature_coming, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- SettingsActionCallback 实现：委托给现有私有方法 ----------

    override fun onOpenAIAssistant() {
        startActivity(Intent(this, AgentActivity::class.java))
    }

    override fun onImportMusic() {
        checkPermissionsAndImport()
    }

    override fun onScanDeviceMusic() {
        checkPermissionsAndScan()
    }

    override fun onMatchLyrics() {
        showLyricFolderPickerHint()
    }

    override fun onMatchImages() {
        showImageFolderPickerHint()
    }

    override fun onSortBy(type: String) {
        viewModel.setSortType(
            when (type) {
                "NAME" -> SortType.NAME
                "ARTIST" -> SortType.ARTIST
                "ADD_TIME" -> SortType.ADD_TIME
                else -> SortType.NAME
            }
        )
    }

    override fun onOpenPlaylists() {
        startActivity(Intent(this, com.musicplayer.app.ui.playlist.PlaylistActivity::class.java))
    }

    /**
     * 为当前播放歌曲设置自定义播放页背景：
     * 无播放歌曲则提示；已有背景则询问"换一张/清除"。
     */
    override fun onCustomBackground() {
        val songId = playerManager.playbackInfo.value.currentSongId
        if (songId == null) {
            Toast.makeText(this, R.string.custom_bg_need_playing, Toast.LENGTH_SHORT).show()
            return
        }
        val song = playerManager.getPlaylist().find { it.id == songId }
        if (song?.customBackgroundPath != null) {
            val options = arrayOf(
                getString(R.string.custom_bg_pick),
                getString(R.string.custom_bg_clear)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_custom_background)
                .setItems(options) { _, which ->
                    if (which == 0) launchBackgroundPicker(songId) else clearCustomBackground(songId)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            launchBackgroundPicker(songId)
        }
    }

    private fun launchBackgroundPicker(songId: Long) {
        customBackgroundSongId = songId
        backgroundPickerLauncher.launch("image/*")
    }

    private fun clearCustomBackground(songId: Long) {
        viewModel.setCustomBackground(songId, null)
        playerManager.updateSongCustomBackground(songId, null)
        Toast.makeText(this, R.string.custom_bg_cleared, Toast.LENGTH_SHORT).show()
    }
}
