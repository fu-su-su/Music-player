package com.musicplayer.app.ui.splash

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivitySplashBinding
import com.musicplayer.app.ui.main.MainActivity
import com.musicplayer.app.util.ThemeManager

/**
 * 首次启动引导页：欢迎页 → 隐私协议弹窗 → 音频权限请求 → 进入主页。
 * 非首次启动直接跳转 MainActivity。
 * 复用 ThemeManager 的 SharedPreferences("theme_prefs") 存 first_launch 标志。
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 无论是否授予，都标记为已启动并进入主页（权限可在设置内再次申请）
        markLaunchedAndProceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this)

        prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)

        if (prefs.getBoolean(KEY_FIRST_LAUNCH, true)) {
            // 首次启动：展示欢迎页，等待用户同意隐私协议
            binding.agreeButton.setOnClickListener { showPrivacyPolicyDialog() }
        } else {
            // 非首次：直接进主页
            launchMainAndFinish()
        }
    }

    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_policy_title)
            .setMessage(getString(R.string.privacy_policy_content))
            .setCancelable(false)
            .setPositiveButton(R.string.agree_and_continue) { _, _ -> requestAudioPermission() }
            .setNegativeButton(R.string.disagree) { _, _ -> finish() }
            .show()
    }

    private fun requestAudioPermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun markLaunchedAndProceed() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        launchMainAndFinish()
    }

    private fun launchMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
