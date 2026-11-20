package com.minstudy.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.Dispatchers
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
        val collection = MediaStore.Files.getContentUri("external")
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
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = data.read(buffer, 0, buffer.size)
                            if (count == C.RESULT_END_OF_INPUT) break
                            out.write(buffer, 0, count)
                            written += count
                            onProgress(written, -1L)
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
                                imageVector = if (record.mime.startsWith("video")) androidx.compose.material.icons.Icons.Default.PlayArrow else androidx.compose.material.icons.Icons.Default.MenuBook,
                                contentDescription = null
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(record.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
                                androidx.compose.material3.Text("${record.category} • ${record.bytes / (1024 * 1024)} MB", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.IconButton(onClick = { onOpen(record) }) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.OpenInNew, contentDescription = "Open")
                            }
                        }
                    }
                }
            }
        }
    }
}
