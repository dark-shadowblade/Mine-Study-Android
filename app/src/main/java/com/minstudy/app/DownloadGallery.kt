package com.minstudy.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.dp
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DownloadRecord(
    val uri: String,
    val name: String,
    val category: String,
    val mime: String,
    val bytes: Long
)

class DownloadControl {
    @Volatile var paused: Boolean = false
    @Volatile var cancelled: Boolean = false

    suspend fun checkpoint() {
        while (paused && !cancelled) delay(150)
        if (cancelled) throw CancellationException("Download cancelled")
    }
}

object DownloadRepository {
    private const val PREFS = "mine_study_downloads"
    private const val RECORDS = "records"

    fun list(context: Context): List<DownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECORDS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            DownloadRecord(
                item.optString("uri"), item.optString("name"), item.optString("category"),
                item.optString("mime"), item.optLong("bytes")
            ).takeIf { it.uri.isNotBlank() }
        }.filter { record ->
            runCatching { context.contentResolver.openFileDescriptor(Uri.parse(record.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }

    private fun save(context: Context, record: DownloadRecord) {
        val all = list(context).filterNot { it.uri == record.uri } + record
        val array = JSONArray()
        all.forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri); put("name", item.name); put("category", item.category)
                put("mime", item.mime); put("bytes", item.bytes)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECORDS, array.toString()).apply()
    }

    suspend fun download(
        context: Context,
        sourceUrl: String,
        title: String,
        category: String,
        isVideo: Boolean,
        control: DownloadControl = DownloadControl(),
        onProgress: (Long, Long) -> Unit
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Study material" }
        val extension = if (isVideo) ".mp4" else ".pdf"
        val fileName = (if (clean.endsWith(extension, true)) clean else clean + extension).take(180)
        val mime = if (isVideo) "video/mp4" else "application/pdf"
        val relative = Environment.DIRECTORY_DOWNLOADS + "/Mine Study/" + category
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Could not create download file")
        var written = 0L
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Could not open download file")
            output.use { out ->
                if (isVideo) {
                    val data = ChunkQueryDataSource()
                    try {
                        data.open(DataSpec(Uri.parse(sourceUrl)))
                        val total = data.discoveredLength
                        onProgress(0L, total)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (data.isAtChunkBoundary) control.checkpoint()
                            val count = data.read(buffer, 0, buffer.size)
                            if (count == C.RESULT_END_OF_INPUT) break
                            out.write(buffer, 0, count)
                            written += count
                            onProgress(written, total)
                        }
                    } finally { data.close() }
                } else {
                    val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*")
                    }
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) throw IllegalStateException("PDF download failed (HTTP $code)")
                        val total = connection.contentLengthLong
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                control.checkpoint()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                written += count
                                onProgress(written, total)
                            }
                        }
                    } finally { connection.disconnect() }
                }
            }
            if (written <= 0L) throw IllegalStateException("The server returned an empty file")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val record = DownloadRecord(uri.toString(), fileName, category, mime, written)
            save(context, record)
            record
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

@androidx.compose.runtime.Composable
fun DownloadGallery(
    records: List<DownloadRecord>,
    status: String,
    active: Boolean,
    paused: Boolean,
    bytes: Long,
    total: Long,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (DownloadRecord) -> Unit,
    onRefresh: () -> Unit
) {
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("All") }
    val categories = listOf("All", "Lectures", "Notes", "DPP", "DPP Videos")
    val visible = if (selected == "All") records else records.filter { it.category == selected }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.material3.Text("Downloads", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            androidx.compose.material3.TextButton(onClick = onRefresh) { androidx.compose.material3.Text("Refresh") }
        }
        if (status.isNotBlank()) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp)
                ) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text(status, modifier = Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
                        if (active) {
                            androidx.compose.material3.IconButton(onClick = onTogglePause) {
                                androidx.compose.material3.Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause")
                            }
                            androidx.compose.material3.IconButton(onClick = onCancel) {
                                androidx.compose.material3.Icon(Icons.Default.Close, "Cancel", tint = androidx.compose.material3.MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (active) {
                        if (total > 0L) {
                            val progress = (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                            androidx.compose.material3.Text(
                                "${bytes / (1024 * 1024)} MB / ${total / (1024 * 1024)} MB  •  ${(progress * 100).toInt()}%",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            androidx.compose.material3.Text(
                                "${bytes / (1024 * 1024)} MB downloaded",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            items(categories) { category ->
                androidx.compose.material3.FilterChip(
                    selected = selected == category,
                    onClick = { selected = category },
                    label = { androidx.compose.material3.Text(category) }
                )
            }
        }
        if (visible.isEmpty()) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Text("No downloads in this section yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.uri }) { record ->
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(record) },
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = if (record.mime.startsWith("video")) Icons.Default.PlayArrow else Icons.Default.MenuBook,
                                contentDescription = null
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(record.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
                                androidx.compose.material3.Text("${record.category} • ${record.bytes / (1024 * 1024)} MB", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.IconButton(onClick = { onOpen(record) }) {
                                androidx.compose.material3.Icon(Icons.Default.ArrowForward, contentDescription = "Open")
                            }
                        }
                    }
                }
            }
        }
    }
}
