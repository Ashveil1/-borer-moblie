package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@Serializable
data class EventRecord(
    val seq: Int,
    val ts: String,
    val kind: String,
    val data: Map<String, String>
)

@Serializable
data class HttpLogRecord(
    val ts: String,
    val method: String,
    val url: String,
    val status: Int,
    val bytes: Int,
    val ms: Long,
    val aggression: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap()
)

class EventLog(val dir: File) {
    init {
        dir.mkdirs()
    }

    val eventsFile = File(dir, "events.jsonl")
    val httpFile = File(dir, "http.jsonl")

    private val seqCounter = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableStateFlow<List<EventRecord>>(loadExistingEvents())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    private val _httpLogs = MutableStateFlow<List<HttpLogRecord>>(loadExistingHttpLogs())
    val httpLogs: StateFlow<List<HttpLogRecord>> = _httpLogs.asStateFlow()

    private val _allowedCount = MutableStateFlow(_httpLogs.value.size)
    val allowedCount: StateFlow<Int> = _allowedCount.asStateFlow()

    private val _deniedCount = MutableStateFlow(0)
    val deniedCount: StateFlow<Int> = _deniedCount.asStateFlow()

    @Synchronized
    fun append(kind: String, data: Map<String, Any?> = emptyMap()): EventRecord {
        val stringData = data.mapValues { (_, v) -> v?.toString().orEmpty() }
        val record = EventRecord(
            seq = seqCounter.incrementAndGet(),
            ts = Instant.now().toString(),
            kind = kind,
            data = stringData
        )
        runCatching {
            eventsFile.appendText(json.encodeToString(record) + "\n")
        }
        if (kind == "tool.denied" && (stringData["tool"] == "http" || stringData["tool"] == "net")) {
            _deniedCount.value = _deniedCount.value + 1
        }
        _events.value = (_events.value + record).takeLast(500)
        return record
    }

    @Synchronized
    fun appendHttp(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        status: Int,
        responseHeaders: Map<String, String>,
        bytes: Int,
        ms: Long,
        aggression: String
    ) {
        val record = HttpLogRecord(
            ts = Instant.now().toString(),
            method = method,
            url = url,
            status = status,
            bytes = bytes,
            ms = ms,
            aggression = aggression,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders
        )
        runCatching {
            httpFile.appendText(json.encodeToString(record) + "\n")
        }
        _allowedCount.value = _allowedCount.value + 1
        _httpLogs.value = (_httpLogs.value + record).takeLast(400)
    }

    @Synchronized
    fun artifactMeta(id: String, kind: String, label: String, bytes: Long, lines: Int) {
        val artifactsDir = File(dir, "artifacts").apply { mkdirs() }
        val obj: JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("ts", JsonPrimitive(Instant.now().toString()))
            put("kind", JsonPrimitive(kind))
            put("label", JsonPrimitive(label))
            put("bytes", JsonPrimitive(bytes))
            put("lines", JsonPrimitive(lines))
        }
        runCatching {
            File(artifactsDir, "index.jsonl").appendText(obj.toString() + "\n")
        }
    }

    private fun loadExistingEvents(): List<EventRecord> {
        if (!eventsFile.exists()) return emptyList()
        return runCatching {
            readTailLines(eventsFile, maxBytes = 512L * 1024L, maxLines = 300)
                .mapNotNull { line -> runCatching { json.decodeFromString<EventRecord>(line) }.getOrNull() }
                .also { list -> seqCounter.set(list.maxOfOrNull { it.seq } ?: 0) }
        }.getOrDefault(emptyList())
    }

    private fun loadExistingHttpLogs(): List<HttpLogRecord> {
        if (!httpFile.exists()) return emptyList()
        return runCatching {
            readTailLines(httpFile, maxBytes = 512L * 1024L, maxLines = 300)
                .mapNotNull { line -> runCatching { json.decodeFromString<HttpLogRecord>(line) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun readTailLines(file: File, maxBytes: Long, maxLines: Int): List<String> {
        if (!file.exists() || !file.isFile) return emptyList()
        val length = file.length()
        if (length <= 0L) return emptyList()
        val start = (length - maxBytes).coerceAtLeast(0L)
        val raw = RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val toRead = (length - start).toInt().coerceAtLeast(0)
            val buf = ByteArray(toRead)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        val allLines = raw.lineSequence()
            .let { seq -> if (start > 0L) seq.drop(1) else seq }
            .filter { it.isNotBlank() }
            .toList()
        return if (allLines.size <= maxLines) allLines else allLines.takeLast(maxLines)
    }

    companion object {
        private val runFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)

        fun newRunId(): String {
            val stamp = runFmt.format(Instant.now())
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val rand = (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            return "$stamp-$rand"
        }
    }
}
