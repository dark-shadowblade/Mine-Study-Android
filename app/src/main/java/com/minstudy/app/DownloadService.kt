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
import java.util.ArrayDeque
import kotlin.math.max

data class DownloadQueueEntry(val id: String, val title: String, val category: String, val active: Boolean, val paused: Boolean)

data class DownloadUiState(
    val status: String = "",
    val active: Boolean = false,
    val paused: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val speed: Long = 0L,
    val title: String = "",
    val category: String = "",
    val itemKey: String = "",
    val runningCount: Int = 0,
    val queuedCount: Int = 0,
    val queueItems: List<DownloadQueueEntry> = emptyList(),
    val maxParallel: Int = 3
)

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "mine_study_downloads"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_START = "com.minstudy.app.download.START"
        private const val ACTION_PAUSE = "com.minstudy.app.download.PAUSE"
        private const val ACTION_RESUME = "com.minstudy.app.download.RESUME"
        private const val ACTION_CANCEL = "com.minstudy.app.download.CANCEL"
        private const val ACTION_PAUSE_ITEM = "com.minstudy.app.download.PAUSE_ITEM"
        private const val ACTION_RESUME_ITEM = "com.minstudy.app.download.RESUME_ITEM"
        private const val EXTRA_CONTROL_KEY = "control_key"
        private const val EXTRA_LIMIT = "parallel_limit"
        private const val ACTION_SET_LIMIT = "com.minstudy.app.download.SET_LIMIT"
        private const val ACTION_MOVE = "com.minstudy.app.download.MOVE"
        private const val ACTION_REMOVE_QUEUED = "com.minstudy.app.download.REMOVE_QUEUED"
        private const val ACTION_CLEAR_QUEUE = "com.minstudy.app.download.CLEAR_QUEUE"
        private const val EXTRA_MOVE = "move_direction"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_ITEM_KEY = "item_key"
        private val mutableState = MutableStateFlow(DownloadUiState())
        val state = mutableState.asStateFlow()

        fun start(context: Context, url: String, title: String, category: String, video: Boolean, itemKey: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_VIDEO, video)
                putExtra(EXTRA_ITEM_KEY, itemKey)
            }
            ContextCompat.startForegroundService(context, intent)
        }
        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun cancel(context: Context) = command(context, ACTION_CANCEL)
        fun pauseItem(context: Context, key: String) = itemCommand(context, ACTION_PAUSE_ITEM, key)
        fun resumeItem(context: Context, key: String) = itemCommand(context, ACTION_RESUME_ITEM, key)
        fun setParallelLimit(context: Context, value: Int) { context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_SET_LIMIT).putExtra(EXTRA_LIMIT, value.coerceIn(1, 5))) }
        fun moveQueued(context: Context, id: String, direction: String) { context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_MOVE).putExtra(EXTRA_CONTROL_KEY, id).putExtra(EXTRA_MOVE, direction)) }
        fun removeQueued(context: Context, id: String) = itemCommand(context, ACTION_REMOVE_QUEUED, id)
        fun clearQueue(context: Context) = command(context, ACTION_CLEAR_QUEUE)
        private fun itemCommand(context: Context, action: String, key: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action).putExtra(EXTRA_CONTROL_KEY, key))
        }
        private fun command(context: Context, action: String) {
            context.startService(Intent(context, DownloadService::class.java).setAction(action))
        }
    }

    private data class Task(
        val id: String,
        val url: String,
        val title: String,
        val category: String,
        val video: Boolean,
        val itemKey: String
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<Task>()
    private val active = linkedMapOf<String, Pair<Task, DownloadControl>>()
    private val resumeRequested = linkedSetOf<String>()
    private var maxParallel = 3
    private var foregroundStarted = false
    private var currentTitle = "Study material"
    private var lastProgressAt = 0L
    private var lastProgressBytes = 0L

    override fun onCreate() {
        super.onCreate()
        maxParallel = getSharedPreferences("download_manager_settings", MODE_PRIVATE).getInt("parallel_limit", 3).coerceIn(1, 5)
        mutableState.value = mutableState.value.copy(maxParallel = maxParallel)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Study downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and controls for Mine Study downloads"
                setShowBadge(true)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_NOT_STICKY
        when (command.action) {
            ACTION_START -> enqueue(command)
            ACTION_PAUSE -> {
                active.values.forEach { it.second.paused = true }
                mutableState.value = mutableState.value.copy(paused = true, status = "Pausing downloads…")
                updateNotification()
            }
            ACTION_RESUME -> {
                active.values.forEach { it.second.paused = false }
                mutableState.value = mutableState.value.copy(paused = false, status = "Downloading ${active.size} files")
                updateNotification()
            }
            ACTION_SET_LIMIT -> {
                maxParallel = command.getIntExtra(EXTRA_LIMIT, 3).coerceIn(1, 5)
                getSharedPreferences("download_manager_settings", MODE_PRIVATE).edit().putInt("parallel_limit", maxParallel).apply()
                pumpQueue(); refreshQueueState("Parallel downloads: $maxParallel")
            }
            ACTION_MOVE -> {
                val id = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                val direction = command.getStringExtra(EXTRA_MOVE).orEmpty()
                val list = pending.toMutableList(); val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val target = when (direction) { "top" -> 0; "up" -> (index - 1).coerceAtLeast(0); "down" -> (index + 1).coerceAtMost(list.lastIndex); "bottom" -> list.lastIndex; else -> index }
                    val task = list.removeAt(index); list.add(target, task); pending.clear(); list.forEach { pending.addLast(it) }
                }
                refreshQueueState("Queue order updated")
            }
            ACTION_CLEAR_QUEUE -> {
                pending.clear()
                refreshQueueState("Queued downloads cleared")
            }
            ACTION_REMOVE_QUEUED -> {
                val id = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                val remaining = pending.filterNot { it.id == id }; pending.clear(); remaining.forEach { pending.addLast(it) }
                refreshQueueState("Removed from queue")
            }
            ACTION_PAUSE_ITEM -> {
                val key = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                active[key]?.second?.paused = true
                pumpQueue()
                refreshQueueState("Paused • ${active[key]?.first?.title ?: "Download"}")
            }
            ACTION_RESUME_ITEM -> {
                val key = command.getStringExtra(EXTRA_CONTROL_KEY).orEmpty()
                if (active.containsKey(key)) resumeRequested.add(key)
                pumpQueue()
                refreshQueueState("Resume queued")
            }
            ACTION_CANCEL -> {
                pending.clear()
                active.values.forEach { it.second.cancelled = true }
                refreshQueueState("Cancelling downloads…")
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun enqueue(intent: Intent) {
        val task = Task(
            java.util.UUID.randomUUID().toString(),
            intent.getStringExtra(EXTRA_URL).orEmpty(),
            intent.getStringExtra(EXTRA_TITLE) ?: "Study material",
            intent.getStringExtra(EXTRA_CATEGORY) ?: "Lectures",
            intent.getBooleanExtra(EXTRA_VIDEO, true),
            intent.getStringExtra(EXTRA_ITEM_KEY).orEmpty()
        )
        if (task.url.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Download failed: URL missing")
            return
        }
        val duplicate = pending.any { it.itemKey == task.itemKey && it.itemKey.isNotBlank() } ||
            active.values.any { it.first.itemKey == task.itemKey && it.itemKey.isNotBlank() }
        if (duplicate) return
        pending.addLast(task)
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification())
            foregroundStarted = true
        }
        pumpQueue()
        refreshQueueState("Queued ${task.title}")
    }

    private fun pumpQueue() {
        var running = active.values.count { !it.second.paused }
        for (key in resumeRequested.toList()) {
            if (running >= maxParallel) break
            val entry = active[key]
            if (entry == null) {
                resumeRequested.remove(key)
            } else {
                entry.second.paused = false
                resumeRequested.remove(key)
                running++
            }
        }
        while (running < maxParallel && pending.isNotEmpty()) {
            val task = pending.removeFirst()
            val id = task.id
            val taskControl = DownloadControl()
            active[id] = task to taskControl
            running++
            serviceScope.launch { runTask(id, task, taskControl) }
        }
    }

    private suspend fun runTask(id: String, task: Task, taskControl: DownloadControl) {
        currentTitle = task.title
        lastProgressAt = System.currentTimeMillis()
        lastProgressBytes = 0L
        refreshQueueState("Downloading • ${task.title}")
        try {
            val record = DownloadRepository.download(
                this@DownloadService, task.url, task.title, task.category, task.video, task.itemKey, taskControl
            ) { bytes, total ->
                val now = System.currentTimeMillis()
                val elapsed = (now - lastProgressAt).coerceAtLeast(1L)
                val speed = if (elapsed >= 700L) ((bytes - lastProgressBytes) * 1000L / elapsed).coerceAtLeast(0L) else mutableState.value.speed
                mutableState.value = mutableState.value.copy(
                    status = "Downloading • ${task.title}",
                    active = true,
                    bytes = bytes,
                    total = total,
                    speed = speed,
                    title = task.title,
                    category = task.category,
                    itemKey = task.itemKey
                )
                if (elapsed >= 700L) {
                    lastProgressAt = now
                    lastProgressBytes = bytes
                    updateNotification()
                }
            }
            refreshQueueState("Completed • ${record.name}")
        } catch (e: CancellationException) {
            refreshQueueState("Cancelled • ${task.title}")
        } catch (e: Exception) {
            refreshQueueState("Failed • ${task.title}: ${e.message ?: "Network error"}")
        } finally {
            active.remove(id)
            resumeRequested.remove(id)
            pumpQueue()
            if (active.isEmpty() && pending.isEmpty()) {
                val previous = mutableState.value
                mutableState.value = previous.copy(active = false, paused = false, runningCount = 0, queuedCount = 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                foregroundStarted = false
                stopSelf()
            } else {
                refreshQueueState("Downloading ${active.size} • Queued ${pending.size}")
            }
        }
    }

    private fun refreshQueueState(message: String) {
        val previous = mutableState.value
        val first = active.values.firstOrNull { !it.second.paused }?.first ?: active.values.firstOrNull()?.first
        mutableState.value = previous.copy(
            status = message,
            active = active.isNotEmpty() || pending.isNotEmpty(),
            paused = active.isNotEmpty() && active.values.all { it.second.paused },
            title = first?.title ?: previous.title,
            category = first?.category ?: previous.category,
            itemKey = first?.itemKey ?: previous.itemKey,
            runningCount = active.values.count { !it.second.paused },
            queuedCount = pending.size,
            queueItems = active.map { (id, pair) -> DownloadQueueEntry(id, pair.first.title, pair.first.category, true, pair.second.paused) } +
                pending.map { task -> DownloadQueueEntry(task.id, task.title, task.category, false, false) },
            maxParallel = maxParallel
        )
        if (foregroundStarted) updateNotification()
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
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(if (completed) if (failed) "Download failed" else "Download complete" else currentTitle)
            .setSubText("Mine Study")
            .setContentText(s.status)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (!completed && s.total > 0L) {
            val percent = ((s.bytes * 100L) / s.total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
                .setContentText("${percent}% • ${human(s.bytes)} / ${human(s.total)} • ${human(s.speed)}/s")
        } else if (!completed) builder.setProgress(0, 0, true)
        if (!completed) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel all", servicePending(ACTION_CANCEL, 3))
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
