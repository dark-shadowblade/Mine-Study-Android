package com.minstudy.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * Parallel, retrying downloader for the CDN's ?s=start&e=end range protocol.
 * Each worker owns a disjoint file range; short CDN responses are continued
 * from the actual returned Content-Range end rather than leaving gaps.
 */
object ParallelCdnDownloader {
    private const val CHUNK = 1024 * 1024
    private const val WORKERS = 4
    private data class Part(val start: Long, val end: Long, val total: Long, val bytes: ByteArray)

    suspend fun download(
        source: String,
        target: java.io.File,
        control: DownloadControl,
        progress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = Uri.parse(source)
        if (!base.scheme.equals("https", true) ||
            !Regex("a\\d+\\.stmvv1\\.xyz", RegexOption.IGNORE_CASE).matches(base.host ?: "")) {
            throw IOException("Invalid video CDN host")
        }
        val first = fetch(base, 0L, CHUNK - 1L)
        val total = first.total
        if (total <= 0L) throw IOException("CDN returned an invalid video size")
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(total)
            val written = AtomicLong(0L)
            fun writePart(part: Part) {
                synchronized(file) {
                    file.seek(part.start)
                    file.write(part.bytes)
                }
                written.addAndGet(part.bytes.size.toLong())
                progress(written.get(), total)
            }
            var firstCursor = 0L
            var firstPart = first
            while (firstCursor < minOf(CHUNK.toLong(), total)) {
                if (firstPart.start != firstCursor) throw IOException("CDN returned a non-contiguous initial range")
                writePart(firstPart)
                firstCursor = firstPart.end + 1L
                if (firstCursor < minOf(CHUNK.toLong(), total)) {
                    firstPart = retryingFetch(base, firstCursor, minOf(CHUNK.toLong() - 1L, total - 1L), total)
                }
            }

            val next = AtomicInteger(CHUNK)
            coroutineScope {
                (0 until WORKERS).map {
                    async(Dispatchers.IO) {
                        while (true) {
                            control.checkpoint()
                            val start = next.getAndAdd(CHUNK).toLong()
                            if (start >= total) break
                            val blockEnd = minOf(start + CHUNK - 1L, total - 1L)
                            var cursor = start
                            while (cursor <= blockEnd) {
                                control.checkpoint()
                                val part = retryingFetch(base, cursor, blockEnd, total)
                                if (part.bytes.isEmpty()) throw IOException("CDN returned an empty range")
                                writePart(part)
                                cursor = part.end + 1L
                            }
                        }
                    }
                }.awaitAll()
            }
            if (written.get() != total) throw IOException("Download incomplete: received ${written.get()} of $total bytes")
        }
    }

    private suspend fun retryingFetch(base: Uri, start: Long, end: Long, total: Long): Part {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return fetch(base, start, end).also {
                    if (it.total != total || it.start != start || it.end > end) {
                        throw IOException("CDN returned an inconsistent byte range")
                    }
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(350L * (attempt + 1))
            }
        }
        throw IOException("Range request failed after 4 attempts: ${last?.message}", last)
    }

    private suspend fun fetch(base: Uri, start: Long, end: Long): Part = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(4) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                val builder = base.buildUpon().clearQuery()
                for (name in base.queryParameterNames) {
                    if (name != "s" && name != "e") {
                        base.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
                    }
                }
                builder.appendQueryParameter("s", start.toString())
                builder.appendQueryParameter("e", end.toString())
                connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 45000
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "ExoPlayer")
                }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_PARTIAL) {
                    val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(200) } }.getOrNull()
                    throw IOException("CDN HTTP $status: ${detail ?: "unexpected response"}")
                }
                val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                    .find(connection.getHeaderField("Content-Range") ?: "")
                    ?: throw IOException("CDN returned invalid Content-Range")
                val actualStart = match.groupValues[1].toLong()
                val actualEnd = match.groupValues[2].toLong()
                val actualTotal = match.groupValues[3].toLong()
                if (actualStart != start || actualEnd < start || actualEnd > end || actualEnd >= actualTotal) {
                    throw IOException("CDN returned an unexpected byte range")
                }
                val output = ByteArrayOutputStream((actualEnd - start + 1L).toInt())
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.size.toLong() != actualEnd - start + 1L) throw IOException("CDN range was truncated")
                return@withContext Part(actualStart, actualEnd, actualTotal, bytes)
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(350L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException("CDN request failed after retries: ${last?.message}", last)
    }
}
