package com.example.tools

import com.example.data.ArtifactStore
import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.RunDatabase
import com.example.data.RunStore
import com.example.runtime.ShellExecResult
import com.example.scope.EngagementGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

interface JobRegistry {
    suspend fun start(name: String, command: String): StartedJob
    fun tail(id: String, lines: Int): String
    fun poll(id: String): JobPollResult
    fun kill(id: String)
    fun list(): List<JobSummary>
}

data class StartedJob(val id: String, val pid: Long, val log: String)
data class JobPollResult(val status: String, val exitCode: Int?)
data class JobSummary(val id: String, val command: String, val status: String, val started: String)

class ToolContext(
    val runDir: File,
    val workdir: File,
    val gate: EngagementGate,
    val log: EventLog,
    val artifacts: ArtifactStore,
    val store: RunStore,
    val db: RunDatabase,
    private val settingsProvider: () -> BorerSettings,
    val isCancelled: () -> Boolean,
    val notify: (String) -> Unit,
    var finish: ((summary: String, outcome: String?) -> Unit)? = null,
    val jobs: JobRegistry? = null,
    val proxyUrl: String? = null,
    val shellExecutor: suspend (command: String, cwd: File, env: Map<String, String>, timeoutMs: Long, stdin: String?) -> ShellExecResult = { command, cwd, env, timeoutMs, stdin ->
        defaultLocalShellExec(command, cwd, env, timeoutMs, stdin)
    }
) {
    val settings: BorerSettings get() = settingsProvider()
}

private suspend fun defaultLocalShellExec(
    command: String,
    cwd: File,
    env: Map<String, String>,
    timeoutMs: Long,
    stdin: String?
): ShellExecResult = withContext(Dispatchers.IO) {
    var proc: Process? = null
    var readerThread: Thread? = null
    var stdinThread: Thread? = null
    try {
        val shellBin = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val pb = ProcessBuilder(shellBin, "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val started = pb.start()
        proc = started
        val outputLock = Any()
        val outputBuf = java.io.ByteArrayOutputStream()
        readerThread = Thread {
            runCatching {
                val buf = ByteArray(4096)
                val stream = started.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) synchronized(outputLock) { outputBuf.write(buf, 0, n) }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        stdinThread = Thread {
            runCatching {
                if (stdin != null) {
                    started.outputStream.bufferedWriter().use { it.write(stdin) }
                } else {
                    started.outputStream.close()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = withTimeoutOrNull(timeoutMs.coerceAtLeast(100L)) {
            while (!started.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (!isActive) break
            }
            true
        } == true
        if (finished && isActive && !started.isAlive) {
            runCatching { stdinThread.join(500) }
            runCatching { readerThread.join(1_000) }
            val output = synchronized(outputLock) { outputBuf.toString(Charsets.UTF_8.name()) }
            ShellExecResult(started.exitValue(), output, timedOut = false)
        } else {
            started.destroyForcibly()
            runCatching { started.waitFor(200, TimeUnit.MILLISECONDS) }
            runCatching { started.inputStream.close() }
            runCatching { started.outputStream.close() }
            stdinThread.interrupt()
            readerThread.interrupt()
            runCatching { readerThread.join(200) }
            val partial = synchronized(outputLock) { outputBuf.toString(Charsets.UTF_8.name()) }
            ShellExecResult(null, partial, timedOut = true)
        }
    } catch (e: CancellationException) {
        proc?.destroyForcibly()
        throw e
    } catch (t: Throwable) {
        proc?.destroyForcibly()
        ShellExecResult(null, "[spawn error] ${t.message}")
    } finally {
        if (proc?.isAlive == true) {
            runCatching { proc.destroyForcibly() }
        }
        runCatching { proc?.inputStream?.close() }
        runCatching { proc?.outputStream?.close() }
        stdinThread?.interrupt()
        readerThread?.interrupt()
    }
}

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val artifactId: String? = null
)

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

data class ToolExecutionResult(
    val content: String,
    val isError: Boolean,
    val ms: Long
)

class ToolRegistry(initialTools: List<Tool> = emptyList()) {
    private val tools = linkedMapOf<String, Tool>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        initialTools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        require(!tools.containsKey(tool.name)) { "duplicate tool: ${tool.name}" }
        tools[tool.name] = tool
    }

    fun has(name: String): Boolean = tools.containsKey(name)
    fun names(): List<String> = tools.keys.toList()

    fun defs(): List<com.example.provider.ToolDef> =
        tools.values.map { com.example.provider.ToolDef(it.name, it.description, it.parameters) }

    suspend fun execute(name: String, rawArguments: String, ctx: ToolContext): ToolExecutionResult {
        val tool = tools[name]
            ?: return ToolExecutionResult(
                content = "error: unknown tool \"$name\". available: ${names().joinToString(", ")}",
                isError = true,
                ms = 0
            )
        val started = System.currentTimeMillis()
        val args: JsonObject = if (rawArguments.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(rawArguments).jsonObject }.getOrElse { err ->
                return ToolExecutionResult(
                    content = "error: arguments for \"$name\" are not valid JSON (${err.message}). raw: ${rawArguments.take(500)}",
                    isError = true,
                    ms = System.currentTimeMillis() - started
                )
            }
        }
        return try {
            val res = withContext(Dispatchers.IO) { tool.run(args, ctx) }
            ToolExecutionResult(res.content, res.isError, System.currentTimeMillis() - started)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ToolExecutionResult(
                content = "error: ${tool.name} threw: ${t::class.simpleName}: ${t.message}",
                isError = true,
                ms = System.currentTimeMillis() - started
            )
        }
    }
}

fun spill(ctx: ToolContext, text: String, kind: String, label: String): Pair<String, String?> {
    val budget = ctx.settings.inlineBudget
    val bytes = text.toByteArray(Charsets.UTF_8).size
    if (bytes <= budget) return text to null
    val ref = ctx.artifacts.put(text, kind, label)
    ctx.store.registerArtifact(ref.id, ref.kind, ref.label, ref.bytes, ref.lines, ref.path)
    ctx.log.append("artifact", mapOf("id" to ref.id, "kind" to kind, "label" to label, "bytes" to ref.bytes, "lines" to ref.lines))
    ctx.log.artifactMeta(ref.id, kind, label, ref.bytes, ref.lines)
    val head = text.take(1200)
    val tail = text.takeLast(600)
    val msg = buildString {
        appendLine("[artifact ${ref.id}] ${ref.bytes} bytes / ${ref.lines} lines stored on disk (over $budget byte inline budget).")
        appendLine("Use recall({ id: \"${ref.id}\", ... }) to page, grep, or slice it.")
        appendLine("--- first bytes ---")
        appendLine(head)
        if (text.length > 1800) {
            appendLine("--- last bytes ---")
            append(tail)
        }
    }.trimEnd()
    return msg to ref.id
}

fun schemaObject(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) {
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

fun schemaProp(type: String, description: String? = null, itemType: String? = null): JsonObject = buildJsonObject {
    put("type", type)
    description?.let { put("description", it) }
    if (type == "array" && itemType != null) {
        put("items", buildJsonObject { put("type", itemType) })
    }
}
