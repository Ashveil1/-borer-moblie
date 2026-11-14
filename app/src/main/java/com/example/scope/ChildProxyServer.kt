package com.example.scope

import com.example.data.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale

/**
 * In-process loopback HTTP & CONNECT proxy wired via HTTP_PROXY / HTTPS_PROXY into child
 * processes (bash, verify, background jobs) so every outbound request goes through EngagementGate
 * and lands in http.jsonl, matching the original borer child-proxy architecture.
 */
class ChildProxyServer(
    private val gate: EngagementGate,
    private val log: EventLog
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    @Volatile
    var proxyUrl: String? = null
        private set

    fun start(): String? {
        if (proxyUrl != null) return proxyUrl
        return runCatching {
            val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            serverSocket = ss
            val url = "http://127.0.0.1:${ss.localPort}"
            proxyUrl = url
            scope.launch {
                while (!ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            }
            url
        }.getOrNull()
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val requestLine = readLineCrlf(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLineCrlf(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method == "CONNECT") {
                handleConnect(target, headers, input, output)
            } else {
                handlePlainHttp(method, target, parts[2], headers, input, output)
            }
        }
    }

    private fun handleConnect(
        authority: String,
        headers: Map<String, String>,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val started = System.currentTimeMillis()
        val (host, parsedPort) = splitAuthority(authority)
        val port = parsedPort ?: 443
        val decision = runBlocking { gate.check(host = host) }
        if (!decision.ok) {
            log.append("tool.denied", mapOf("tool" to "proxy", "host" to authority, "reason" to decision.reason))
            clientOut.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        val connectHost = decision.pinned.firstOrNull() ?: host
        val upstream = try {
            Socket().apply {
                connect(InetSocketAddress(connectHost, port), 15_000)
                soTimeout = 60_000
            }
        } catch (t: Throwable) {
            gate.recordFailure(host)
            log.appendHttp("CONNECT", "https://$authority", headers, 502, emptyMap(), 0, System.currentTimeMillis() - started, decision.aggression.wire)
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        upstream.use { remote ->
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()

            val t1 = Thread {
                runCatching { clientIn.copyTo(remote.getOutputStream()) }
                runCatching { remote.shutdownOutput() }
            }
            t1.start()
            val bytesDown = runCatching {
                val buf = ByteArray(8192)
                var total = 0L
                val rIn = remote.getInputStream()
                while (true) {
                    val n = rIn.read(buf)
                    if (n <= 0) break
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    total += n
                }
                total
            }.getOrDefault(0L)
            runCatching { clientIn.close() }
            runCatching { t1.join(1000) }
            gate.recordSuccess(host)
            log.appendHttp(
                method = "CONNECT",
                url = "https://$authority",
                requestHeaders = headers,
                status = 200,
                responseHeaders = emptyMap(),
                bytes = bytesDown.toInt(),
                ms = System.currentTimeMillis() - started,
                aggression = decision.aggression.wire
            )
        }
    }

    private fun handlePlainHttp(
        method: String,
        rawUrl: String,
        httpVersion: String,
        headers: Map<String, String>,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val started = System.currentTimeMillis()
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        val hostHeader = headers["host"].orEmpty()
        val resolvedUrl = if (uri?.scheme != null) rawUrl else {
            val suffix = if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"
            "http://$hostHeader$suffix"
        }
        val parsed = runCatching { URI(resolvedUrl) }.getOrNull()
        val (hdrHost, hdrPort) = splitAuthority(hostHeader)
        val host = parsed?.host?.removePrefix("[")?.removeSuffix("]") ?: hdrHost
        val port = if ((parsed?.port ?: -1) > 0) parsed!!.port else (hdrPort ?: 80)

        val decision = runBlocking { gate.check(url = resolvedUrl) }
        if (!decision.ok) {
            log.append("tool.denied", mapOf("tool" to "proxy", "url" to resolvedUrl, "reason" to decision.reason))
            clientOut.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
        val reqBody = if (contentLen > 0) readExactBytes(clientIn, contentLen) else ByteArray(0)

        val connectHost = decision.pinned.firstOrNull() ?: host
        try {
            Socket().use { remote ->
                remote.connect(InetSocketAddress(connectHost, port), 15_000)
                remote.soTimeout = 30_000
                val path = (parsed?.rawPath?.ifEmpty { "/" } ?: "/") +
                    (parsed?.rawQuery?.let { "?$it" } ?: "")
                val rOut = remote.getOutputStream()
                val headerBlock = buildString {
                    append("$method $path $httpVersion\r\n")
                    headers.filterKeys { it != "proxy-connection" && it != "connection" && it != "keep-alive" }
                        .forEach { (k, v) -> append("$k: $v\r\n") }
                    append("connection: close\r\n\r\n")
                }
                rOut.write(headerBlock.toByteArray(Charsets.ISO_8859_1))
                if (reqBody.isNotEmpty()) rOut.write(reqBody)
                rOut.flush()

                val rIn = remote.getInputStream()
                val statusLine = readLineCrlf(rIn).orEmpty()
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                val respHeaders = linkedMapOf<String, String>()
                val rawHeaderOut = ByteArrayOutputStream()
                rawHeaderOut.write((statusLine + "\r\n").toByteArray(Charsets.ISO_8859_1))
                while (true) {
                    val line = readLineCrlf(rIn) ?: break
                    rawHeaderOut.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        respHeaders[line.substring(0, colon).trim().lowercase(Locale.US)] =
                            line.substring(colon + 1).trim()
                    }
                }
                clientOut.write(rawHeaderOut.toByteArray())
                val respBytes = rIn.readBytes()
                clientOut.write(respBytes)
                clientOut.flush()

                gate.recordSuccess(host)
                log.appendHttp(
                    method = method,
                    url = resolvedUrl,
                    requestHeaders = headers,
                    status = statusCode,
                    responseHeaders = respHeaders,
                    bytes = respBytes.size,
                    ms = System.currentTimeMillis() - started,
                    aggression = decision.aggression.wire
                )
            }
        } catch (_: Throwable) {
            gate.recordFailure(host)
            log.appendHttp(method, resolvedUrl, headers, 0, emptyMap(), 0, System.currentTimeMillis() - started, decision.aggression.wire)
            runCatching {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                clientOut.flush()
            }
        }
    }

    private fun readLineCrlf(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            out.write(b)
        }
    }

    private fun readExactBytes(input: InputStream, totalBytes: Int): ByteArray {
        val capped = totalBytes.coerceIn(0, 16 * 1024 * 1024)
        val buf = ByteArray(capped)
        var offset = 0
        while (offset < capped) {
            val read = input.read(buf, offset, capped - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == capped) buf else buf.copyOf(offset)
    }

    private fun splitAuthority(authority: String): Pair<String, Int?> {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx > 1) {
                val host = trimmed.substring(1, closeIdx)
                val rest = trimmed.substring(closeIdx + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
                return host to port
            }
        }
        val firstColon = trimmed.indexOf(':')
        val lastColon = trimmed.lastIndexOf(':')
        return if (firstColon != -1 && firstColon == lastColon) {
            val p = trimmed.substring(lastColon + 1).toIntOrNull()
            if (p != null) trimmed.substring(0, lastColon) to p else trimmed to null
        } else {
            trimmed.removePrefix("[").removeSuffix("]") to null
        }
    }

    override fun close() {
        proxyUrl = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }
}
