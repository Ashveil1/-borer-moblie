package com.example.tools

import com.example.mcp.McpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val MAX_HTTP_BODY = 4 * 1024 * 1024

private val trustAllManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private val baseOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

private val insecureSslSocketFactory by lazy {
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }.socketFactory
}

private fun buildOkHttp(timeoutMs: Long, verifyTls: Boolean, http2: Boolean): OkHttpClient {
    val builder = baseOkHttpClient.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .protocols(if (http2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1))

    if (!verifyTls) {
        builder.sslSocketFactory(insecureSslSocketFactory, trustAllManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

val httpTool = object : Tool {
    override val name = "http"
    override val description =
        "Full-control HTTP client. Any method, headers and body; HTTP/1.1 and HTTP/2; per-request TLS verification toggle; redirect control; you get status, headers, timings and the body (large bodies become artifacts). Every request is logged to http.jsonl."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "url" to schemaProp("string"),
            "method" to schemaProp("string", "default GET"),
            "headers" to schemaProp("object", "header map"),
            "body" to schemaProp("string", "raw request body"),
            "body_base64" to schemaProp("string", "binary body, base64 encoded"),
            "redirects" to schemaProp("number", "max redirects to follow, 0 = do not follow (default 0)"),
            "timeout_ms" to schemaProp("number", "default 30000"),
            "verify_tls" to schemaProp("boolean", "default true"),
            "http2" to schemaProp("boolean", "use HTTP/2 when the scheme is https"),
            "max_body" to schemaProp("number", "bytes to keep before truncating (default 4194304)"),
            "decompress" to schemaProp("boolean", "default true"),
            "extract_text" to schemaProp("boolean", "strip HTML/scripts into readable plain text when true (default false)")
        ),
        required = listOf("url")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        var url = args["url"].asStr().orEmpty()
        if (url.isBlank()) return@withContext ToolResult("error: url is required", isError = true)
        var method = (args["method"].asStr() ?: "GET").uppercase()
        val maxRedirects = (args["redirects"].asInt() ?: 0).coerceIn(0, 20)
        val timeoutMs = (args["timeout_ms"].asInt() ?: 30_000).toLong().coerceIn(500L, 120_000L)
        val verifyTls = args["verify_tls"].asBool() ?: true
        val http2 = args["http2"].asBool() ?: false
        val decompress = args["decompress"].asBool() ?: true
        val extractText = args["extract_text"].asBool() ?: false
        val maxBody = (args["max_body"].asInt() ?: MAX_HTTP_BODY).coerceIn(1, 32 * 1024 * 1024)

        val headersMap = linkedMapOf<String, String>()
        (args["headers"] as? JsonObject)?.forEach { (k, v) ->
            headersMap[k.lowercase()] = v.asStr().orEmpty()
        }

        val bodyB64 = args["body_base64"].asStr()
        val bodyStr = args["body"].asStr()
        var bodyBytes: ByteArray? = when {
            !bodyB64.isNullOrEmpty() -> runCatching { Base64.getDecoder().decode(bodyB64) }
                .getOrElse { return@withContext ToolResult("error: invalid body_base64", isError = true) }
            bodyStr != null -> bodyStr.toByteArray(Charsets.UTF_8)
            else -> null
        }

        val client = buildOkHttp(timeoutMs, verifyTls, http2)

        for (hop in 0..maxRedirects) {
            val decision = ctx.gate.check(url = url)
            if (!decision.ok) {
                ctx.log.append("tool.denied", mapOf("tool" to "http", "url" to url, "method" to method, "reason" to decision.reason))
                return@withContext ToolResult("refused by scope gate: ${decision.reason}", isError = true)
            }

            val started = System.currentTimeMillis()
            var ttfbMs = 0L
            val parsedUri = runCatching { URI(url) }.getOrNull()
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", headersMap["user-agent"] ?: "borer/0.1")
                .header("Accept", headersMap["accept"] ?: "*/*")
                .header("Accept-Encoding", if (decompress) "gzip, deflate" else "identity")

            headersMap.forEach { (k, v) -> reqBuilder.header(k, v) }

            val reqBody = when {
                bodyBytes != null -> bodyBytes.toRequestBody(headersMap["content-type"]?.toMediaTypeOrNull())
                method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            reqBuilder.method(method, reqBody)

            try {
                client.newCall(reqBuilder.build()).execute().use { res ->
                    ttfbMs = System.currentTimeMillis() - started
                    val rawStream = res.body?.byteStream()
                    val out = ByteArrayOutputStream()
                    var truncated = false
                    if (rawStream != null) {
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = rawStream.read(buf)
                            if (read <= 0) break
                            if (total + read > maxBody) {
                                val room = maxBody - total
                                if (room > 0) out.write(buf, 0, room)
                                truncated = true
                                break
                            }
                            out.write(buf, 0, read)
                            total += read
                        }
                    }
                    val totalMs = System.currentTimeMillis() - started
                    val rawBytes = out.toByteArray()
                    val encoding = res.header("content-encoding")?.lowercase().orEmpty()
                    val finalBytes = if (decompress && rawBytes.isNotEmpty()) {
                        decompressPartial(rawBytes, encoding, maxBody)
                    } else rawBytes

                    val respHeaders = linkedMapOf<String, String>()
                    for (i in 0 until res.headers.size) {
                        val name = res.headers.name(i).lowercase()
                        val value = res.headers.value(i)
                        respHeaders[name] = respHeaders[name]?.let { "$it, $value" } ?: value
                    }

                    ctx.log.appendHttp(
                        method = method,
                        url = url,
                        requestHeaders = headersMap,
                        status = res.code,
                        responseHeaders = respHeaders,
                        bytes = finalBytes.size,
                        ms = totalMs,
                        aggression = decision.aggression.wire
                    )
                    parsedUri?.host?.let { ctx.gate.recordSuccess(it) }

                    val location = respHeaders["location"]
                    if (!location.isNullOrBlank() && res.code in 300..399 && hop < maxRedirects) {
                        val resolved = url.toHttpUrlOrNull()?.resolve(location)?.toString()
                            ?: return@withContext ToolResult("invalid redirect location: $location", isError = true)
                        url = resolved
                        if (res.code == 303 || ((res.code == 301 || res.code == 302) && method == "POST")) {
                            method = "GET"
                            bodyBytes = null
                            headersMap.remove("content-type")
                            headersMap.remove("content-length")
                        }
                        continue
                    }

                    val rawBodyText = finalBytes.toString(Charsets.UTF_8)
                    val bodyText = if (extractText) {
                        val (title, readable) = htmlToReadableText(rawBodyText)
                        if (title.isNullOrBlank()) readable else "# $title\n\n$readable"
                    } else {
                        rawBodyText
                    }
                    val (spilledBody, artId) = spill(ctx, bodyText, "http", "$method $url")
                    val content = buildString {
                        appendLine("$method $url")
                        appendLine("status ${res.code} ${res.message} · ${finalBytes.size}b · total ${totalMs}ms (ttfb ${ttfbMs}ms)")
                        if (truncated) appendLine("body truncated at ${finalBytes.size} bytes")
                        appendLine("--- headers ---")
                        respHeaders.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine("--- body ---")
                        append(spilledBody)
                    }
                    return@withContext ToolResult(content = content, artifactId = artId)
                }
            } catch (t: Throwable) {
                parsedUri?.host?.let { ctx.gate.recordFailure(it) }
                ctx.log.appendHttp(
                    method = method,
                    url = url,
                    requestHeaders = headersMap,
                    status = 0,
                    responseHeaders = emptyMap(),
                    bytes = 0,
                    ms = System.currentTimeMillis() - started,
                    aggression = decision.aggression.wire
                )
                return@withContext ToolResult("$method $url\nnetwork error: ${t.message}", isError = true)
            }
        }
        ToolResult("too many redirects (>$maxRedirects)", isError = true)
    }
}

val netTool = object : Tool {
    override val name = "net"
    override val description =
        "Raw TCP client for protocols that are not HTTP: open a socket, send text or hex bytes, optionally wrap TLS, and collect everything the peer sends until a timeout, an idle gap, or a byte pattern appears."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "host" to schemaProp("string", "host or host:port"),
            "port" to schemaProp("number"),
            "send" to schemaProp("string", "text to send"),
            "send_hex" to schemaProp("string", "hex bytes to send, e.g. \"1603 0100\""),
            "tls" to schemaProp("boolean"),
            "server_name" to schemaProp("string", "SNI override"),
            "verify_tls" to schemaProp("boolean"),
            "read_ms" to schemaProp("number", "how long to keep reading (default 3000)"),
            "until" to schemaProp("string", "stop reading once this text appears in the response"),
            "max_bytes" to schemaProp("number", "default 262144")
        ),
        required = listOf("host")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val rawHostPort = args["host"].asStr().orEmpty()
        val (host, parsedPort) = parseHostPort(rawHostPort)
        val port = args["port"].asInt() ?: parsedPort ?: 443
        if (host.isBlank()) return@withContext ToolResult("error: host is required", isError = true)
        if (port !in 1..65535) return@withContext ToolResult("error: invalid port $port", isError = true)

        val decision = ctx.gate.check(host = host)
        if (!decision.ok) {
            ctx.log.append("tool.denied", mapOf("tool" to "net", "host" to host, "port" to port, "reason" to decision.reason))
            return@withContext ToolResult("refused by scope gate: ${decision.reason}", isError = true)
        }

        val readMs = (args["read_ms"].asInt() ?: 3000).coerceIn(200, 120_000)
        val maxBytes = (args["max_bytes"].asInt() ?: 262_144).coerceIn(1, 16 * 1024 * 1024)
        val useTls = args["tls"].asBool() ?: false
        val verifyTls = args["verify_tls"].asBool() ?: true
        val until = args["until"].asStr()

        val sendHex = args["send_hex"].asStr()
        val sendText = args["send"].asStr()
        val payload = when {
            !sendHex.isNullOrBlank() -> sendHex.replace(Regex("[^0-9a-fA-F]"), "")
                .chunked(2)
                .filter { it.length == 2 }
                .map { it.toInt(16).toByte() }
                .toByteArray()
            sendText != null -> sendText.toByteArray(Charsets.UTF_8)
            else -> null
        }

        val started = System.currentTimeMillis()
        val connectHost = decision.pinned.firstOrNull() ?: host
        val out = ByteArrayOutputStream()
        var note = "peer closed"
        var socket: Socket? = null
        try {
            val baseSocket = Socket().apply {
                connect(InetSocketAddress(connectHost, port), readMs)
                soTimeout = readMs
            }
            socket = if (useTls) {
                val sslContext = if (verifyTls) {
                    SSLContext.getDefault()
                } else {
                    SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                    }
                }
                (sslContext.socketFactory.createSocket(baseSocket, args["server_name"].asStr() ?: host, port, true) as SSLSocket).apply {
                    startHandshake()
                }
            } else {
                baseSocket
            }

            if (payload != null) {
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            } else if (!useTls && sendText == null) {
                note = "connected, no payload"
            }

            if (note != "connected, no payload") {
                val input = socket.getInputStream()
                val buf = ByteArray(4096)
                val windowCap = if (until.isNullOrEmpty()) 0 else maxOf(until.length * 4, 4096)
                val untilWindow = StringBuilder()
                while (out.size() < maxBytes) {
                    val n = try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        note = "idle timeout"
                        break
                    }
                    if (n < 0) {
                        note = "peer closed"
                        break
                    }
                    val room = maxBytes - out.size()
                    val take = n.coerceAtMost(room)
                    out.write(buf, 0, take)
                    if (!until.isNullOrEmpty() && take > 0) {
                        untilWindow.append(String(buf, 0, take, Charsets.UTF_8))
                        if (untilWindow.length > windowCap) {
                            untilWindow.delete(0, untilWindow.length - windowCap)
                        }
                        if (untilWindow.indexOf(until) >= 0) {
                            note = "matched until-pattern"
                            break
                        }
                    }
                    if (out.size() >= maxBytes) {
                        note = "max_bytes reached"
                        break
                    }
                }
            }
            ctx.gate.recordSuccess(host)
        } catch (t: Throwable) {
            ctx.gate.recordFailure(host)
            note = "error: ${t.message}"
        } finally {
            runCatching { socket?.close() }
        }

        val bytes = out.toByteArray()
        val (spilled, artId) = spill(ctx, bytes.toString(Charsets.UTF_8), "net", "$host:$port")
        val header = "$host:$port ${if (useTls) "TLS" else "TCP"} · $note · ${bytes.size}b · ${System.currentTimeMillis() - started}ms"
        ToolResult(content = "$header\n$spilled", artifactId = artId)
    }
}

private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun decompressPartial(rawBytes: ByteArray, encoding: String, maxBody: Int): ByteArray {
    val stream = when {
        encoding.contains("gzip") -> runCatching { GZIPInputStream(ByteArrayInputStream(rawBytes)) }.getOrNull()
        encoding.contains("deflate") -> runCatching { InflaterInputStream(ByteArrayInputStream(rawBytes)) }.getOrNull()
        else -> null
    } ?: return rawBytes

    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    try {
        stream.use { s ->
            while (out.size() < maxBody) {
                val n = s.read(buf, 0, minOf(buf.size, maxBody - out.size()))
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    } catch (_: Throwable) {
        if (out.size() == 0) return rawBytes
    }
    return if (out.size() > 0) out.toByteArray() else rawBytes
}

private fun parseHostPort(rawHostPort: String): Pair<String, Int?> {
    val trimmed = rawHostPort.trim()
    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf(']')
        if (close > 1) {
            val host = trimmed.substring(1, close)
            val rest = trimmed.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            return host to port
        }
    }
    val firstColon = trimmed.indexOf(':')
    val lastColon = trimmed.lastIndexOf(':')
    return if (firstColon != -1 && firstColon == lastColon) {
        val port = trimmed.substring(lastColon + 1).toIntOrNull()
        if (port != null) trimmed.substring(0, lastColon) to port else trimmed to null
    } else {
        trimmed.removePrefix("[").removeSuffix("]") to null
    }
}

val webFetchTool = object : Tool {
    override val name = "web_fetch"
    override val description =
        "Fetch a web page or JSON/text endpoint, following redirects automatically and converting HTML into clean readable text. Ideal for documentation, articles, APIs, and web research."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "url" to schemaProp("string", "HTTP/HTTPS URL to fetch"),
            "raw" to schemaProp("boolean", "return raw HTML/response body instead of readable text (default false)"),
            "timeout_ms" to schemaProp("number", "timeout in milliseconds (default 20000)")
        ),
        required = listOf("url")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        var url = args["url"].asStr().orEmpty().trim()
        if (url.isBlank()) return@withContext ToolResult("error: url is required", isError = true)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        val raw = args["raw"].asBool() ?: false
        val timeoutMs = (args["timeout_ms"].asInt() ?: 20_000).toLong().coerceIn(1000L, 60_000L)
        val client = buildOkHttp(timeoutMs, verifyTls = true, http2 = true)

        for (hop in 0..5) {
            val decision = ctx.gate.check(url = url)
            if (!decision.ok) {
                return@withContext ToolResult("refused by policy: ${decision.reason}", isError = true)
            }
            val started = System.currentTimeMillis()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; BorerAgent/1.0)")
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .get()
                .build()
            try {
                client.newCall(req).execute().use { res ->
                    val location = res.header("location")
                    if (!location.isNullOrBlank() && res.code in 300..399 && hop < 5) {
                        val resolved = url.toHttpUrlOrNull()?.resolve(location)?.toString()
                            ?: return@withContext ToolResult("error: invalid redirect location: $location", isError = true)
                        url = resolved
                        continue
                    }
                    val rawBytes = readBoundedBytes(res.body?.byteStream(), 2 * 1024 * 1024)
                    val encoding = res.header("content-encoding")?.lowercase().orEmpty()
                    val finalBytes = if (rawBytes.isNotEmpty()) decompressPartial(rawBytes, encoding, 2 * 1024 * 1024) else rawBytes
                    val totalMs = System.currentTimeMillis() - started
                    val contentType = res.header("content-type")?.lowercase().orEmpty()
                    val parsedHost = runCatching { URI(url).host }.getOrNull()
                    parsedHost?.let { ctx.gate.recordSuccess(it) }

                    val respHeaders = linkedMapOf<String, String>()
                    for (i in 0 until res.headers.size) {
                        respHeaders[res.headers.name(i).lowercase()] = res.headers.value(i)
                    }
                    ctx.log.appendHttp(
                        method = "GET",
                        url = url,
                        requestHeaders = emptyMap(),
                        status = res.code,
                        responseHeaders = respHeaders,
                        bytes = finalBytes.size,
                        ms = totalMs,
                        aggression = decision.aggression.wire
                    )

                    val decoded = finalBytes.toString(Charsets.UTF_8)
                    val bodyText = if (!raw && (contentType.contains("html") || decoded.contains("<html", ignoreCase = true) || decoded.contains("<body", ignoreCase = true))) {
                        val (title, readable) = htmlToReadableText(decoded)
                        if (title.isNullOrBlank()) readable else "# $title\n\n$readable"
                    } else {
                        decoded
                    }
                    val (spilled, artId) = spill(ctx, bodyText, "web", url)
                    return@withContext ToolResult(
                        content = "GET $url\nstatus ${res.code} · ${finalBytes.size}b · ${totalMs}ms\n\n$spilled",
                        isError = res.code >= 400,
                        artifactId = artId
                    )
                }
            } catch (t: Throwable) {
                runCatching { URI(url).host }.getOrNull()?.let { ctx.gate.recordFailure(it) }
                return@withContext ToolResult("error fetching $url: ${t.message}", isError = true)
            }
        }
        ToolResult("error: too many redirects", isError = true)
    }
}

val webSearchTool = object : Tool {
    override val name = "web_search"
    override val description =
        "Search the public web for up-to-date facts, documentation, news, packages, or references. Uses the connected MCP search server (such as github.com/Ashveil1/whsearch) when active, with built-in search fallback."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "query" to schemaProp("string", "Search query keywords"),
            "limit" to schemaProp("number", "Max results to return (default 8, max 15)")
        ),
        required = listOf("query")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args["query"].asStr().orEmpty().trim()
        if (query.isBlank()) return@withContext ToolResult("error: query is required", isError = true)
        val limit = (args["limit"].asInt() ?: 8).coerceIn(1, 15)

        val mcpTarget = ctx.mcpManager?.findWebSearchTarget()
        var mcpFallbackNote: String? = null
        if (mcpTarget != null) {
            val (server, tool) = mcpTarget
            val mappedArgs = McpManager.mapSearchArgs(tool, query, limit)
            val mcpRes = ctx.mcpManager.callTool(server.config.id, tool.name, mappedArgs, ctx)
            if (!mcpRes.isError && mcpRes.content.isNotBlank()) {
                ctx.log.append(
                    "mcp.web_search",
                    mapOf("server" to server.config.id, "tool" to tool.name, "query" to query, "ok" to true)
                )
                return@withContext ToolResult(
                    content = "[via MCP ${server.config.id}/${tool.name}]\n${mcpRes.content}",
                    artifactId = mcpRes.artifactId
                )
            }
            ctx.log.append(
                "mcp.web_search",
                mapOf("server" to server.config.id, "tool" to tool.name, "query" to query, "ok" to false, "error" to mcpRes.content.take(200))
            )
            mcpFallbackNote = "[MCP ${server.config.id}/${tool.name} unavailable -> built-in fallback: ${mcpRes.content.take(120)}]"
        }

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encoded"
        val decision = ctx.gate.check(url = searchUrl)
        if (!decision.ok) {
            return@withContext ToolResult("refused by policy: ${decision.reason}", isError = true)
        }

        val client = buildOkHttp(20_000L, verifyTls = true, http2 = true)
        val req = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Encoding", "gzip, deflate")
            .get()
            .build()

        val started = System.currentTimeMillis()
        try {
            client.newCall(req).execute().use { res ->
                val rawBytes = readBoundedBytes(res.body?.byteStream(), 1024 * 1024)
                val encoding = res.header("content-encoding")?.lowercase().orEmpty()
                val html = (if (rawBytes.isNotEmpty()) decompressPartial(rawBytes, encoding, 1024 * 1024) else rawBytes)
                    .toString(Charsets.UTF_8)
                val totalMs = System.currentTimeMillis() - started
                ctx.gate.recordSuccess("html.duckduckgo.com")
                ctx.log.appendHttp(
                    method = "GET",
                    url = searchUrl,
                    requestHeaders = emptyMap(),
                    status = res.code,
                    responseHeaders = emptyMap(),
                    bytes = html.length,
                    ms = totalMs,
                    aggression = decision.aggression.wire
                )
                if (res.code >= 400) {
                    return@withContext ToolResult("web_search HTTP ${res.code} for \"$query\"", isError = true)
                }

                val results = parseDuckDuckGoResults(html, limit)
                if (results.isEmpty()) {
                    val emptyMsg = "No web search results extracted for \"$query\" (HTTP ${res.code}, ${totalMs}ms). Try rephrasing or use web_fetch/http on a direct URL."
                    return@withContext ToolResult(listOfNotNull(mcpFallbackNote, emptyMsg).joinToString("\n"))
                }
                val formatted = buildString {
                    mcpFallbackNote?.let { appendLine(it) }
                    appendLine("Search results for \"$query\" (${results.size} hits, ${totalMs}ms):")
                    appendLine()
                    results.forEachIndexed { idx, (title, url, snippet) ->
                        appendLine("${idx + 1}. $title")
                        appendLine("   $url")
                        if (snippet.isNotBlank()) appendLine("   $snippet")
                    }
                }.trimEnd()
                ToolResult(formatted)
            }
        } catch (t: Throwable) {
            ctx.gate.recordFailure("html.duckduckgo.com")
            val errMsg = "web_search failed for \"$query\": ${t.message}"
            ToolResult(listOfNotNull(mcpFallbackNote, errMsg).joinToString("\n"), isError = true)
        }
    }
}

private fun readBoundedBytes(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
    if (stream == null) return ByteArray(0)
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < maxBytes) {
        val read = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
        if (read <= 0) break
        out.write(buf, 0, read)
        total += read
    }
    return out.toByteArray()
}

private fun decodeHtmlEntities(text: String): String {
    val named = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
    return Regex("&#(x?)([0-9a-fA-F]+);").replace(named) { m ->
        val radix = if (m.groupValues[1].equals("x", ignoreCase = true)) 16 else 10
        val code = m.groupValues[2].toIntOrNull(radix)
        if (code != null && code in 1..0xFFFF) code.toChar().toString() else m.value
    }
}

private fun htmlToReadableText(html: String): Pair<String?, String> {
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.get(1)
        ?.let { decodeHtmlEntities(it).replace(Regex("\\s+"), " ").trim() }
        ?.takeIf { it.isNotBlank() }

    var cleaned = html
    val stripTags = listOf("script", "style", "noscript", "svg", "template")
    for (tag in stripTags) {
        cleaned = cleaned.replace(
            Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            " "
        )
    }
    cleaned = cleaned
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|li|tr|h[1-6]|section|article|blockquote|pre)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "- ")
        .replace(Regex("<[^>]+>"), " ")

    val decoded = decodeHtmlEntities(cleaned)
    val normalized = decoded.lines()
        .map { it.replace(Regex("[ \\t\\r]+"), " ").trim() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return title to normalized
}

private fun parseDuckDuckGoResults(html: String, limit: Int): List<Triple<String, String, String>> {
    val linkRegex = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val snippetRegex = Regex(
        """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val links = linkRegex.findAll(html).toList()
    val snippets = snippetRegex.findAll(html).toList()
    val out = mutableListOf<Triple<String, String, String>>()
    for (i in links.indices) {
        if (out.size >= limit) break
        val m = links[i]
        val rawHref = decodeHtmlEntities(m.groupValues[1].trim())
        val resolvedUrl = resolveDuckDuckGoHref(rawHref)
        val title = decodeHtmlEntities(m.groupValues[2].replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isBlank() || resolvedUrl.isBlank()) continue
        val snippet = snippets.getOrNull(i)?.groupValues?.get(1)
            ?.let { decodeHtmlEntities(it.replace(Regex("<[^>]+>"), " ")) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        out.add(Triple(title, resolvedUrl, snippet))
    }
    return out
}

private fun resolveDuckDuckGoHref(rawHref: String): String {
    val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
    val uddgMatch = Regex("[?&]uddg=([^&]+)").find(normalized)
    if (uddgMatch != null) {
        runCatching { return URLDecoder.decode(uddgMatch.groupValues[1], Charsets.UTF_8.name()) }
    }
    return normalized
}
