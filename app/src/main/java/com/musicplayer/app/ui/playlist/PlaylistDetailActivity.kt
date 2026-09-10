package com.musicplayer.app.ui.playlist

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.R
import com.musicplayer.app.data.Song
import com.musicplayer.app.databinding.ActivityPlaylistDetailBinding
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * 歌单详情页：查看/移除歌曲、从歌单播放、添加歌曲。
 */
class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private val viewModel: PlaylistViewModel by viewModels()
    private lateinit var adapter: PlaylistSongAdapter
    private lateinit var playerManager: MusicPlayerManager

    private var playlistId: Long = -1L
    private var currentSongs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this)

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        val name = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: getString(R.string.settings_my_playlists)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = name
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        playerManager = MusicPlayerManager.getInstance(this)

        adapter = PlaylistSongAdapter(
            onSongClick = { _, index -> playFromPlaylist(index) },
            onRemoveClick = { song -> viewModel.removeSongFromPlaylist(playlistId, song.id) }
        )
        binding.songRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlaylistDetailActivity)
            adapter = this@PlaylistDetailActivity.adapter
        }

        applyEdgeInsets()
        viewModel.setCurrentPlaylist(playlistId)
        viewModel.songsInPlaylist.observe(this) { songs ->
            currentSongs = songs
            adapter.submitList(songs)
            binding.emptyTextView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist, menu)
        menu.findItem(R.id.action_add)?.title = getString(R.string.playlist_add_songs)
        return true
    }

    private fun applyEdgeInsets() {
        try {
            val tb = binding.toolbar
            val ab = (56 * resources.displayMetrics.density).toInt()
            val rv = binding.songRecyclerView
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                try {
                    val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                    val nb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    tb.setPadding(tb.paddingStart, sb, tb.paddingEnd, tb.paddingBottom)
                    val lp = tb.layoutParams
                    lp.height = ab + sb
                    tb.layoutParams = lp
                    rv.setPadding(rv.paddingStart, rv.paddingTop, rv.paddingEnd, nb)
                } catch (_: Throwable) {
                }
                insets
            }
        } catch (_: Throwable) {
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> { showAddSongsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun playFromPlaylist(index: Int) {
        if (currentSongs.isEmpty()) return
        playerManager.playPlaylist(currentSongs, index)
        // 跳转播放页
        startActivity(android.content.Intent(this, com.musicplayer.app.ui.player.PlayerActivity::class.java))
    }

    private fun showAddSongsDialog() {
        lifecycleScope.launch {
            val allSongs = viewModel.getAllSongs()
            val existingIds = currentSongs.map { it.id }.toSet()
            val candidates = allSongs.filter { it.id !in existingIds }
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this@PlaylistDetailActivity)
                    .setMessage(R.string.no_songs)
                    .setPositiveButton(R.string.done, null)
                    .show()
                return@launch
            }
            val labels = candidates.map { "${it.title} - ${it.artist}" }.toTypedArray()
            val checked = BooleanArray(candidates.size)
            AlertDialog.Builder(this@PlaylistDetailActivity)
                .setTitle(R.string.select_songs)
                .setMultiChoiceItems(labels, checked) { _, _, _ -> }
                .setPositiveButton(R.string.done) { _, _ ->
                    for (i in candidates.indices) {
                        if (checked[i]) {
                            viewModel.addSongToPlaylist(playlistId, candidates[i].id)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
    }
}
