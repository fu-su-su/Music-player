package com.musicplayer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.musicplayer.app.R
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.playback.PlaybackState as PlayerState
import com.musicplayer.app.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 媒体播放前台服务（框架 MediaSession API，API 21+，无需额外依赖）：
 * - 持有 [MediaSession]，对外暴露传输控制（通知栏/锁屏/耳机按键）
 * - 收集 [MusicPlayerManager.playbackInfo] 同步会话状态、元数据与通知
 * - 通知按钮经自定义 action 发往本服务 onStartCommand 控制播放
 * - 完全停止时退出前台并自停
 */
class MusicPlaybackService : Service() {

    private lateinit var playerManager: MusicPlayerManager
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "music_playback_channel"
        const val ACTION_TOGGLE = "com.musicplayer.app.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.musicplayer.app.action.SKIP_TO_NEXT"
        const val ACTION_PREVIOUS = "com.musicplayer.app.action.SKIP_TO_PREVIOUS"
    }

    override fun onCreate() {
        super.onCreate()
        playerManager = MusicPlayerManager.getInstance(this)
        mediaSession = MediaSession(this, "MusicPlayerSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playerManager.play()
                override fun onPause() = playerManager.pause()
                override fun onSkipToNext() = playerManager.next()
                override fun onSkipToPrevious() = playerManager.previous()
                override fun onStop() = playerManager.stop()
                override fun onSeekTo(pos: Long) = playerManager.seekTo(pos)
            })
            isActive = true
        }
        ensureChannel()
        // 以前台身份驻留（占位通知），避免后台启动服务被系统终止
        startForeground(NOTIFICATION_ID, buildNotification(playerManager.playbackInfo.value))
        observePlayback()
    }

    private fun observePlayback() {
        observeJob?.cancel()
        observeJob = scope.launch {
            playerManager.playbackInfo.collectLatest { info ->
                updateSession(info.state)
                updateMetadata(info.currentSongTitle, info.currentSongArtist, info.duration)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(info))
                // 完全停止后退出前台并自停服务
                if (info.state == PlayerState.STOPPED) {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    private fun updateSession(state: PlayerState) {
        val (sessionState, actions) = when (state) {
            PlayerState.PLAYING ->
                PlaybackState.STATE_PLAYING to
                    (baseActions or PlaybackState.ACTION_PAUSE)
            PlayerState.PAUSED ->
                PlaybackState.STATE_PAUSED to
                    (baseActions or PlaybackState.ACTION_PLAY)
            PlayerState.IDLE, PlayerState.STOPPED, PlayerState.COMPLETED ->
                PlaybackState.STATE_STOPPED to
                    (baseActions or PlaybackState.ACTION_PLAY)
            PlayerState.ERROR ->
                PlaybackState.STATE_ERROR to 0L
        }
        val pos = playerManager.playbackInfo.value.currentPosition
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(sessionState, pos, 1.0f)
                .build()
        )
    }

    private val baseActions: Long =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO

    private fun updateMetadata(title: String, artist: String, duration: Long) {
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    /** 构建带媒体样式与三键控制的通知（框架 Notification.MediaStyle）。 */
    private fun buildNotification(
        info: com.musicplayer.app.playback.PlaybackInfo
    ): Notification {
        val isPlaying = info.state == PlayerState.PLAYING
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(info.currentSongTitle.ifEmpty { getString(R.string.app_name) })
            .setContentText(info.currentSongArtist)
            .setContentIntent(buildContentIntent())
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    getString(R.string.previous),
                    actionPendingIntent(ACTION_PREVIOUS)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) getString(R.string.pause) else getString(R.string.play),
                    actionPendingIntent(ACTION_TOGGLE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    getString(R.string.next),
                    actionPendingIntent(ACTION_NEXT)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    /** 通知按钮 → 前台服务 intent（API 26+ 需 getForegroundService）。 */
    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
        } else {
            PendingIntent.getService(this, action.hashCode(), intent, flags)
        }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, PlayerActivity::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.app_name) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> playerManager.togglePlayPause()
            ACTION_NEXT -> playerManager.next()
            ACTION_PREVIOUS -> playerManager.previous()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        playerManager.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
        super.onDestroy()
    }
}
