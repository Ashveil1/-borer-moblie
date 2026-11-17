package com.example.mcp

import android.content.Context
import com.example.data.BorerSettings
import com.example.data.McpServerConfig
import com.example.data.McpTransport
import com.example.data.ShellBackend
import com.example.runtime.UbuntuProotRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface McpTransportSession {
    suspend fun connect(timeoutMs: Long)
    suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject
    suspend fun notify(method: String, params: JsonObject?)
    fun stderrTail(): List<String> = emptyList()
    fun close()
}

private val mcpJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private fun buildJsonRpcRequest(id: Int, method: String, params: JsonObject?): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("method", method)
    if (params != null) put("params", params)
}

private fun buildJsonRpcNotification(method: String, params: JsonObject?): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", method)
    if (params != null) put("params", params)
}

private fun buildJsonRpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun buildJsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}

private fun unwrapJsonRpcResponse(msg: JsonObject): JsonObject {
    val errObj = msg["error"] as? JsonObject
    if (errObj != null) {
        val code = (errObj["code"] as? JsonPrimitive)?.intOrNull ?: -32000
        val message = (errObj["message"] as? JsonPrimitive)?.content ?: "Unknown MCP error"
        val data = errObj["data"]?.toString()
        throw McpRpcException(code, message, data)
    }
    return (msg["result"] as? JsonObject) ?: JsonObject(emptyMap())
}

private fun buildInitializeParams(protocolVersion: String = McpProtocol.LATEST_VERSION): JsonObject = buildJsonObject {
    put("protocolVersion", protocolVersion)
    put("capabilities", buildJsonObject {
        put("roots", buildJsonObject { put("listChanged", false) })
    })
    put("clientInfo", buildJsonObject {
        put("name", "borer")
        put("version", "0.1.0")
    })
}

private fun shellQuoteSingle(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

fun createMcpTransport(
    context: Context,
    config: McpServerConfig,
    settings: BorerSettings,
    workdir: File
): McpTransportSession {
    val resolvedTransport = when (config.transport) {
        McpTransport.AUTO -> when {
            config.url.isNotBlank() && config.url.trim().substringBefore('?').endsWith("/sse", ignoreCase = true) ->
                McpTransport.SSE
            config.url.isNotBlank() -> McpTransport.AUTO
            config.command.isNotBlank() -> McpTransport.STDIO
            else -> McpTransport.AUTO
        }
        else -> config.transport
    }

    return when (resolvedTransport) {
        McpTransport.STDIO -> {
            if (settings.shellBackend == ShellBackend.TERMUX_PROOT) {
                TermuxBatchStdioMcpTransport(context, config, settings, workdir)
            } else {
                StdioMcpTransport(context, config, settings, workdir)
            }
        }
        McpTransport.STREAMABLE_HTTP -> StreamableHttpMcpTransport(config)
        McpTransport.SSE -> SseMcpTransport(config)
        McpTransport.AUTO -> {
            if (config.url.isNotBlank()) AutoHttpMcpTransport(config)
            else if (settings.shellBackend == ShellBackend.TERMUX_PROOT) TermuxBatchStdioMcpTransport(context, config, settings, workdir)
            else StdioMcpTransport(context, config, settings, workdir)
        }
    }
}

/**
 * Persistent stdio JSON-RPC 2.0 MCP client over ProcessBuilder (or Direct proot).
 */
class StdioMcpTransport(
    private val context: Context,
    private val config: McpServerConfig,
    private val settings: BorerSettings,
    private val workdir: File
) : McpTransportSession {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val stderrLines = ArrayDeque<String>()
    private val stderrLock = Any()
    private val writeLock = Any()

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var stdoutThread: Thread? = null
    @Volatile
    private var stderrThread: Thread? = null
    @Volatile
    private var closed = false

    override suspend fun connect(timeoutMs: Long) = withContext(Dispatchers.IO) {
        if (config.command.isBlank()) {
            throw McpRpcException(0, "Stdio command is blank for MCP server '${config.name}'")
        }
        closed = false
        val rawArgs = buildList {
            add(config.command.trim())
            addAll(config.args)
        }
        val envMap = linkedMapOf<String, String>().apply {
            put("BORER_WORKDIR", workdir.absolutePath)
            putAll(config.env)
        }

        val pb = if (settings.shellBackend == ShellBackend.PROOT_DIRECT) {
            val fullCmd = rawArgs.joinToString(" ") { shellQuoteSingle(it) }
            UbuntuProotRuntime.buildProcessBuilder(context, fullCmd, workdir, envMap, settings).getOrElse { err ->
                throw McpRpcException(0, "Failed to prepare Direct proot command: ${err.message}")
            }
        } else {
            ProcessBuilder(rawArgs)
                .directory(workdir)
                .apply {
                    envMap.forEach { (k, v) -> environment()[k] = v }
                }
        }

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            throw McpRpcException(0, "Unable to spawn '${config.command}': ${t.message}")
        }
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        stdoutThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    while (!closed && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        handleIncomingLine(trimmed)
                    }
                }
            }
            failAllPending("MCP stdio process closed (${stderrTail().lastOrNull() ?: "EOF"})")
        }.apply {
            isDaemon = true
            name = "mcp-stdout-${config.id}"
            start()
        }

        stderrThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { reader ->
                    while (!closed && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        synchronized(stderrLock) {
                            if (stderrLines.size >= 40) stderrLines.removeFirst()
                            stderrLines.addLast(line)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "mcp-stderr-${config.id}"
            start()
        }
    }

    private fun handleIncomingLine(line: String) {
        val obj = runCatching { mcpJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val idElem = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.content
        if (idElem != null && method != null) {
            // Server-initiated request (e.g. ping / roots/list)
            val reply = when (method) {
                "ping" -> buildJsonRpcResult(idElem, JsonObject(emptyMap()))
                "roots/list" -> buildJsonRpcResult(idElem, buildJsonObject {
                    put("roots", buildJsonArray {
                        add(buildJsonObject {
                            put("uri", workdir.toURI().toString())
                            put("name", "workspace")
                        })
                    })
                })
                else -> buildJsonRpcError(idElem, -32601, "Method not implemented by client: $method")
            }
            runCatching { writeLine(reply.toString()) }
            return
        }
        if (idElem != null) {
            val idKey = (idElem as? JsonPrimitive)?.content ?: idElem.toString()
            pending.remove(idKey)?.complete(obj)
        }
    }

    private fun failAllPending(reason: String) {
        val keys = pending.keys.toList()
        for (k in keys) {
            pending.remove(k)?.completeExceptionally(McpRpcException(0, reason))
        }
    }

    private fun writeLine(jsonLine: String) {
        val w = writer ?: throw McpRpcException(0, "Stdio writer is not open")
        synchronized(writeLock) {
            w.write(jsonLine)
            w.newLine()
            w.flush()
        }
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<JsonObject>()
            pending[id.toString()] = deferred
            try {
                val payload = buildJsonRpcRequest(id, method, params).toString()
                writeLine(payload)
                val msg = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { deferred.await() }
                    ?: throw McpRpcException(
                        0,
                        "Timeout waiting for MCP stdio response to '$method' (${stderrTail().lastOrNull() ?: "${timeoutMs}ms"})"
                    )
                unwrapJsonRpcResponse(msg)
            } finally {
                pending.remove(id.toString())
            }
        }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        writeLine(buildJsonRpcNotification(method, params).toString())
    }

    override fun stderrTail(): List<String> = synchronized(stderrLock) { stderrLines.toList() }

    override fun close() {
        closed = true
        failAllPending("MCP stdio session closed")
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        stdoutThread?.interrupt()
        stderrThread?.interrupt()
        writer = null
        process = null
    }
}

/**
 * Stdio MCP bridge for Termux proot-distro Ubuntu (where Android apps dispatch commands
 * via Termux RunCommandService rather than interactive ProcessBuilder pipes).
 * Replays initialize + notifications/initialized + target RPC calls over stdin per batch.
 */
class TermuxBatchStdioMcpTransport(
    private val context: Context,
    private val config: McpServerConfig,
    private val settings: BorerSettings,
    private val workdir: File
) : McpTransportSession {
    private val nextId = AtomicInteger(1)
    private val lastStderr = mutableListOf<String>()

    override suspend fun connect(timeoutMs: Long) {
        if (config.command.isBlank()) {
            throw McpRpcException(0, "Stdio command is blank for MCP server '${config.name}'")
        }
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            val reqId = nextId.getAndIncrement()
            val stdinScript = buildString {
                if (method != "initialize") {
                    appendLine(buildJsonRpcRequest(0, "initialize", buildInitializeParams()).toString())
                    appendLine(buildJsonRpcNotification("notifications/initialized", null).toString())
                }
                appendLine(buildJsonRpcRequest(reqId, method, params).toString())
            }
            val cmdParts = buildList {
                add(config.command.trim())
                addAll(config.args)
            }
            val shellCmd = cmdParts.joinToString(" ") { shellQuoteSingle(it) }
            val env = linkedMapOf<String, String>().apply {
                put("BORER_WORKDIR", workdir.absolutePath)
                putAll(config.env)
            }
            val exec = UbuntuProotRuntime.exec(
                context = context,
                command = shellCmd,
                cwd = workdir,
                env = env,
                timeoutMs = timeoutMs.coerceIn(3_000L, 120_000L),
                stdin = stdinScript,
                settings = settings
            )
            val lines = exec.output.lines()
            var targetResp: JsonObject? = null
            val diag = mutableListOf<String>()
            for (raw in lines) {
                val t = raw.trim()
                if (t.isEmpty()) continue
                val parsed = runCatching { mcpJson.parseToJsonElement(t).jsonObject }.getOrNull()
                if (parsed != null && parsed["id"]?.jsonPrimitive?.content == reqId.toString()) {
                    targetResp = parsed
                } else if (parsed == null) {
                    diag.add(t)
                }
            }
            synchronized(lastStderr) {
                lastStderr.clear()
                lastStderr.addAll(diag.takeLast(30))
            }
            val resp = targetResp ?: throw McpRpcException(
                0,
                "Termux MCP '$method' returned no JSON-RPC response (exit=${exec.exitCode}): ${diag.lastOrNull() ?: exec.output.take(240)}"
            )
            unwrapJsonRpcResponse(resp)
        }

    override suspend fun notify(method: String, params: JsonObject?) {
        // Handled inline in each Termux stdio batch invocation.
    }

    override fun stderrTail(): List<String> = synchronized(lastStderr) { lastStderr.toList() }

    override fun close() = Unit
}

/**
 * MCP Streamable HTTP transport (protocol 2025-03-26+).
 * Supports both JSON responses and text/event-stream responses on POST, plus Mcp-Session-Id.
 */
class StreamableHttpMcpTransport(
    private val config: McpServerConfig
) : McpTransportSession {
    private val nextId = AtomicInteger(1)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(config.timeoutMs.coerceIn(5_000L, 180_000L), TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var closed = false

    override suspend fun connect(timeoutMs: Long) {
        if (config.url.isBlank()) {
            throw McpRpcException(0, "HTTP URL is required for MCP server '${config.name}'")
        }
        closed = false
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            val id = nextId.getAndIncrement()
            val payload = buildJsonRpcRequest(id, method, params).toString()
            val req = buildPostRequest(payload)
            client.newCall(req).execute().use { res ->
                res.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                val cType = res.header("Content-Type")?.lowercase().orEmpty()
                if (!res.isSuccessful) {
                    val errBody = res.body?.string().orEmpty().take(300)
                    throw McpRpcException(res.code, "HTTP ${res.code} ${res.message}: $errBody")
                }
                if (cType.contains("text/event-stream")) {
                    val bodyStream = res.body?.byteStream()
                        ?: throw McpRpcException(0, "Empty SSE stream from ${config.url}")
                    val parsed = parseSseForRequestId(BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8)), id.toString())
                        ?: throw McpRpcException(0, "No matching JSON-RPC response in SSE stream for '$method'")
                    return@use unwrapJsonRpcResponse(parsed)
                }
                val raw = res.body?.string().orEmpty()
                if (raw.isBlank()) return@use JsonObject(emptyMap())
                val obj = runCatching { mcpJson.parseToJsonElement(raw).jsonObject }.getOrElse {
                    throw McpRpcException(0, "Invalid JSON from MCP HTTP server: ${raw.take(240)}")
                }
                unwrapJsonRpcResponse(obj)
            }
        }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        val payload = buildJsonRpcNotification(method, params).toString()
        runCatching {
            client.newCall(buildPostRequest(payload)).execute().use { res ->
                res.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            }
        }
        Unit
    }

    private fun buildPostRequest(payload: String): Request {
        val builder = Request.Builder()
            .url(config.url.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        config.headers.forEach { (k, v) ->
            if (k.isNotBlank()) builder.header(k.trim(), v)
        }
        return builder.build()
    }

    override fun close() {
        if (closed) return
        closed = true
        val sid = sessionId
        if (!sid.isNullOrBlank() && config.url.isNotBlank()) {
            runCatching {
                val req = Request.Builder()
                    .url(config.url.trim())
                    .header("Mcp-Session-Id", sid)
                    .apply {
                        config.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k.trim(), v) }
                    }
                    .delete()
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }
}

/**
 * Legacy MCP HTTP+SSE transport (protocol 2024-11-05).
 * Opens GET SSE stream, awaits 'endpoint' event, posts JSON-RPC calls, and matches 'message' events.
 */
class SseMcpTransport(
    private val config: McpServerConfig
) : McpTransportSession {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val endpointReady = CompletableDeferred<String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Long-lived SSE stream
        .build()

    @Volatile
    private var sseCall: okhttp3.Call? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var closed = false
    @Volatile
    private var postEndpoint: String? = null

    override suspend fun connect(timeoutMs: Long) = withContext(Dispatchers.IO) {
        if (config.url.isBlank()) {
            throw McpRpcException(0, "SSE URL is required for MCP server '${config.name}'")
        }
        closed = false
        val sseUrl = config.url.trim()
        val req = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .apply {
                config.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k.trim(), v) }
            }
            .get()
            .build()

        val call = client.newCall(req)
        sseCall = call
        val response: Response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            val body = response.body?.string().orEmpty().take(240)
            response.close()
            throw McpRpcException(code, "SSE connect failed ($code): $body")
        }

        val stream = response.body?.byteStream()
        if (stream == null) {
            response.close()
            throw McpRpcException(0, "Empty SSE body from $sseUrl")
        }

        readerThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var eventType = "message"
                    val dataBuf = StringBuilder()
                    fun flushEvent() {
                        val data = dataBuf.toString().trim()
                        dataBuf.clear()
                        if (data.isEmpty()) {
                            eventType = "message"
                            return
                        }
                        when (eventType) {
                            "endpoint" -> {
                                val resolved = sseUrl.toHttpUrlOrNull()?.resolve(data)?.toString() ?: data
                                postEndpoint = resolved
                                endpointReady.complete(resolved)
                            }
                            else -> {
                                val obj = runCatching { mcpJson.parseToJsonElement(data).jsonObject }.getOrNull()
                                val idStr = obj?.get("id")?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
                                if (idStr != null && obj != null) {
                                    pending.remove(idStr)?.complete(obj)
                                }
                            }
                        }
                        eventType = "message"
                    }

                    while (!closed && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> flushEvent()
                            line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                dataBuf.append(line.removePrefix("data:").trimStart())
                            }
                        }
                    }
                }
            }.onFailure { err ->
                if (!endpointReady.isCompleted) endpointReady.completeExceptionally(err)
            }
            response.close()
            if (!endpointReady.isCompleted) {
                endpointReady.completeExceptionally(McpRpcException(0, "SSE stream closed before receiving endpoint event"))
            }
            pending.keys.toList().forEach { k ->
                pending.remove(k)?.completeExceptionally(McpRpcException(0, "SSE stream closed"))
            }
        }.apply {
            isDaemon = true
            name = "mcp-sse-${config.id}"
            start()
        }

        val resolved = withTimeoutOrNull(timeoutMs.coerceAtLeast(3_000L)) { endpointReady.await() }
        if (resolved.isNullOrBlank()) {
            close()
            throw McpRpcException(0, "Timed out waiting for SSE 'endpoint' event from $sseUrl")
        }
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            val ep = postEndpoint ?: throw McpRpcException(0, "SSE endpoint not initialized")
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<JsonObject>()
            pending[id.toString()] = deferred
            try {
                val payload = buildJsonRpcRequest(id, method, params).toString()
                val req = Request.Builder()
                    .url(ep)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .apply {
                        config.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k.trim(), v) }
                    }
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        throw McpRpcException(res.code, "SSE POST HTTP ${res.code}: ${res.body?.string().orEmpty().take(240)}")
                    }
                    val body = res.body?.string().orEmpty().trim()
                    if (body.startsWith("{")) {
                        val inlineObj = runCatching { mcpJson.parseToJsonElement(body).jsonObject }.getOrNull()
                        if (inlineObj != null && (inlineObj.containsKey("result") || inlineObj.containsKey("error"))) {
                            return@use unwrapJsonRpcResponse(inlineObj)
                        }
                    }
                }
                val msg = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { deferred.await() }
                    ?: throw McpRpcException(0, "Timeout waiting for SSE message response to '$method'")
                unwrapJsonRpcResponse(msg)
            } finally {
                pending.remove(id.toString())
            }
        }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        val ep = postEndpoint ?: return@withContext
        val payload = buildJsonRpcNotification(method, params).toString()
        runCatching {
            val req = Request.Builder()
                .url(ep)
                .header("Content-Type", "application/json")
                .apply { config.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k.trim(), v) } }
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().close()
        }
        Unit
    }

    override fun close() {
        closed = true
        runCatching { sseCall?.cancel() }
        readerThread?.interrupt()
        pending.keys.toList().forEach { k ->
            pending.remove(k)?.completeExceptionally(McpRpcException(0, "SSE transport closed"))
        }
    }
}

/**
 * Negotiates Streamable HTTP (2025-03-26) first, falling back to legacy HTTP+SSE (2024-11-05)
 * if the server responds 404/405/406 or does not speak Streamable HTTP on POST.
 */
class AutoHttpMcpTransport(
    private val config: McpServerConfig
) : McpTransportSession {
    @Volatile
    private var delegate: McpTransportSession? = null

    override suspend fun connect(timeoutMs: Long) {
        val urlPath = config.url.trim().substringBefore('?')
        if (urlPath.endsWith("/sse", ignoreCase = true)) {
            val sse = SseMcpTransport(config)
            try {
                sse.connect(timeoutMs)
                delegate = sse
                return
            } catch (_: Throwable) {
                sse.close()
            }
        }
        delegate = StreamableHttpMcpTransport(config).also { it.connect(timeoutMs) }
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject {
        val active = delegate ?: throw McpRpcException(0, "Transport not connected")
        return try {
            active.request(method, params, timeoutMs)
        } catch (err: McpRpcException) {
            if (method == "initialize" && active is StreamableHttpMcpTransport && err.code in setOf(404, 405, 406, 501)) {
                active.close()
                val sse = SseMcpTransport(config)
                sse.connect(timeoutMs)
                delegate = sse
                sse.request(method, params, timeoutMs)
            } else {
                throw err
            }
        }
    }

    override suspend fun notify(method: String, params: JsonObject?) {
        delegate?.notify(method, params)
    }

    override fun close() {
        delegate?.close()
        delegate = null
    }
}

private fun parseSseForRequestId(reader: BufferedReader, expectedId: String): JsonObject? {
    val dataBuf = StringBuilder()
    var matched: JsonObject? = null
    reader.use { r ->
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) {
                val data = dataBuf.toString().trim()
                dataBuf.clear()
                if (data.startsWith("{")) {
                    val obj = runCatching { mcpJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    val idStr = obj?.get("id")?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
                    if (obj != null && (idStr == expectedId || obj.containsKey("result") || obj.containsKey("error"))) {
                        matched = obj
                        break
                    }
                }
            } else if (line.startsWith("data:")) {
                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                dataBuf.append(line.removePrefix("data:").trimStart())
            }
        }
        if (matched == null && dataBuf.isNotEmpty()) {
            val data = dataBuf.toString().trim()
            if (data.startsWith("{")) {
                matched = runCatching { mcpJson.parseToJsonElement(data).jsonObject }.getOrNull()
            }
        }
    }
    return matched
}
