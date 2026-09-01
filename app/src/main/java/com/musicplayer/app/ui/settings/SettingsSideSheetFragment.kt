package com.musicplayer.app.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.musicplayer.app.R
import com.musicplayer.app.databinding.FragmentSideSheetSettingsBinding
import com.musicplayer.app.databinding.ItemSettingsRowBinding
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.util.ThemeManager
import com.musicplayer.app.util.ThemeManager.AppLanguage
import com.musicplayer.app.util.ThemeManager.LyricFontSize
import com.musicplayer.app.util.ThemeManager.NightMode
import com.musicplayer.app.util.ThemeManager.ThemeColor

/**
 * 设置页：从左侧唤出的侧边面板（横向占 3/4 屏宽，纵向占满）。
 *
 * 已接入（第二批/第四批）：
 * - AI助手/导入音乐/扫描设备音乐/自动匹配歌词/自动匹配图片/排序 → 经 [SettingsActionCallback] 委托给 MainActivity 现有逻辑
 * - 主题色切换（4 套）→ ThemeManager + 宿主 recreate()
 * - 夜间模式（跟随系统/日/夜）→ ThemeManager + setDefaultNightMode 自动 recreate
 * - 语言（跟随系统/简中/繁中/英/日）→ ThemeManager + setApplicationLocales 自动 recreate
 * - 睡眠定时（关/15/30/60分钟/播完当前）→ MusicPlayerManager 倒计时
 * - 自定义播放页背景（对当前播放歌曲生效）→ 经 [SettingsActionCallback.onCustomBackground] 委托
 * - 歌词字号（小/中/大）→ ThemeManager，播放页 onResume 时生效
 * - 关于：跳转“关于”页（SettingsActivity）
 */
class SettingsSideSheetFragment : DialogFragment() {

    private var _binding: FragmentSideSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private var callback: SettingsActionCallback? = null
    private lateinit var playerManager: MusicPlayerManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? SettingsActionCallback
        playerManager = MusicPlayerManager.getInstance(context.applicationContext)
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }

    private data class Row(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val valueText: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无边框，背景透明，由根布局自带圆角背景
        setStyle(STYLE_NO_FRAME, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSideSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 横向(x)占 3/4 屏宽；纵向(y)占满由 match_parent + layout_gravity=start 保证
        val dm = resources.displayMetrics
        val lp = binding.settingsPanel.layoutParams as FrameLayout.LayoutParams
        lp.width = (dm.widthPixels * 3 / 4)
        lp.gravity = Gravity.START
        binding.settingsPanel.layoutParams = lp
        // 点击面板外空白（根容器）关闭
        binding.settingsRoot.setOnClickListener { dismiss() }
        buildRows()
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        // 全屏窗口 + 透明背景，让 FLAG_DIM_BEHIND 形成面板外半透明遮罩
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.START)
        window.setWindowAnimations(R.style.SideSheetAnim)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.4f)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 行构建 ----------

    private fun buildRows() {
        val container = binding.settingsContainer
        container.removeAllViews()
        val rows = currentRows()
        rows.forEachIndexed { index, row ->
            val rowBinding = ItemSettingsRowBinding.inflate(layoutInflater, container, false)
            rowBinding.rowTitle.text = getString(row.titleRes)
            rowBinding.rowIcon.setImageResource(row.iconRes)
            if (row.valueText != null) {
                rowBinding.rowValue.text = row.valueText
                rowBinding.rowValue.visibility = View.VISIBLE
            } else {
                rowBinding.rowValue.visibility = View.GONE
            }
            rowBinding.root.setOnClickListener { onRowClicked(row.key) }
            container.addView(rowBinding.root)
            if (index != rows.lastIndex) addDivider(container)
        }
    }

    private fun addDivider(container: ViewGroup) {
        val dp = resources.displayMetrics.density
        val v = View(container.context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_divider))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            1
        )
        lp.marginStart = (dp * 20).toInt()
        lp.marginEnd = (dp * 20).toInt()
        v.layoutParams = lp
        container.addView(v)
    }

    private fun currentRows(): List<Row> {
        val ctx = requireContext()
        return listOf(
            Row("ai", R.string.settings_ai_assistant, android.R.drawable.ic_menu_send),
            Row("import", R.string.settings_import_music, android.R.drawable.ic_menu_add),
            Row("scan", R.string.settings_scan_music, android.R.drawable.ic_menu_search),
            Row("match_lyrics", R.string.settings_match_lyrics, android.R.drawable.ic_menu_agenda),
            Row("match_images", R.string.settings_match_images, android.R.drawable.ic_menu_gallery),
            Row("sort", R.string.settings_sort, android.R.drawable.ic_menu_sort_by_size),
            Row("my_playlists", R.string.settings_my_playlists, android.R.drawable.ic_menu_my_calendar),
            Row("theme", R.string.settings_theme_color, android.R.drawable.ic_menu_preferences,
                valueText = getString(themeColorTitle(ThemeManager.getThemeColor(ctx)))),
            Row("night", R.string.settings_night_mode, android.R.drawable.ic_menu_crop,
                valueText = getString(nightModeTitle(ThemeManager.getNightMode(ctx)))),
            Row("sleep", R.string.settings_sleep_timer, android.R.drawable.ic_menu_recent_history,
                valueText = sleepTimerValueText()),
            Row("custom_bg", R.string.settings_custom_background, android.R.drawable.ic_menu_camera),
            Row("lyric_font", R.string.settings_lyric_font_size, android.R.drawable.ic_menu_edit,
                valueText = getString(lyricFontSizeTitle(ThemeManager.getLyricFontSize(ctx)))),
            Row("language", R.string.settings_language, android.R.drawable.ic_menu_my_calendar,
                valueText = getString(languageTitle(ThemeManager.getLanguage(ctx)))),
            Row("about", R.string.settings_about, android.R.drawable.ic_menu_info_details),
        )
    }

    /** 睡眠定时行的状态文案：剩余时间 / 播完当前 / 关 */
    private fun sleepTimerValueText(): String {
        val remainingMs = playerManager.sleepTimerRemaining.value
        if (remainingMs != null) {
            val minutes = (remainingMs + 59_999) / 60_000  // 向上取整到分钟
            return getString(R.string.sleep_remaining_fmt, minutes)
        }
        if (playerManager.isSleepAfterCurrent()) {
            return getString(R.string.sleep_after_current)
        }
        return getString(R.string.sleep_off)
    }

    private fun lyricFontSizeTitle(s: LyricFontSize): Int = when (s) {
        LyricFontSize.SMALL -> R.string.lyric_font_small
        LyricFontSize.MEDIUM -> R.string.lyric_font_medium
        LyricFontSize.LARGE -> R.string.lyric_font_large
    }

    // ---------- 点击处理 ----------

    private fun onRowClicked(key: String) {
        val cb = callback
        when (key) {
            "theme" -> showThemeColorPicker()
            "night" -> showNightModePicker()
            "language" -> showLanguagePicker()
            "sort" -> showSortPicker()
            "sleep" -> showSleepTimerPicker()
            "lyric_font" -> showLyricFontSizePicker()
            "custom_bg" -> { dismiss(); cb?.onCustomBackground() }
            "my_playlists" -> { dismiss(); cb?.onOpenPlaylists() }
            "about" -> {
                dismiss()
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
            "ai" -> { dismiss(); cb?.onOpenAIAssistant() }
            "import" -> { dismiss(); cb?.onImportMusic() }
            "scan" -> { dismiss(); cb?.onScanDeviceMusic() }
            "match_lyrics" -> { dismiss(); cb?.onMatchLyrics() }
            "match_images" -> { dismiss(); cb?.onMatchImages() }
            else -> Toast.makeText(requireContext(), R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    /** 睡眠定时：关/15/30/60分钟/播完当前，作用于 MusicPlayerManager。 */
    private fun showSleepTimerPicker() {
        val options = arrayOf(
            getString(R.string.sleep_off),
            getString(R.string.sleep_15_min),
            getString(R.string.sleep_30_min),
            getString(R.string.sleep_60_min),
            getString(R.string.sleep_after_current)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_sleep_timer)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> playerManager.cancelSleepTimer()
                    1 -> playerManager.startSleepTimer(15)
                    2 -> playerManager.startSleepTimer(30)
                    3 -> playerManager.startSleepTimer(60)
                    4 -> playerManager.setSleepAfterCurrentSong()
                }
                dialog.dismiss()
                buildRows()  // 刷新行状态文案
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 歌词字号三档，播放页 onResume 时生效。 */
    private fun showLyricFontSizePicker() {
        val sizes = LyricFontSize.entries
        val labels = sizes.map { getString(lyricFontSizeTitle(it)) }.toTypedArray()
        val checked = sizes.indexOf(ThemeManager.getLyricFontSize(requireContext()))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_lyric_font_size)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setLyricFontSize(requireContext(), sizes[which])
                dialog.dismiss()
                buildRows()  // 刷新行状态文案
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSortPicker() {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.sort_by_name),
            getString(R.string.sort_by_artist),
            getString(R.string.sort_by_add_time)
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_sort)
            .setItems(options) { _, which ->
                val type = when (which) {
                    0 -> "NAME"
                    1 -> "ARTIST"
                    else -> "ADD_TIME"
                }
                callback?.onSortBy(type)
                dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThemeColorPicker() {
        val ctx = requireContext()
        val colors = ThemeColor.entries
        val labels = colors.map { getString(themeColorTitle(it)) }.toTypedArray()
        val checked = colors.indexOf(ThemeManager.getThemeColor(ctx))
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_theme_color)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setThemeColor(ctx, colors[which])
                dialog.dismiss()
                dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNightModePicker() {
        val ctx = requireContext()
        val modes = NightMode.entries
        val labels = modes.map { getString(nightModeTitle(it)) }.toTypedArray()
        val checked = modes.indexOf(ThemeManager.getNightMode(ctx))
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_night_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setNightMode(ctx, modes[which])
                dialog.dismiss()
                dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguagePicker() {
        val ctx = requireContext()
        val langs = AppLanguage.entries
        val labels = langs.map { getString(languageTitle(it)) }.toTypedArray()
        val checked = langs.indexOf(ThemeManager.getLanguage(ctx))
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setLanguage(ctx, langs[which])
                dialog.dismiss()
                dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 枚举 → 文案 ----------

    private fun themeColorTitle(c: ThemeColor): Int = when (c) {
        ThemeColor.GREEN -> R.string.theme_green
        ThemeColor.SAKURA -> R.string.theme_sakura
        ThemeColor.MINT -> R.string.theme_mint
        ThemeColor.CORAL -> R.string.theme_coral
        ThemeColor.SKY -> R.string.theme_sky
        ThemeColor.BLACK -> R.string.theme_black
        ThemeColor.WHITE -> R.string.theme_white
    }

    private fun nightModeTitle(m: NightMode): Int = when (m) {
        NightMode.FOLLOW_SYSTEM -> R.string.night_mode_follow_system
        NightMode.DAY -> R.string.night_mode_day
        NightMode.NIGHT -> R.string.night_mode_night
    }

    private fun languageTitle(l: AppLanguage): Int = when (l) {
        AppLanguage.SYSTEM -> R.string.night_mode_follow_system
        AppLanguage.ZH_CN -> R.string.lang_zh_cn
        AppLanguage.ZH_TW -> R.string.lang_zh_tw
        AppLanguage.EN -> R.string.lang_en
        AppLanguage.JA -> R.string.lang_ja
    }
}

/**
 * 设置面板与宿主 Activity 之间的回调约定。
 * 排序类型用字符串 key（"NAME"/"ARTIST"/"ADD_TIME"）以解耦枚举位置。
 */
interface SettingsActionCallback {
    fun onOpenAIAssistant()
    fun onImportMusic()
    fun onScanDeviceMusic()
    fun onMatchLyrics()
    fun onMatchImages()
    fun onSortBy(type: String)
    fun onOpenPlaylists()
    /** 为当前播放歌曲设置自定义播放页背景（SAF 选图，MainActivity 处理）。 */
    fun onCustomBackground()
}
