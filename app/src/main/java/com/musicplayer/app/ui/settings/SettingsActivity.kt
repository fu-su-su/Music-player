package com.musicplayer.app.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivitySettingsBinding
import com.musicplayer.app.util.ThemeManager

/**
 * “关于”页：应用图标、名称、版本、editor_by。
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
        applyEdgeInsets()
    }

    /**
     * 使用 WindowInsetsListener 异步拿到状态栏/导航栏高度，
     * Toolbar 加状态栏 top padding，内容容器加导航栏 bottom padding。
     */
    private fun applyEdgeInsets() {
        try {
            val tb = binding.toolbar
            val ab = (56 * resources.displayMetrics.density).toInt()
            val xl = resources.getDimensionPixelSize(R.dimen.spacing_xl)
            val content = binding.settingsContent

            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                try {
                    val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                    val nb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom

                    tb.setPadding(tb.paddingStart, sb, tb.paddingEnd, tb.paddingBottom)
                    val lp = tb.layoutParams
                    lp.height = ab + sb
                    tb.layoutParams = lp

                    content.setPadding(
                        content.paddingStart,
                        xl,
                        content.paddingEnd,
                        xl + nb
                    )
                } catch (_: Throwable) {
                }
                insets
            }
        } catch (_: Throwable) {
        }
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
            "2.1.0"
        }
        binding.versionTextView.text = com.musicplayer.app.R.string.version_format
            .let { getString(it, versionName) }
    }
}
