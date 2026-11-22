package com.minstudy.app

import android.content.Context
import android.app.Activity
import android.media.AudioManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.google.android.exoplayer2.upstream.DefaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4107)
        }
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
        var lastError: Exception? = null
        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(API + path).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 25000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                val requestKey = keyOverride ?: apiKey
                if (requestKey.isNotBlank()) connection.setRequestProperty("x-api-key", requestKey)
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = "HTTP $code: $body"
                    if ((code == 429 || code in 500..599) && attempt < 2) {
                        lastError = java.io.IOException(message)
                        delay(400L * (attempt + 1))
                        return@repeat
                    }
                    throw IllegalStateException(message)
                }
                // Match browser response.json(): the API may return an object or array.
                val payload = JSONTokener(body).nextValue()
                if (payload is JSONObject) {
                    if (payload.has("success") && !payload.optBoolean("success", true)) {
                        throw IllegalStateException(payload.optString("message", "API returned an error"))
                    }
                    return@withContext payload.opt("data")?.takeUnless { it == JSONObject.NULL } ?: payload
                }
                return@withContext payload
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(400L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Network request failed after 3 attempts: ${lastError?.message ?: "unknown network error"}", lastError)
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
    val progressPrefs = remember { context.getSharedPreferences("mine_study_progress", Context.MODE_PRIVATE) }
    var lastSubjectId by remember { mutableStateOf(progressPrefs.getString("subject_id", "").orEmpty()) }
    var lastSubjectTitle by remember { mutableStateOf(progressPrefs.getString("subject_title", "").orEmpty()) }
    var lastChapterId by remember { mutableStateOf(progressPrefs.getString("chapter_id", "").orEmpty()) }
    var lastChapterTitle by remember { mutableStateOf(progressPrefs.getString("chapter_title", "").orEmpty()) }
    var lastProgressBatch by remember { mutableStateOf(progressPrefs.getString("batch_id", "").orEmpty()) }
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
    var pendingDownloadTitle by remember { mutableStateOf("") }
    var pendingDownloadCategory by remember { mutableStateOf("") }
    var pendingDownloadKey by remember { mutableStateOf("") }
    val downloadState by DownloadService.state.collectAsState()
    LaunchedEffect(downloadState.active, downloadState.status) {
        if (downloadState.active || downloadState.status.startsWith("Download complete") ||
            downloadState.status.startsWith("Download failed") || downloadState.status.startsWith("Download cancelled")) {
            pendingDownloadTitle = ""
            pendingDownloadCategory = ""
            pendingDownloadKey = ""
        }
        if (downloadState.status.startsWith("Download complete")) {
            downloadRecords = DownloadRepository.list(context)
        }
    }

    fun loadSubjects() {
        busy = true; error = ""
        scope.launch {
            try {
                subjects = api.list(api.get("/batch/${api.batchId}/subject"), "subjects")
                screen = "home"
            } catch (e: Exception) { error = e.message ?: "Could not load batches" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        if (apiKey.isNotBlank()) {
            api.batchId = batch
            busy = true
            try {
                subjects = api.list(api.get("/batch/${api.batchId}/subject"), "subjects")
                screen = "home"
                error = ""
            } catch (e: Exception) {
                screen = "home"
                error = e.message ?: "Could not load your study library"
            } finally {
                busy = false
            }
        } else {
            screen = "home"
            error = "Add and verify your API key in Settings to load subjects."
        }
    }

    // Pasting a key saves it immediately and verifies it automatically.
    LaunchedEffect(apiKey, batch, showSettings) {
        if (showSettings && apiKey.isNotBlank()) {
            delay(700)
            api.batchId = batch
            keyStatus = "Checking API key..."
            try {
                val count = api.verifyKey(apiKey)
                api.apiKey = apiKey
                subjects = api.list(api.get("/batch/${api.batchId}/subject"), "subjects")
                keyStatus = "Verified · $count subjects found"
                error = ""
            } catch (e: Exception) {
                keyStatus = e.message ?: "API key verification failed"
            }
        }
    }

    fun loadChapters(subject: StudyItem) {
        if (selectedSubject?.id == subject.id && chapters.isNotEmpty()) {
            screen = "chapters"
            return
        }
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
        selectedChapter = chapter
        lastChapterId = chapter.id
        lastChapterTitle = chapter.title
        lastSubjectId = selectedSubject?.id.orEmpty()
        lastSubjectTitle = selectedSubject?.title.orEmpty()
        lastProgressBatch = api.batchId
        progressPrefs.edit()
            .putString("subject_id", lastSubjectId)
            .putString("subject_title", lastSubjectTitle)
            .putString("chapter_id", lastChapterId)
            .putString("chapter_title", lastChapterTitle)
            .putString("batch_id", lastProgressBatch)
            .apply()
        if (selectedChapter?.id == chapter.id && type == contentType && contents.isNotEmpty()) {
            screen = "contents"
            return
        }
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
        itemKey: String,
        resolveUrl: suspend () -> String?
    ) {
        if (downloadState.active) {
            Toast.makeText(context, "A download is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = DownloadRepository.list(context).any { record ->
            record.category == category && (record.itemKey == itemKey ||
                (record.itemKey.isBlank() && record.name.substringBeforeLast('.').equals(title.replace(Regex("[\\\\/:*?\\\"<>|]"), "_").trim(), true)))
        }
        if (existing) {
            Toast.makeText(context, "Already downloaded", Toast.LENGTH_SHORT).show()
            downloadRecords = DownloadRepository.list(context)
            return
        }
        pendingDownloadTitle = title
        pendingDownloadCategory = category
        pendingDownloadKey = itemKey
        scope.launch {
            try {
                val sourceUrl = resolveUrl()
                if (sourceUrl.isNullOrBlank()) throw IllegalStateException("Download URL is missing")
                DownloadService.start(context, sourceUrl, title, category, isVideo, itemKey)
            } catch (e: Exception) {
                pendingDownloadTitle = ""
                pendingDownloadCategory = ""
                pendingDownloadKey = ""
                error = "Could not start download: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun playLecture(index: Int) {
        val item = contents.getOrNull(index) ?: return
        val category = if (type == "DppVideos") "DPP Videos" else "Lectures"
        val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val itemKey = "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${item.id}"
        val localCopy = DownloadRepository.list(context).firstOrNull { record ->
            record.category == category && (record.itemKey == itemKey ||
                (record.itemKey.isBlank() && record.name.substringBeforeLast('.').equals(cleanTitle, ignoreCase = true)))
                && record.mime.startsWith("video/")
        }
        // Prefer the completed local file. Only resolve the online stream when no
        // valid downloaded copy exists, so playback continues without internet.
        if (localCopy != null) {
            currentVideo = localCopy.uri
            currentTitle = item.title
            currentItemIndex = index
            downloadRecords = DownloadRepository.list(context)
            screen = "player"
            return
        }
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

    fun resumeLastChapter() {
        if (lastSubjectId.isBlank() || lastChapterId.isBlank() || lastProgressBatch != api.batchId) return
        busy = true
        error = ""
        scope.launch {
            try {
                val subject = subjects.firstOrNull { it.id == lastSubjectId }
                    ?: StudyItem(lastSubjectId, lastSubjectTitle.ifBlank { "Last subject" }, "", JSONObject())
                selectedSubject = subject
                val chapterPayload = api.get("/batch/${api.batchId}/subject/${enc(subject.id)}/chapter")
                chapters = api.list(chapterPayload, "chapters")
                val chapter = chapters.firstOrNull { it.id == lastChapterId }
                    ?: StudyItem(lastChapterId, lastChapterTitle.ifBlank { "Last chapter" }, "", JSONObject())
                selectedChapter = chapter
                val contentPath = "/batch/${api.batchId}/subject/${enc(subject.id)}/chapter/${enc(chapter.id)}/content?type=${enc(type)}"
                contents = api.list(api.get(contentPath), "contents")
                screen = "contents"
            } catch (e: Exception) {
                error = e.message ?: "Could not resume your last chapter"
            } finally { busy = false }
        }
    }

    fun goBack() {
        screen = when (screen) {
            "player" -> "contents"
            "contents" -> "chapters"
            "chapters" -> "home"
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
                        if (apiKey.isBlank()) {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Key, null, tint = Color(0xFFB5A9FF))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Connect your library", fontWeight = FontWeight.SemiBold)
                                        Text("Add an API key to load your subjects.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { showSettings = true }) { Text("Set up") }
                                }
                            }
                        } else {
                            if (lastSubjectId.isNotBlank() && lastChapterId.isNotBlank() && lastProgressBatch == api.batchId) {
                                Card(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { resumeLastChapter() },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A)),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF71D6C5))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Continue learning", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text("${lastSubjectTitle} · ${lastChapterTitle}", maxLines = 1, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Icon(Icons.Default.ArrowForward, "Continue", tint = Color(0xFFB5A9FF))
                                    }
                                }
                            }
                            ItemList(
                                " ", subjects, busy, error,
                                actionLabel = "Open",
                                onSelect = { loadChapters(it) },
                                onRetry = {}
                            )
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
                            downloadCategory = when (type) {
                                "videos" -> "Lectures"
                                "notes" -> "Notes"
                                "DppNotes" -> "DPP"
                                "DppVideos" -> "DPP Videos"
                                else -> type
                            },
                            downloadRecords = downloadRecords,
                            activeDownloadTitle = if (downloadState.active) downloadState.title else pendingDownloadTitle,
                            activeDownloadCategory = if (downloadState.active) downloadState.category else pendingDownloadCategory,
                            activeDownloadKey = if (downloadState.active) downloadState.itemKey else pendingDownloadKey,
                            downloadItemKey = { item -> "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${item.id}" },
                            activeDownloadPercent = if (downloadState.active && downloadState.total > 0L) ((downloadState.bytes * 100L) / downloadState.total).toInt().coerceIn(0, 100) else 0,
                            onDownload = { item ->
                                val video = type == "videos" || type == "DppVideos"
                                val category = when (type) {
                                    "videos" -> "Lectures"
                                    "notes" -> "Notes"
                                    "DppNotes" -> "DPP"
                                    "DppVideos" -> "DPP Videos"
                                    else -> type
                                }
                                val itemKey = "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${item.id}"
                                startDownload(item.title, category, video, itemKey) {
                                    if (video) {
                                        val detailPath = "/batch/" + api.batchId + "/subject/" + enc(selectedSubject!!.id) +
                                            "/chapter/" + enc(selectedChapter!!.id) + "/content/" + enc(item.id)
                                        findStreamUrl(api.get(detailPath))
                                    } else item.raw.optString("baseUrl").takeIf { it.startsWith("http", true) }
                                }
                            },
                            onSelect = { item ->
                                if (type == "videos" || type == "DppVideos") {
                                    playLecture(contents.indexOfFirst { it.id == item.id })
                                } else {
                                    val category = when (type) {
                                        "notes" -> "Notes"
                                        "DppNotes" -> "DPP"
                                        else -> type
                                    }
                                    val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                                    val itemKey = "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${item.id}"
                                    val localCopy = downloadRecords.firstOrNull { record ->
                                        record.category == category && (record.itemKey == itemKey ||
                                            (record.itemKey.isBlank() && record.name.substringBeforeLast('.').equals(cleanTitle, ignoreCase = true))) && record.mime == "application/pdf"
                                    }
                                    if (localCopy != null) {
                                        // Open the saved PDF URI; no API/CDN request is made.
                                        openLocalDocument(context, Uri.parse(localCopy.uri))
                                    } else {
                                        val url = item.raw.optString("baseUrl").takeIf { it.startsWith("http", true) }
                                        if (url != null) openExternal(context, url) else error = "No document link found for this item."
                                    }
                                }
                            }, onRetry = { selectedChapter?.let { loadContents(it) } })
                    }
                    "player" -> VideoScreen(
                        currentTitle,
                        currentVideo,
                        playbackKey = contents.getOrNull(currentItemIndex)?.id ?: currentVideo,
                        hasPrevious = currentItemIndex < contents.lastIndex,
                        hasNext = currentItemIndex > 0,
                        onPrevious = { playLecture(currentItemIndex + 1) },
                        onNext = { playLecture(currentItemIndex - 1) },
                        onBack = { screen = "contents" },
                        isDownloaded = downloadRecords.any { record ->
                            record.category == (if (type == "DppVideos") "DPP Videos" else "Lectures") &&
                                (record.itemKey == "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${contents.getOrNull(currentItemIndex)?.id}" ||
                                    (record.itemKey.isBlank() && record.name.substringBeforeLast('.').equals(currentTitle.replace(Regex("[\\\\/:*?\\\"<>|]"), "_").trim(), true)))
                        },
                        downloadPercent = if (downloadState.active && downloadState.itemKey == "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${contents.getOrNull(currentItemIndex)?.id}" && downloadState.total > 0L) ((downloadState.bytes * 100L) / downloadState.total).toInt().coerceIn(0, 100) else 0,
                        isDownloading = (downloadState.active && downloadState.itemKey == "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${contents.getOrNull(currentItemIndex)?.id}") || (pendingDownloadKey == "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${contents.getOrNull(currentItemIndex)?.id}"),
                        onDownload = {
                            val category = if (type == "DppVideos") "DPP Videos" else "Lectures"
                            val itemKey = "${api.batchId}:${selectedSubject?.id}:${selectedChapter?.id}:${contents.getOrNull(currentItemIndex)?.id}"
                            startDownload(currentTitle, category, true, itemKey) { currentVideo }
                        }
                    )
                    "downloads" -> DownloadGallery(
                        records = downloadRecords,
                        status = downloadState.status,
                        active = downloadState.active,
                        paused = downloadState.paused,
                        bytes = downloadState.bytes,
                        total = downloadState.total,
                        speed = downloadState.speed,
                        onTogglePause = { if (downloadState.paused) DownloadService.resume(context) else DownloadService.pause(context) },
                        onCancel = { DownloadService.cancel(context) },
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
            shape = RoundedCornerShape(22.dp),
            containerColor = Color(0xFF141A26),
            tonalElevation = 0.dp,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Settings", fontWeight = FontWeight.Bold)
                    Text("Library connection", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("SERVER", color = Color.Gray, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = batch == BATCH_ONE,
                            onClick = { batch = BATCH_ONE; api.batchId = BATCH_ONE },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Server 1") }
                        SegmentedButton(
                            selected = batch == BATCH_TWO,
                            onClick = { batch = BATCH_TWO; api.batchId = BATCH_TWO },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Server 2") }
                    }
                    HorizontalDivider(color = Color(0xFF303747))
                    Text("API KEY", color = Color.Gray, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { value -> apiKey = value; api.apiKey = value; api.batchId = batch },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFFB5A9FF),
                            focusedBorderColor = Color(0xFF9B8CFF),
                            unfocusedBorderColor = Color(0xFF343D50),
                            focusedContainerColor = Color(0xFF101521),
                            unfocusedContainerColor = Color(0xFF101521),
                            focusedPlaceholderColor = Color(0xFF8791A5),
                            unfocusedPlaceholderColor = Color(0xFF8791A5)
                        ),
                        placeholder = { Text("Paste your API key", style = MaterialTheme.typography.bodySmall) }
                    )
                    Text("Stored securely on this device · Expires after 24 hours", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
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
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                        ) { Icon(Icons.Default.Key, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Generate", maxLines = 1) }
                        Button(
                            onClick = {
                                scope.launch {
                                    keyStatus = "Verifying API key..."
                                    try {
                                        val count = api.verifyKey(apiKey)
                                        api.apiKey = apiKey
                                        keyStatus = "Verified · $count subjects found"
                                        loadSubjects()
                                    } catch (e: Exception) { keyStatus = e.message ?: "API key verification failed" }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                        ) { Text("Verify key", maxLines = 1) }
                    }
                    if (keyStatus.isNotBlank()) {
                        Text(
                            keyStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (keyStatus.contains("Verified")) Color(0xFF71D6C5) else Color.Gray
                        )
                    }
                    TextButton(
                        onClick = {
                            api.apiKey = ""
                            apiKey = ""
                            keyStatus = "Saved API key cleared."
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) { Text("Clear saved key", color = Color(0xFFFF8A8A), style = MaterialTheme.typography.labelSmall) }
                    if (keyLink.isNotBlank()) Text(keyLink, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB5A9FF), maxLines = 2)
                }
            },
            confirmButton = { TextButton(onClick = {
                api.apiKey = apiKey
                api.batchId = batch
                showSettings = false
                if (apiKey.isNotBlank()) loadSubjects()
                else { screen = "home"; error = "Add an API key in Settings." }
            }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ItemList(
    heading: String, items: List<StudyItem>, busy: Boolean, error: String,
    actionLabel: String = "Open",
    showThumbnails: Boolean = false,
    downloadCategory: String = "",
    downloadRecords: List<DownloadRecord> = emptyList(),
    activeDownloadTitle: String = "",
    activeDownloadCategory: String = "",
    activeDownloadKey: String = "",
    downloadItemKey: (StudyItem) -> String = { it.id },
    activeDownloadPercent: Int = 0,
    onDownload: ((StudyItem) -> Unit)? = null,
    onSelect: (StudyItem) -> Unit, onRetry: () -> Unit
 ) {
    var searchQuery by remember(heading) { mutableStateOf("") }
    val searchable = heading == "Chapters" || showThumbnails
    val visibleItems = remember(items, searchQuery) { if (searchQuery.isBlank()) items else items.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) } }
    Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (searchable) {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (showThumbnails) "Search lectures…" else "Search chapters…") },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF101521), unfocusedContainerColor = Color(0xFF101521),
                focusedBorderColor = Color(0xFF9B8CFF), unfocusedBorderColor = Color(0xFF343D50)
            )
        )
    }
    Spacer(Modifier.height(8.dp))
    if (!busy && visibleItems.isEmpty() && error.isBlank()) {
        Text("Nothing here yet. Check your API key or try refreshing.", color = Color.Gray)
        TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("Refresh") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(visibleItems, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(item) },
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2A))
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val thumbnail = item.raw.optString("image").takeIf { showThumbnails && it.startsWith("http", true) }
                        if (thumbnail != null) {
                            AsyncImage(
                                model = thumbnail,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 136.dp, height = 88.dp).clip(RoundedCornerShape(13.dp))
                            )
                        } else {
                            Box(
                                Modifier.size(width = if (showThumbnails) 136.dp else 48.dp, height = if (showThumbnails) 88.dp else 48.dp)
                                    .background(Color(0xFF292641), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFB5A9FF), modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedButton(onClick = { onSelect(item) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(actionLabel, maxLines = 1)
                        }
                        if (onDownload != null) {
                            val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                            val itemKey = downloadItemKey(item)
                            val downloaded = downloadRecords.any { record ->
                                record.category == downloadCategory && (record.itemKey == itemKey ||
                                    (record.itemKey.isBlank() && record.name.substringBeforeLast('.').equals(cleanTitle, ignoreCase = true)))
                            }
                            val downloading = activeDownloadKey == itemKey && activeDownloadCategory == downloadCategory
                            IconButton(
                                onClick = { if (!downloaded && !downloading) onDownload(item) },
                                enabled = !downloaded && !downloading,
                                modifier = Modifier.size(44.dp)
                            ) {
                                when {
                                    downloading -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(25.dp), strokeWidth = 2.dp, color = Color(0xFFB5A9FF))
                                        Text("$activeDownloadPercent%", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                    downloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = Color(0xFF38C879), modifier = Modifier.size(25.dp))
                                    else -> Icon(Icons.Default.Download, contentDescription = "Download", tint = Color(0xFF858B98), modifier = Modifier.size(25.dp))
                                }
                            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoScreen(
    title: String,
    url: String,
    playbackKey: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    downloadPercent: Int = 0,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val playbackPrefs = remember { context.getSharedPreferences("mine_study_playback", Context.MODE_PRIVATE) }
    val savedPositionKey = remember(playbackKey) { "position_$playbackKey" }
    var fullScreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var speedText by remember { mutableStateOf("1.25") }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf("") }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var volumePanel by remember { mutableStateOf(false) }
    var previousVolume by remember { mutableIntStateOf(volume.coerceAtLeast(1)) }

    val player = remember(url, playbackKey) {
        val mediaUri = Uri.parse(url)
        val sourceFactory = if (mediaUri.scheme == "content" || mediaUri.scheme == "file") {
            DefaultDataSource.Factory(context)
        } else {
            ChunkQueryDataSourceFactory()
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : com.google.android.exoplayer2.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == com.google.android.exoplayer2.Player.STATE_BUFFERING
                if (state == com.google.android.exoplayer2.Player.STATE_ENDED && hasNext) onNext()
            }
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                playerError = error.message ?: "Playback failed"
                showControls = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (player.playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) playbackPrefs.edit().remove(savedPositionKey).apply()
            else if (player.currentPosition > 0L) playbackPrefs.edit().putLong(savedPositionKey, player.currentPosition).apply()
            player.release()
        }
    }

    LaunchedEffect(player, savedPositionKey) {
        while (player.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE || player.playbackState == com.google.android.exoplayer2.Player.STATE_BUFFERING) delay(150)
        if (player.playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
            val saved = playbackPrefs.getLong(savedPositionKey, 0L)
            val duration = player.duration
            if (saved > 5_000L && (duration <= 0L || duration == com.google.android.exoplayer2.C.TIME_UNSET || saved < duration - 5_000L)) player.seekTo(saved)
        }
        while (true) {
            delay(1_000)
            if (player.playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) playbackPrefs.edit().remove(savedPositionKey).apply()
            else if (!isScrubbing && player.playbackState == com.google.android.exoplayer2.Player.STATE_READY && player.currentPosition > 0L) playbackPrefs.edit().putLong(savedPositionKey, player.currentPosition).apply()
            if (!isScrubbing) positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L && it != com.google.android.exoplayer2.C.TIME_UNSET } ?: 0L
            isPlaying = player.isPlaying
            buffering = player.playbackState == com.google.android.exoplayer2.Player.STATE_BUFFERING
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

    LaunchedEffect(showControls, speedMenu, speedDialog, isScrubbing, fullScreen) {
        if (showControls && !speedMenu && !speedDialog && !isScrubbing && !controlsLocked) {
            delay(4500)
            showControls = false
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 4f)
        player.setPlaybackSpeed(playbackSpeed)
        speedMenu = false
    }

    @Composable
    fun SpeedPicker() {
        Box {
            TextButton(onClick = { speedMenu = !speedMenu }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text(if (playbackSpeed == 1f) "1×" else "${playbackSpeed}×", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            if (speedMenu) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = 38.dp).width(104.dp),
                    color = Color(0xF0202020), shape = RoundedCornerShape(10.dp), shadowElevation = 8.dp
                ) {
                    Column(Modifier.heightIn(max = 260.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f).forEach { speed ->
                            Text(
                                text = (if (speed == playbackSpeed) "✓  " else "    ") + "${speed}×",
                                color = Color.White, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth().clickable { setSpeed(speed) }.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                        Text("Custom…", color = Color(0xFFC5B9FF), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                speedText = playbackSpeed.toString()
                                speedMenu = false
                                speedDialog = true
                            }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun SeekBar(compact: Boolean = false) {
        Canvas(
            Modifier.fillMaxWidth().height(if (compact) 22.dp else 26.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { point ->
                        if (durationMs > 0L) {
                            positionMs = ((point.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()
                            player.seekTo(positionMs)
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { isScrubbing = true },
                        onDragEnd = { player.seekTo(positionMs); isScrubbing = false },
                        onDragCancel = { isScrubbing = false },
                        onDrag = { change, _ ->
                            if (durationMs > 0L) {
                                positionMs = ((change.position.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()
                                change.consume()
                            }
                        }
                    )
                }
        ) {
            val centerY = size.height / 2f
            val fraction = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs).toFloat() / durationMs else 0f
            val endX = size.width * fraction
            drawLine(Color.White.copy(alpha = 0.42f), Offset(0f, centerY), Offset(size.width, centerY), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color(0xFFFF0033), Offset(0f, centerY), Offset(endX, centerY), 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFFFF0033), 5.dp.toPx(), Offset(endX.coerceIn(5.dp.toPx(), (size.width - 5.dp.toPx()).coerceAtLeast(5.dp.toPx())), centerY))
        }
    }

    @Composable
    fun PlayerOverlay(landscape: Boolean) {
        val iconSize = if (landscape) 28.dp else 23.dp
        Column(
            Modifier.fillMaxSize()
                .background(Color(0x66000000))
                .padding(horizontal = if (landscape) 24.dp else 10.dp, vertical = if (landscape) 14.dp else 7.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (landscape) fullScreen = false else onBack() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Text(title, color = Color.White, maxLines = 1, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (landscape) {
                    IconButton(onClick = onDownload, enabled = !isDownloaded && !isDownloading, modifier = Modifier.size(38.dp)) {
                        when {
                            isDownloading -> Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                                Text("$downloadPercent%", color = Color.White, fontSize = 9.sp)
                            }
                            isDownloaded -> Icon(Icons.Default.CheckCircle, "Downloaded", tint = Color(0xFF38C879))
                            else -> Icon(Icons.Default.Download, "Download", tint = Color(0xFFB8BEC9))
                        }
                    }
                }
                SpeedPicker()
                IconButton(onClick = {
                    controlsLocked = true
                    showControls = false
                }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Lock, "Lock controls", tint = Color.White)
                }
                if (landscape) {
                    IconButton(onClick = { fullScreen = false }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White)
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(iconSize))
                    }
                    Spacer(Modifier.width(22.dp))
                    FilledIconButton(
                        onClick = {
                            if (player.isPlaying) player.pause()
                            else if (player.playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) { player.seekTo(0L); player.play() }
                            else player.play()
                        },
                        modifier = Modifier.size(50.dp), 
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(22.dp))
                    IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)) }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(iconSize))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(iconSize))
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(
                        onClick = {
                            if (player.isPlaying) player.pause()
                            else if (player.playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) { player.seekTo(0L); player.play() }
                            else player.play()
                        },
                        modifier = Modifier.size(42.dp), 
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(iconSize))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SeekBar(compact = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text("/", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { volumePanel = !volumePanel }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            when { volume == 0 -> Icons.Default.VolumeMute; volume < maxVolume / 2 -> Icons.Default.VolumeDown; else -> Icons.Default.VolumeUp },
                            "Mute or restore volume", tint = Color.White, modifier = Modifier.size(19.dp)
                        )
                    }
                    if (volumePanel) {
                        Slider(
                            value = volume.toFloat(),
                            onValueChange = {
                                volume = it.toInt().coerceIn(0, maxVolume)
                                if (volume > 0) previousVolume = volume
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                            },
                            valueRange = 0f..maxVolume.toFloat(),
                            modifier = Modifier.width(104.dp).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }

    if (speedDialog) {
        AlertDialog(
            onDismissRequest = { speedDialog = false },
            title = { Text("Custom playback speed") },
            text = {
                OutlinedTextField(
                    value = speedText,
                    onValueChange = { speedText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                    singleLine = true,
                    label = { Text("0.25× to 4×") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    speedText.toFloatOrNull()?.takeIf { it in 0.25f..4f }?.let { setSpeed(it); speedDialog = false }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { speedDialog = false }) { Text("Cancel") } }
        )
    }

    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(player, controlsLocked) {
            detectTapGestures(
                onTap = { if (controlsLocked) { controlsLocked = false; showControls = true } else showControls = !showControls },
                onDoubleTap = { offset ->
                    if (!controlsLocked) {
                        val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                        val end = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + delta).coerceIn(0L, end))
                        showControls = true
                    }
                }
            )
        }) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); keepScreenOn = true } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            if (buffering && playerError.isBlank()) {
                Column(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = Color.White)
                }
            }
            if (controlsLocked) {
                Text("Tap to unlock", color = Color.White, modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 9.dp))
            } else if (showControls) PlayerOverlay(landscape = true)
            if (playerError.isNotBlank()) {
                TextButton(onClick = { playerError = ""; player.prepare(); player.play() }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("Retry", color = Color.White)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowBack, "Back to lessons") }
                Text("NOW PLAYING", color = Color(0xFF9B8CFF), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { fullScreen = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .pointerInput(player, controlsLocked) {
                        detectTapGestures(
                            onTap = { if (controlsLocked) { controlsLocked = false; showControls = true } else showControls = !showControls },
                            onDoubleTap = { offset ->
                                if (!controlsLocked) {
                                    val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                                    val end = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    player.seekTo((player.currentPosition + delta).coerceIn(0L, end))
                                    showControls = true
                                }
                            }
                        )
                    }
            ) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); keepScreenOn = true } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            if (buffering && playerError.isBlank()) {
                Column(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = Color.White)
                }
            }
                if (controlsLocked) {
                    Text("Tap to unlock", color = Color.White, modifier = Modifier.align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                } else if (showControls) PlayerOverlay(landscape = false)
                if (playerError.isNotBlank()) {
                    TextButton(onClick = { playerError = ""; player.prepare(); player.play() }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Text("Retry", color = Color.White)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDownload, enabled = !isDownloaded && !isDownloading, modifier = Modifier.size(36.dp)) {
                    when {
                        isDownloading -> Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp, color = Color(0xFFB5A9FF))
                            Text("$downloadPercent%", fontSize = 9.sp, color = Color.White)
                        }
                        isDownloaded -> Icon(Icons.Default.CheckCircle, "Downloaded", tint = Color(0xFF38C879), modifier = Modifier.size(23.dp))
                        else -> Icon(Icons.Default.Download, "Download", tint = Color(0xFF858B98), modifier = Modifier.size(23.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPrevious, enabled = hasPrevious) { Icon(Icons.Default.SkipPrevious, null); Text("Previous") }
                TextButton(onClick = onNext, enabled = hasNext) { Text("Next"); Icon(Icons.Default.SkipNext, null) }
            }
            if (playerError.isNotBlank()) Text(playerError, color = Color(0xFFFF8A8A), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return String.format(java.util.Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
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
private fun openLocalDocument(context: Context, uri: Uri) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "No PDF viewer is installed", Toast.LENGTH_SHORT).show()
    }
}

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
