package com.minstudy.app

import android.content.Context
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val API = "https://v1.sgmvv1.xyz/api/v1"
private const val BATCH_ONE = "6a0eb0a7f33c042849418636"
private const val BATCH_TWO = "6a0eaf70f4ae45d1901b7196"

data class StudyItem(val id: String, val title: String, val subtitle: String = "", val raw: JSONObject = JSONObject())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MineStudyApp() }
    }
}

private class StudyApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("mine_study", Context.MODE_PRIVATE)
    var apiKey: String
        get() {
            val key = prefs.getString("api_key", "") ?: ""
            val expiry = prefs.getLong("api_key_expiry", 0L)
            if (key.isNotBlank() && (expiry == 0L || System.currentTimeMillis() >= expiry)) {
                prefs.edit().remove("api_key").remove("api_key_expiry").apply()
                return ""
            }
            return key
        }
        set(value) {
            val clean = value.trim()
            prefs.edit().putString("api_key", clean)
                .putLong("api_key_expiry", if (clean.isBlank()) 0L else System.currentTimeMillis() + 24L * 60L * 60L * 1000L)
                .apply()
        }

    var batchId: String
        get() = prefs.getString("batch_id", BATCH_ONE) ?: BATCH_ONE
        set(value) { prefs.edit().putString("batch_id", value).apply() }

    // Mirrors Mine-Study/index.html request(): validate the envelope, then
    // return json.data ?? json so callers receive the actual API payload.
    suspend fun get(path: String, keyOverride: String? = null): Any? = withContext(Dispatchers.IO) {
        val connection = (URL(API + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            val requestKey = keyOverride ?: apiKey
            if (requestKey.isNotBlank()) connection.setRequestProperty("x-api-key", requestKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
            // Match browser response.json(): the API may return an object or array.
            val payload = JSONTokener(body).nextValue()
            if (payload is JSONObject) {
                if (payload.has("success") && !payload.optBoolean("success", true)) {
                    throw IllegalStateException(payload.optString("message", "API returned an error"))
                }
                payload.opt("data")?.takeUnless { it == JSONObject.NULL } ?: payload
            } else {
                payload
            }
        } finally { connection.disconnect() }
    }

    suspend fun generateKeyLink(): String {
        val result = get("/auth/generate?server=1", "")
        val link = findString(result, setOf("keyUrl", "key_url"))
            ?: throw IllegalStateException("The server did not return data.keyUrl")
        if (!link.startsWith("https://", ignoreCase = true)) throw IllegalStateException("The server returned an invalid key link")
        return link
    }

    suspend fun verifyKey(candidate: String): Int {
        if (candidate.isBlank()) throw IllegalStateException("Enter an API key first")
        val response = get("/batch/$batchId/subject", candidate)
        return list(response, "subjects").size
    }

    fun findString(value: Any?, names: Set<String>): String? {
        when (value) {
            is JSONObject -> {
                for (name in names) value.optString(name).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) findString(value.opt(keys.next()), names)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findString(value.opt(i), names)?.let { return it }
        }
        return null
    }

    fun list(value: Any?, kind: String): List<StudyItem> {
        // Mine-Study request() returns json.data ?? json; each caller expects
        // that exact payload to be an array.
        val array = value as? JSONArray
            ?: throw IllegalStateException("Unexpected API response: expected an array for $kind")

        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = when (kind) {
                "subjects" -> first(obj, "_id")
                "chapters" -> first(obj, "chapter_id")
                "contents" -> first(obj, "_id", "videoId", "id")
                else -> null
            } ?: return@mapNotNull null

            val title = when (kind) {
                "subjects" -> first(obj, "subject")
                "chapters" -> first(obj, "name")
                "contents" -> first(obj, "name", "topic")
                else -> null
            } ?: "Untitled"
            val subtitle = if (kind == "contents") {
                first(obj, "duration", "date", "topic") ?: ""
            } else ""
            StudyItem(id, title, subtitle, obj)
        }
    }

    private fun findArray(value: Any?): JSONArray? {
        if (value is JSONArray) return value
        if (value is JSONObject) {
            for (key in listOf("data", "subjects", "chapters", "contents", "videos", "result", "items")) {
                val v = value.opt(key)
                if (v is JSONArray) return v
                if (v is JSONObject) findArray(v)?.let { return it }
            }
            val keys = value.keys()
            while (keys.hasNext()) findArray(value.opt(keys.next()))?.let { return it }
        }
        return null
    }

    private fun first(obj: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MineStudyApp() {
    val context = LocalContext.current
    val api = remember { StudyApi(context) }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(api.apiKey) }
    var batch by remember { mutableStateOf(api.batchId) }
    var screen by remember { mutableStateOf("home") }
    var type by remember { mutableStateOf("videos") }
    var subjects by remember { mutableStateOf(emptyList<StudyItem>()) }
    var chapters by remember { mutableStateOf(emptyList<StudyItem>()) }
    var contents by remember { mutableStateOf(emptyList<StudyItem>()) }
    var selectedSubject by remember { mutableStateOf<StudyItem?>(null) }
    var selectedChapter by remember { mutableStateOf<StudyItem?>(null) }
    var currentVideo by remember { mutableStateOf("") }
    var currentTitle by remember { mutableStateOf("") }
    var currentItemIndex by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var keyLink by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var downloadRecords by remember { mutableStateOf(DownloadRepository.list(context)) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloadBytes by remember { mutableStateOf(0L) }
    var downloadTotal by remember { mutableStateOf(-1L) }
    var downloadActive by remember { mutableStateOf(false) }
    var downloadPaused by remember { mutableStateOf(false) }
    var downloadControl by remember { mutableStateOf<DownloadControl?>(null) }

    fun loadSubjects() {
        busy = true; error = ""
        scope.launch {
            try {
                subjects = api.list(api.get("/batch/${api.batchId}/subject"), "subjects")
                screen = "subjects"
            } catch (e: Exception) { error = e.message ?: "Could not load batches" }
            finally { busy = false }
        }
    }
    LaunchedEffect(apiKey, batch) {
        if (apiKey.isNotBlank()) {
            api.batchId = batch
            loadSubjects()
        } else {
            screen = "home"
            error = "Add and verify your API key in Settings to load subjects."
        }
    }

    fun loadChapters(subject: StudyItem) {
        selectedSubject = subject; busy = true; error = ""
        scope.launch {
            try {
                chapters = api.list(api.get("/batch/${api.batchId}/subject/${enc(subject.id)}/chapter"), "chapters")
                screen = "chapters"
            } catch (e: Exception) { error = e.message ?: "Could not load chapters" }
            finally { busy = false }
        }
    }
    fun loadContents(chapter: StudyItem, contentType: String = type) {
        selectedChapter = chapter; type = contentType; busy = true; error = ""
        scope.launch {
            try {
                val path = "/batch/${api.batchId}/subject/${enc(selectedSubject!!.id)}/chapter/${enc(chapter.id)}/content?type=${enc(contentType)}"
                contents = api.list(api.get(path), "contents")
                screen = "contents"
            } catch (e: Exception) { error = e.message ?: "Could not load lessons" }
            finally { busy = false }
        }
    }

    fun startDownload(
        title: String,
        category: String,
        isVideo: Boolean,
        resolveUrl: suspend () -> String?
    ) {
        if (downloadActive) {
            Toast.makeText(context, "A download is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val control = DownloadControl()
        downloadControl = control
        downloadActive = true
        downloadPaused = false
        downloadBytes = 0L
        downloadTotal = -1L
        downloadStatus = "Preparing $title"
        scope.launch {
            try {
                val sourceUrl = resolveUrl()
                if (sourceUrl.isNullOrBlank()) throw IllegalStateException("Download URL is missing")
                downloadStatus = "Downloading $title"
                val record = DownloadRepository.download(context, sourceUrl, title, category, isVideo, control) { bytes, total ->
                    android.os.Handler(context.mainLooper).post {
                        downloadBytes = bytes
                        downloadTotal = total
                    }
                }
                downloadRecords = DownloadRepository.list(context)
                downloadStatus = "Download saved: " + record.name
                Toast.makeText(context, "Saved in Downloads/Mine Study/" + category, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                downloadStatus = "Download cancelled: $title"
            } catch (e: Exception) {
                downloadStatus = "Download failed: " + (e.message ?: "Unknown error")
                error = downloadStatus
            } finally {
                downloadActive = false
                downloadPaused = false
                downloadControl = null
            }
        }
    }

    fun playLecture(index: Int) {
        val item = contents.getOrNull(index) ?: return
        busy = true
        error = ""
        scope.launch {
            try {
                val detailPath = "/batch/" + api.batchId + "/subject/" + enc(selectedSubject!!.id) +
                    "/chapter/" + enc(selectedChapter!!.id) + "/content/" + enc(item.id)
                val detail = api.get(detailPath)
                val stream = findStreamUrl(detail)
                if (stream.isNullOrBlank()) throw IllegalStateException("No playable video URL was returned.")
                currentVideo = stream
                currentTitle = item.title
                currentItemIndex = index
                screen = "player"
            } catch (e: Exception) {
                error = e.message ?: "Unable to open lesson"
            } finally {
                busy = false
            }
        }
    }

    fun goBack() {
        screen = when (screen) {
            "player" -> "contents"
            "contents" -> "chapters"
            "chapters" -> "subjects"
            else -> "home"
        }
    }
    BackHandler(enabled = screen != "home") { goBack() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF9B8CFF), secondary = Color(0xFF71D6C5),
        background = Color(0xFF0B0D14), surface = Color(0xFF151925),
        surfaceVariant = Color(0xFF202638)
    )) {
        Scaffold(
            topBar = {
                if (screen != "player") {
                    TopAppBar(
                        title = { Column {
                            Text("MINE STUDY", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("Your learning space", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } },
                        navigationIcon = {
                            if (screen != "home") IconButton(onClick = { goBack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { downloadRecords = DownloadRepository.list(context); screen = "downloads" }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Downloads")
                            }
                            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0D14))
                    )
                }
            },
            containerColor = Color(0xFF0B0D14)
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if (screen == "player") 0.dp else 18.dp)) {
                when (screen) {
                    "home" -> {
                        Spacer(Modifier.height(26.dp))
                        Text("WELCOME TO MINE STUDY", color = Color(0xFF9B8CFF), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Your classes,\nall in one place.", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A))
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Connect your study library", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Add your API key to load subjects automatically.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Key, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Open settings")
                                }
                            }
                        }
                    }
                    "subjects" -> ItemList("Subjects", subjects, busy, error, onSelect = { loadChapters(it) }, onRetry = { loadSubjects() })
                    "chapters" -> {
                        Text(selectedSubject?.title ?: "Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        ItemList("Chapters", chapters, busy, error, onSelect = { loadContents(it) }, onRetry = { selectedSubject?.let { s -> loadChapters(s) } })
                    }
                    "contents" -> {
                        Text(selectedChapter?.title ?: "Lessons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("videos" to "Lectures", "notes" to "Notes", "DppNotes" to "DPP", "DppVideos" to "DPP videos").forEach { (key, label) ->
                                FilterChip(selected = type == key, onClick = { selectedChapter?.let { loadContents(it, key) } }, label = { Text(label, fontSize = 11.sp) })
                            }
                        }
                        ItemList(
                            "Learning material", contents, busy, error,
                            showThumbnails = true,
                            actionLabel = if (type == "videos" || type == "DppVideos") "Play" else "Open PDF",
                            onDownload = { item ->
                                val video = type == "videos" || type == "DppVideos"
                                val category = when (type) {
                                    "videos" -> "Lectures"
                                    "notes" -> "Notes"
                                    "DppNotes" -> "DPP"
                                    "DppVideos" -> "DPP Videos"
                                    else -> type
                                }
                                startDownload(item.title, category, video) {
                                    if (video) {
                                        val detailPath = "/batch/" + api.batchId + "/subject/" + enc(selectedSubject!!.id) +
                                            "/chapter/" + enc(selectedChapter!!.id) + "/content/" + enc(item.id)
                                        findStreamUrl(api.get(detailPath))
                                    } else api.findString(item.raw, setOf("baseUrl"))
                                }
                            },
                            onSelect = { item ->
                                if (type == "videos" || type == "DppVideos") {
                                    playLecture(contents.indexOfFirst { it.id == item.id })
                                } else {
                                    val url = api.findString(item.raw, setOf("baseUrl", "base_url", "url", "fileUrl", "file_url", "downloadUrl"))
                                    if (url != null) openExternal(context, url) else error = "No document link found for this item."
                                }
                            }, onRetry = { selectedChapter?.let { loadContents(it) } })
                    }
                    "player" -> VideoScreen(
                        currentTitle,
                        currentVideo,
                        hasPrevious = currentItemIndex > 0,
                        hasNext = currentItemIndex in 0 until contents.lastIndex,
                        onPrevious = { playLecture(currentItemIndex - 1) },
                        onNext = { playLecture(currentItemIndex + 1) },
                        onBack = { screen = "contents" },
                        downloadStatus = downloadStatus,
                        downloadActive = downloadActive,
                        downloadPaused = downloadPaused,
                        downloadBytes = downloadBytes,
                        downloadTotal = downloadTotal,
                        onTogglePause = {
                            val next = !downloadPaused
                            downloadPaused = next
                            downloadControl?.paused = next
                        },
                        onCancelDownload = { downloadControl?.cancelled = true },
                        onDownload = {
                            val category = if (type == "DppVideos") "DPP Videos" else "Lectures"
                            startDownload(currentTitle, category, true) { currentVideo }
                        }
                    )
                    "downloads" -> DownloadGallery(
                        records = downloadRecords,
                        status = downloadStatus,
                        active = downloadActive,
                        paused = downloadPaused,
                        bytes = downloadBytes,
                        total = downloadTotal,
                        onTogglePause = {
                            val next = !downloadPaused
                            downloadPaused = next
                            downloadControl?.paused = next
                        },
                        onCancel = { downloadControl?.cancelled = true },
                        onOpen = { record ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(Uri.parse(record.uri), record.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) {
                                Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRefresh = { downloadRecords = DownloadRepository.list(context) }
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFF8A8A), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("App settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Batch server", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = batch == BATCH_ONE, onClick = { batch = BATCH_ONE }, label = { Text("Server 1") })
                        FilterChip(selected = batch == BATCH_TWO, onClick = { batch = BATCH_TWO }, label = { Text("Server 2") })
                    }
                    Text("API key", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Paste your API key") })
                    Text("The key is stored locally on this device. It is not included in the app source.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    OutlinedButton(onClick = {
                        scope.launch {
                            keyStatus = "Generating key link..."
                            try {
                                keyLink = api.generateKeyLink()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Mine Study key link", keyLink))
                                keyStatus = "Key link copied. Open the page and obtain your API key."
                                openExternal(context, keyLink)
                            } catch (e: Exception) { keyStatus = e.message ?: "Could not generate key link" }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text("Generate key link") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            keyStatus = "Verifying API key..."
                            try {
                                val count = api.verifyKey(apiKey)
                                api.apiKey = apiKey
                                keyStatus = "Verified successfully. $count subjects found. Expires in 24 hours."
                            } catch (e: Exception) { keyStatus = e.message ?: "API key verification failed" }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Verify API key") }
                    if (keyStatus.isNotBlank()) Text(keyStatus, style = MaterialTheme.typography.bodySmall, color = if (keyStatus.contains("Verified successfully")) Color(0xFF71D6C5) else Color.Gray)
                    TextButton(onClick = {
                        api.apiKey = ""
                        apiKey = ""
                        keyStatus = "Saved API key cleared."
                    }) { Text("Clear saved key", color = Color(0xFFFF8A8A)) }
                    if (keyLink.isNotBlank()) Text(keyLink, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5A9FF))
                }
            },
            confirmButton = { TextButton(onClick = { api.apiKey = apiKey; api.batchId = batch; showSettings = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ItemList(
    heading: String, items: List<StudyItem>, busy: Boolean, error: String,
    actionLabel: String = "Open",
    showThumbnails: Boolean = false,
    onDownload: ((StudyItem) -> Unit)? = null,
    onSelect: (StudyItem) -> Unit, onRetry: () -> Unit
) {
    Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (!busy && items.isEmpty() && error.isBlank()) {
        Text("Nothing here yet. Check your API key or try refreshing.", color = Color.Gray)
        TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("Refresh") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(item) },
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A))
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    val thumbnail = item.raw.optString("image").takeIf { showThumbnails && it.startsWith("https://", true) }
                    if (thumbnail != null) {
                        AsyncImage(
                            model = thumbnail,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 112.dp, height = 72.dp).clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(Modifier.size(width = if (showThumbnails) 112.dp else 44.dp, height = if (showThumbnails) 72.dp else 44.dp).background(Color(0xFF292641), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFB5A9FF))
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton(onClick = { onSelect(item) }) { Text(actionLabel) }
                    if (onDownload != null) {
                        IconButton(onClick = { onDownload(item) }) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color(0xFF71D6C5))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusPanel(
    status: String,
    active: Boolean,
    paused: Boolean,
    bytes: Long,
    total: Long,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
            .background(Color(0xDD151925), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, modifier = Modifier.weight(1f), color = if (status.startsWith("Download failed")) Color(0xFFFF8A8A) else Color(0xFF71D6C5), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (active) {
                TextButton(onClick = onTogglePause) { Text(if (paused) "Resume" else "Pause") }
                TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFFF8A8A)) }
            }
        }
        if (active) {
            if (total > 0L) {
                val fraction = (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(bytes)} / ${formatBytes(total)}  •  ${(fraction * 100).toInt()}%", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(formatBytes(bytes) + " downloaded", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun VideoScreen(
    title: String,
    url: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    downloadStatus: String,
    downloadActive: Boolean,
    downloadPaused: Boolean,
    downloadBytes: Long,
    downloadTotal: Long,
    onTogglePause: () -> Unit,
    onCancelDownload: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var fullScreen by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ChunkQueryDataSourceFactory()))
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var playerError by remember(player) { mutableStateOf("") }

    DisposableEffect(player) {
        player.setPlaybackSpeed(playbackSpeed)
        val listener = object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == com.google.android.exoplayer2.Player.STATE_ENDED && hasNext) onNext()
            }
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                playerError = error.message ?: "Playback failed"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(fullScreen) {
        val window = activity?.window
        if (fullScreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (window != null) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (window != null) WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun cyclePlaybackSpeed() {
        playbackSpeed = when (playbackSpeed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 1.75f
            1.75f -> 2f
            else -> 1f
        }
        player.setPlaybackSpeed(playbackSpeed)
    }

    fun makePlayerView(ctx: Context) = PlayerView(ctx).apply {
        this.player = player
        useController = true
        controllerAutoShow = true
        controllerShowTimeoutMs = 4500
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        setShowRewindButton(true)
        setShowFastForwardButton(true)
        resizeMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { makePlayerView(it) }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp))
                TextButton(onClick = { cyclePlaybackSpeed() }) {
                    Text("${playbackSpeed}×", color = Color.White)
                }
                IconButton(onClick = { fullScreen = false }) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
                }
            }
            if (downloadStatus.isNotBlank()) {
                DownloadStatusPanel(
                    status = downloadStatus, active = downloadActive, paused = downloadPaused,
                    bytes = downloadBytes, total = downloadTotal, onTogglePause = onTogglePause,
                    onCancel = onCancelDownload,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                TextButton(onClick = { cyclePlaybackSpeed() }) { Text("${playbackSpeed}×") }
                IconButton(onClick = { fullScreen = true }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
                }
            }
            AndroidView(factory = { makePlayerView(it) }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (playbackState == com.google.android.exoplayer2.Player.STATE_BUFFERING) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Buffering video…", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (playerError.isNotBlank()) Text(playerError, color = Color(0xFFFF8A8A), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPrevious, enabled = hasPrevious, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Previous")
                }
                OutlinedButton(onClick = onNext, enabled = hasNext, modifier = Modifier.weight(1f)) {
                    Text("Next")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download lecture")
            }
            if (downloadStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                DownloadStatusPanel(
                    status = downloadStatus, active = downloadActive, paused = downloadPaused,
                    bytes = downloadBytes, total = downloadTotal, onTogglePause = onTogglePause,
                    onCancel = onCancelDownload
                )
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    return String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0)
}

private fun findStreamUrl(value: Any?): String? {
    when (value) {
        is JSONObject -> {
            for (key in listOf("streamUrl", "stream_url", "videoUrl", "video_url", "url")) {
                val candidate = value.optString(key)
                if (candidate.startsWith("https://") || candidate.startsWith("http://")) return candidate
            }
            val keys = value.keys()
            while (keys.hasNext()) findStreamUrl(value.opt(keys.next()))?.let { return it }
        }
        is JSONArray -> for (i in 0 until value.length()) findStreamUrl(value.opt(i))?.let { return it }
        is String -> if (value.startsWith("https://") || value.startsWith("http://")) return value
    }
    return null
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
private fun openExternal(context: Context, value: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}
private fun downloadVideo(context: Context, url: String, key: String, title: String) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        var mediaUri: Uri? = null
        try {
            val safeName = title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "MineStudy_Lecture" } + ".mp4"
            val resolver = context.contentResolver
            val output = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mine Study")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                mediaUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create the download file")
                resolver.openOutputStream(mediaUri!!, "w")
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("Downloads folder unavailable")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, safeName)
                mediaUri = Uri.fromFile(file)
                FileOutputStream(file)
            } ?: throw IllegalStateException("Could not open the download file")
            output.use { out ->
                val source = ChunkQueryDataSource()
                try {
                    source.open(DataSpec(Uri.parse(url)))
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count == com.google.android.exoplayer2.C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, count)
                    }
                } finally { source.close() }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(mediaUri!!, values, null, null)
            }
            android.os.Handler(context.mainLooper).post {
                Toast.makeText(context, "Download saved in Downloads/Mine Study", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
            android.os.Handler(context.mainLooper).post {
                Toast.makeText(context, "Download failed: " + (e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
