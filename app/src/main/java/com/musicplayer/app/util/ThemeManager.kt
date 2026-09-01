package com.musicplayer.app.util

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.musicplayer.app.R

/**
 * 主题/夜间模式/语言管理单例。
 * - 主题色：5 套渐变 drawable，由 Activity 在 onCreate 时取用并设为根背景；
 *   切换后调用方 [androidx.appcompat.app.AppCompatActivity.recreate] 即可生效。
 * - 夜间模式：通过 AppCompatDelegate.setDefaultNightMode 全局生效，会自动 recreate 当前 Activity。
 * - 语言：通过 AppCompatDelegate.setApplicationLocales 设置 per-app 语言（API 全级别通用）。
 *
 * 持久化采用 SharedPreferences，key 前缀统一在 [PREFS_NAME]。
 */
object ThemeManager {

    /**
     * 7 套主题色。纯黑/纯白为微渐变纯色主题，通过 [overlayThemeRes] 覆盖文字颜色实现自动反色
     * （黑底白字/白底黑字，与日夜模式无关）；其余主题 overlayThemeRes 为 null，文字随日夜切换。
     */
    enum class ThemeColor(
        val key: String,
        val drawableRes: Int,
        val statusBarColorRes: Int,   // 顶部刘海颜色（对应渐变 startColor）
        val navigationBarColorRes: Int, // 底部残余颜色（对应渐变 endColor）
        val overlayThemeRes: Int? = null // 纯黑/纯白主题的文字反色 overlay，其余为 null
    ) {
        GREEN("green",   R.drawable.bg_gradient,        R.color.background,    R.color.background_end),
        SAKURA("sakura", R.drawable.bg_gradient_pink,   R.color.theme_sakura_start, R.color.theme_sakura_end),
        MINT("mint",     R.drawable.bg_gradient_mint,   R.color.theme_mint_start,   R.color.theme_mint_end),
        CORAL("coral",   R.drawable.bg_gradient_coral,  R.color.theme_coral_start,  R.color.theme_coral_end),
        SKY("sky",       R.drawable.bg_gradient_blue,   R.color.theme_sky_start,    R.color.theme_sky_end),
        BLACK("black",   R.drawable.bg_gradient_black,  R.color.theme_black_start,  R.color.theme_black_end,
              R.style.ThemeOverlay_MusicPlayer_OnBlack),
        WHITE("white",   R.drawable.bg_gradient_white,  R.color.theme_white_start,  R.color.theme_white_end,
              R.style.ThemeOverlay_MusicPlayer_OnWhite);

        companion object {
            fun fromKey(key: String?): ThemeColor = entries.firstOrNull { it.key == key } ?: GREEN
        }
    }

    /** 夜间模式：跟随系统 / 强制日间 / 强制夜间。 */
    enum class NightMode(val key: String, val delegateMode: Int) {
        FOLLOW_SYSTEM("follow_system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        DAY("day", AppCompatDelegate.MODE_NIGHT_NO),
        NIGHT("night", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromKey(key: String?): NightMode = entries.firstOrNull { it.key == key } ?: FOLLOW_SYSTEM
        }
    }

    /** 应用语言：跟随系统或显式指定 4 语言之一。 */
    enum class AppLanguage(val key: String, val tag: String) {
        SYSTEM("system", ""),
        ZH_CN("zh_cn", "zh-CN"),
        ZH_TW("zh_tw", "zh-TW"),
        EN("en", "en"),
        JA("ja", "ja");

        companion object {
            fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    /**
     * 歌词字号三档：normal 非高亮行、highlight 高亮行（sp）。
     */
    enum class LyricFontSize(val key: String, val normalSp: Float, val highlightSp: Float) {
        SMALL("small", 13f, 16f),
        MEDIUM("medium", 15f, 18f),
        LARGE("large", 17f, 20f);

        companion object {
            fun fromKey(key: String?): LyricFontSize =
                entries.firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_COLOR = "theme_color"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_LYRIC_FONT = "lyric_font"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 主题色 ----------

    fun getThemeColor(ctx: Context): ThemeColor =
        ThemeColor.fromKey(prefs(ctx).getString(KEY_COLOR, null))

    fun setThemeColor(ctx: Context, color: ThemeColor) {
        prefs(ctx).edit().putString(KEY_COLOR, color.key).apply()
    }

    /** 返回当前主题对应的渐变 drawable 资源 id，供 Activity 设为根背景。 */
    fun getGradientDrawableRes(ctx: Context): Int = getThemeColor(ctx).drawableRes

    /**
     * 在 Activity 的 setContentView 之前调用：
     * 纯黑/纯白主题应用对应的 overlay 主题，使页面文字/图标自动反色；其余主题不处理。
     */
    fun applyActivityTheme(activity: AppCompatActivity) {
        getThemeColor(activity).overlayThemeRes?.let { activity.theme.applyStyle(it, true) }
    }

    /** 解析当前主题中 [attr] 属性的颜色值（用于 SearchView 等代码设置的控件跟随主题反色）。 */
    fun resolveAttrColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        val theme = ctx.theme
        if (theme.resolveAttribute(attr, tv, true)) {
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
            ) {
                return tv.data
            }
            return androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
        }
        return Color.WHITE
    }

    // ---------- 夜间模式 ----------

    fun getNightMode(ctx: Context): NightMode =
        NightMode.fromKey(prefs(ctx).getString(KEY_NIGHT, null))

    fun setNightMode(ctx: Context, mode: NightMode) {
        prefs(ctx).edit().putString(KEY_NIGHT, mode.key).apply()
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    /** 启动时调用，恢复夜间模式设置。 */
    fun applyNightMode(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(getNightMode(ctx).delegateMode)
    }

    // ---------- 语言 ----------

    fun getLanguage(ctx: Context): AppLanguage =
        AppLanguage.fromKey(prefs(ctx).getString(KEY_LANGUAGE, null))

    fun setLanguage(ctx: Context, lang: AppLanguage) {
        prefs(ctx).edit().putString(KEY_LANGUAGE, lang.key).apply()
        val locales = if (lang == AppLanguage.SYSTEM)
            LocaleListCompat.getEmptyLocaleList()
        else
            LocaleListCompat.forLanguageTags(lang.tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 启动时调用，恢复语言设置。 */
    fun applyLanguage(ctx: Context) {
        val lang = getLanguage(ctx)
        if (lang != AppLanguage.SYSTEM) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang.tag))
        }
    }

    // ---------- 歌词字号 ----------

    fun getLyricFontSize(ctx: Context): LyricFontSize =
        LyricFontSize.fromKey(prefs(ctx).getString(KEY_LYRIC_FONT, null))

    fun setLyricFontSize(ctx: Context, size: LyricFontSize) {
        prefs(ctx).edit().putString(KEY_LYRIC_FONT, size.key).apply()
    }

    // ---------- 状态栏 / 导航栏（刘海与底部残余跟随主题渐变） ----------

    /**
     * 让 [activity] 的顶部状态栏（刘海）与底部导航栏（残余条）颜色跟随当前主题：
     * - 状态栏颜色 = 当前主题渐变起始色（startColor）
     * - 导航栏颜色 = 当前主题渐变结束色（endColor）
     * - 并根据颜色亮度自动切换深色/浅色图标模式（API 26+）
     *
     * 此方法应在 Activity 的 onCreate `setContentView` 之后、或 `recreate` 后自然被调用。
     * 切换主题时由于调用方会 [AppCompatActivity.recreate]，无需单独再调用。
     *
     * @param isPlayerActivity 是否是 PlayerActivity（使用独立的 player_background 而不是渐变）
     */
    fun applySystemBars(activity: AppCompatActivity, isPlayerActivity: Boolean = false) {
        val window: Window = activity.window
        val ctx = activity.applicationContext
        val sbColor: Int
        val nbColor: Int
        if (isPlayerActivity) {
            val pb = androidx.core.content.ContextCompat.getColor(ctx, R.color.player_background)
            sbColor = pb
            nbColor = pb
        } else {
            val theme = getThemeColor(ctx)
            sbColor = androidx.core.content.ContextCompat.getColor(ctx, theme.statusBarColorRes)
            nbColor = androidx.core.content.ContextCompat.getColor(ctx, theme.navigationBarColorRes)
        }
        window.statusBarColor = sbColor
        window.navigationBarColor = nbColor
        // API 23 状态栏浅色图标 / API 26 导航栏浅色图标
        setLightSystemBars(activity, isDark(sbColor), isDark(nbColor))
    }

    private fun setLightSystemBars(
        activity: AppCompatActivity,
        lightStatusIcons: Boolean,
        lightNavIcons: Boolean
    ) {
        val window = activity.window
        val decor: View = window.decorView
        var sysUi = decor.systemUiVisibility
        // Status bar icons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sysUi = if (lightStatusIcons) {
                sysUi and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                sysUi or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        // Navigation bar icons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sysUi = if (lightNavIcons) {
                sysUi and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                sysUi or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        decor.systemUiVisibility = sysUi
    }

    /** 颜色是否偏暗（true 表示其上应该用浅色图标）。基于感知亮度阈值 130/255。 */
    private fun isDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return luminance < 130.0
    }
}
