package com.musicplayer.app.ui.playlist

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.app.R
import com.musicplayer.app.data.Playlist
import com.musicplayer.app.databinding.ActivityPlaylistBinding
import com.musicplayer.app.util.ThemeManager

/**
 * 歌单列表页：新建/重命名/删除歌单，点击进入详情。
 */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private val viewModel: PlaylistViewModel by viewModels()
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyEdgeInsets()

        adapter = PlaylistAdapter(
            onClick = { playlist -> openDetail(playlist) },
            onLongClick = { playlist -> showManageDialog(playlist) }
        )
        binding.playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlaylistActivity)
            adapter = this@PlaylistActivity.adapter
        }

        viewModel.playlistsWithCount.observe(this) { list ->
            adapter.submitList(list)
            binding.emptyTextView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist, menu)
        return true
    }

    private fun applyEdgeInsets() {
        try {
            val tb = binding.toolbar
            val ab = (56 * resources.displayMetrics.density).toInt()
            val rv = binding.playlistRecyclerView
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
            R.id.action_add -> { showCreateDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.playlist_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_create)
            .setView(input)
            .setPositiveButton(R.string.done) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.createPlaylist(name) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showManageDialog(playlist: Playlist) {
        val options = arrayOf(getString(R.string.playlist_rename), getString(R.string.playlist_delete))
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                if (which == 0) showRenameDialog(playlist) else showDeleteConfirm(playlist)
            }
            .show()
    }

    private fun showRenameDialog(playlist: Playlist) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(playlist.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_rename)
            .setView(input)
            .setPositiveButton(R.string.done) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.renamePlaylist(playlist.id, name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirm(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deletePlaylist(playlist) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDetail(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
            putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
            putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_NAME, playlist.name)
        }
        startActivity(intent)
    }
}
