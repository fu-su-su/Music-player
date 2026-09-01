package com.musicplayer.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivitySettingsBinding
import com.musicplayer.app.util.ThemeManager

/**
 * “关于”页：应用图标、名称、版本、editor_by、打开音乐文件夹入口。
 * 背景跟随当前主题色渐变（与 MainActivity 一致）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 应用当前主题色渐变背景（日间/夜间由颜色资源自动适配）
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this)

        setupToolbar()
        setupVersionText()
        setupMusicFolderEntry()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupVersionText() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        binding.versionTextView.text = getString(R.string.version_format, versionName)
    }

    /**
     * 打开系统文件管理器并定位到 内部存储/Music 目录（歌曲与歌词文件的存放处）。
     */
    private fun setupMusicFolderEntry() {
        binding.openFileManagerButton.setOnClickListener {
            try {
                val musicUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Music"
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(musicUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // 兜底：尝试用 resource/folder 方式打开公共 Music 目录
                try {
                    val musicPath = android.os.Environment
                        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                        .absolutePath
                    val fallback = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(musicPath), "resource/folder")
                    }
                    startActivity(fallback)
                } catch (e2: Exception) {
                    Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
