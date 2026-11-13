package com.example.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            "decompress" to schemaProp("boolean", "default true")
        ),
        required = listOf("url")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        var url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var method = (args["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val maxRedirects = args["redirects"]?.jsonPrimitive?.intOrNull ?: 0
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 30_000).toLong().coerceIn(500L, 120_000L)
        val verifyTls = args["verify_tls"]?.jsonPrimitive?.booleanOrNull ?: true
        val http2 = args["http2"]?.jsonPrimitive?.booleanOrNull ?: false
        val decompress = args["decompress"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxBody = args["max_body"]?.jsonPrimitive?.intOrNull ?: MAX_HTTP_BODY

        val headersMap = linkedMapOf<String, String>()
        (args["headers"] as? JsonObject)?.forEach { (k, v) ->
            headersMap[k.lowercase()] = v.jsonPrimitive.contentOrNull.orEmpty()
        }

        val bodyB64 = args["body_base64"]?.jsonPrimitive?.contentOrNull
        val bodyStr = args["body"]?.jsonPrimitive?.contentOrNull
        var bodyBytes: ByteArray? = when {
            !bodyB64.isNullOrEmpty() -> runCatching { Base64.getDecoder().decode(bodyB64) }.getOrNull()
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
                        runCatching {
                            when {
                                encoding.contains("gzip") -> GZIPInputStream(ByteArrayInputStream(rawBytes)).readBytes()
                                encoding.contains("deflate") -> InflaterInputStream(ByteArrayInputStream(rawBytes)).readBytes()
                                else -> rawBytes
                            }
                        }.getOrDefault(rawBytes)
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
                        url = URI(url).resolve(location).toString()
                        if (res.code == 303 || ((res.code == 301 || res.code == 302) && method == "POST")) {
                            method = "GET"
                            bodyBytes = null
                            headersMap.remove("content-type")
                            headersMap.remove("content-length")
                        }
                        continue
                    }

                    val bodyText = finalBytes.toString(Charsets.UTF_8)
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
        val rawHostPort = args["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val parts = rawHostPort.split(":")
        val host = parts.firstOrNull().orEmpty().removePrefix("[").removeSuffix("]")
        val port = args["port"]?.jsonPrimitive?.intOrNull ?: parts.getOrNull(1)?.toIntOrNull() ?: 443

        val decision = ctx.gate.check(host = host)
        if (!decision.ok) {
            ctx.log.append("tool.denied", mapOf("tool" to "net", "host" to host, "port" to port, "reason" to decision.reason))
            return@withContext ToolResult("refused by scope gate: ${decision.reason}", isError = true)
        }

        val readMs = (args["read_ms"]?.jsonPrimitive?.intOrNull ?: 3000).coerceIn(200, 120_000)
        val maxBytes = args["max_bytes"]?.jsonPrimitive?.intOrNull ?: 262_144
        val useTls = args["tls"]?.jsonPrimitive?.booleanOrNull ?: false
        val verifyTls = args["verify_tls"]?.jsonPrimitive?.booleanOrNull ?: true
        val until = args["until"]?.jsonPrimitive?.contentOrNull

        val sendHex = args["send_hex"]?.jsonPrimitive?.contentOrNull
        val sendText = args["send"]?.jsonPrimitive?.contentOrNull
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
                (sslContext.socketFactory.createSocket(baseSocket, args["server_name"]?.jsonPrimitive?.contentOrNull ?: host, port, true) as SSLSocket).apply {
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
                    if (out.size() >= maxBytes) {
                        note = "max_bytes reached"
                        break
                    }
                    if (!until.isNullOrEmpty() && out.toByteArray().toString(Charsets.UTF_8).contains(until)) {
                        note = "matched until-pattern"
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
