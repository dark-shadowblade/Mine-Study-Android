package com.minstudy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

data class DownloadUiState(
    val status: String = "",
    val active: Boolean = false,
    val paused: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val speed: Long = 0L
)

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "mine_study_downloads"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_START = "com.minstudy.app.download.START"
        private const val ACTION_PAUSE = "com.minstudy.app.download.PAUSE"
        private const val ACTION_RESUME = "com.minstudy.app.download.RESUME"
        private const val ACTION_CANCEL = "com.minstudy.app.download.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_VIDEO = "video"
        private val mutableState = MutableStateFlow(DownloadUiState())
        val state = mutableState.asStateFlow()

        fun start(context: Context, url: String, title: String, category: String, video: Boolean) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_VIDEO, video)
            }
            ContextCompat.startForegroundService(context, intent)
        }
        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun cancel(context: Context) = command(context, ACTION_CANCEL)
        private fun command(context: Context, action: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var control: DownloadControl? = null
    private var currentTitle = "Study material"
    private var lastProgressAt = 0L
    private var lastProgressBytes = 0L

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Study downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress and controls for Mine Study downloads"
            setShowBadge(true)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_PAUSE -> {
                control?.paused = true
                mutableState.value = mutableState.value.copy(paused = true, status = "Paused • $currentTitle")
                updateNotification()
            }
            ACTION_RESUME -> {
                control?.paused = false
                mutableState.value = mutableState.value.copy(paused = false, status = "Downloading • $currentTitle")
                updateNotification()
            }
            ACTION_CANCEL -> control?.cancelled = true
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (mutableState.value.active) return
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Study material"
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Lectures"
        val video = intent.getBooleanExtra(EXTRA_VIDEO, true)
        if (url.isBlank()) {
            mutableState.value = DownloadUiState(status = "Download failed: URL missing")
            stopSelf()
            return
        }
        control = DownloadControl()
        lastProgressAt = System.currentTimeMillis()
        lastProgressBytes = 0L
        mutableState.value = DownloadUiState(status = "Preparing • $currentTitle", active = true)
        startForegroundCompat(buildNotification())
        serviceScope.launch {
            try {
                mutableState.value = mutableState.value.copy(status = "Downloading • $currentTitle")
                val record = DownloadRepository.download(
                    this@DownloadService, url, currentTitle, category, video, control!!
                ) { bytes, total ->
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastProgressAt).coerceAtLeast(1L)
                    val speed = if (elapsed >= 700L) ((bytes - lastProgressBytes) * 1000L / elapsed).coerceAtLeast(0L) else mutableState.value.speed
                    mutableState.value = mutableState.value.copy(bytes = bytes, total = total, speed = speed)
                    if (elapsed >= 700L) {
                        lastProgressAt = now
                        lastProgressBytes = bytes
                        updateNotification()
                    }
                }
                mutableState.value = DownloadUiState(status = "Download complete • ${record.name}", bytes = record.bytes, total = record.bytes)
                updateNotification(completed = true)
            } catch (e: CancellationException) {
                mutableState.value = DownloadUiState(status = "Download cancelled • $currentTitle")
                updateNotification(completed = true)
            } catch (e: Exception) {
                mutableState.value = DownloadUiState(status = "Download failed • ${e.message ?: "Network error"}")
                updateNotification(completed = true, failed = true)
            } finally {
                control = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(completed: Boolean = false, failed: Boolean = false): Notification {
        val s = mutableState.value
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val content = openIntent?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (completed) if (failed) "Download failed" else "Download complete" else "Mine Study download")
            .setContentText(s.status)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!completed && s.total > 0L) {
            val percent = ((s.bytes * 100L) / s.total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
                .setContentText("${percent}% • ${human(s.bytes)} / ${human(s.total)} • ${human(s.speed)}/s")
        } else if (!completed) builder.setProgress(0, 0, true)
        if (!completed) {
            val action = if (s.paused) ACTION_RESUME else ACTION_PAUSE
            val label = if (s.paused) "Resume" else "Pause"
            val icon = if (s.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            builder.addAction(icon, label, servicePending(action, 2))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", servicePending(ACTION_CANCEL, 3))
        }
        return builder.build()
    }

    private fun servicePending(action: String, request: Int) =
        PendingIntent.getService(this, request, Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun updateNotification(completed: Boolean = false, failed: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(completed, failed))
    }

    private fun human(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
