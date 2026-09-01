package com.musicplayer.app

import android.app.Application
import com.musicplayer.app.data.MusicDatabase
import com.musicplayer.app.util.ThemeManager

class MusicPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化数据库
        MusicDatabase.getDatabase(this)
        // 恢复夜间模式与语言偏好（必须在首个 Activity 创建之前）
        ThemeManager.applyNightMode(this)
        ThemeManager.applyLanguage(this)
    }

    companion object {
        lateinit var instance: MusicPlayerApplication
            private set
    }
}
