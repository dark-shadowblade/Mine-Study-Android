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
    suspend fun get(path: String, keyOverride: String? = null, unwrapData: Boolean = true): Any? = withContext(Dispatchers.IO) {
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
                    return@withContext if (unwrapData) payload.opt("data")?.takeUnless { it == JSONObject.NULL } ?: payload else payload
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

    suspend fun activateKey(candidate: String) {
        if (candidate.isBlank()) throw IllegalStateException("Enter an API key first")
        // Activation is a first-party server operation. Do not send an existing
        // x-api-key header; the candidate is supplied only as the encoded query parameter.
        val response = get("/auth/activate?key=${enc(candidate)}", "", unwrapData = false) as? JSONObject
            ?: throw IllegalStateException("The activation server returned an invalid response")
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(response.optString("message", "Failed to activate API key"))
        }
    }

    suspend fun verifyKey(candidate: String): Int {
        if (candidate.isBlank()) throw IllegalStateException("Enter an API key first")
        activateKey(candidate)
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

    // Pasting a key triggers server activation and verification; save only after both succeed.
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
                        onValueChange = { value -> apiKey = value; api.batchId = batch },
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