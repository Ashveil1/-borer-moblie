package com.example.tools

import android.content.Context
import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.JobEntity
import com.example.data.RunDatabase
import com.example.data.ShellBackend
import com.example.runtime.UbuntuProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_INLINE_LOG = 512 * 1024

val bashTool = object : Tool {
    override val name = "bash"
    override val description =
        "Run a shell command in the configured runtime (/system/bin/sh or proot-distro ubuntu). stdout and stderr are captured together; oversized output is stored as an artifact."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "command" to schemaProp("string"),
            "cwd" to schemaProp("string"),
            "timeout_ms" to schemaProp("number", "default 120000"),
            "stdin" to schemaProp("string")
        ),
        required = listOf("command")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (command.isBlank()) return@withContext ToolResult("error: empty command", isError = true)
        val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull?.let { File(it) } ?: ctx.workdir
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 120_000).coerceIn(500, 1_800_000).toLong()
        val stdinText = args["stdin"]?.jsonPrimitive?.contentOrNull

        val tmpDir = File(ctx.runDir, "tmp").apply { mkdirs() }
        val logFile = File(tmpDir, "bash-${System.currentTimeMillis()}.log")
        val started = System.currentTimeMillis()
        val env = buildMap {
            put("BORER_RUN_DIR", ctx.runDir.absolutePath)
            put("BORER_WORKDIR", ctx.workdir.absolutePath)
            put("TERM", "dumb")
            ctx.proxyUrl?.let { px ->
                put("HTTP_PROXY", px)
                put("HTTPS_PROXY", px)
                put("http_proxy", px)
                put("https_proxy", px)
            }
        }

        val execRes = ctx.shellExecutor(command, cwd, env, timeoutMs, stdinText)
        logFile.writeText(execRes.output)

        val ms = System.currentTimeMillis() - started
        val full = execRes.output
        val size = full.toByteArray(Charsets.UTF_8).size
        val body = if (size <= MAX_INLINE_LOG) {
            full
        } else {
            val ref = ctx.artifacts.put(full, "bash", command.take(80))
            ctx.store.registerArtifact(ref.id, ref.kind, ref.label, ref.bytes, ref.lines, ref.path)
            ctx.log.append("artifact", mapOf("id" to ref.id, "kind" to "bash", "bytes" to ref.bytes, "lines" to ref.lines))
            "[artifact ${ref.id}] full output is $size bytes; first 8 KB and last 4 KB inline.\n--- head ---\n${full.take(8192)}\n--- tail ---\n${full.takeLast(4096)}"
        }

        val header = listOfNotNull(
            "exit=${execRes.exitCode ?: "null"}${if (execRes.timedOut) " (killed after timeout)" else ""} ms=$ms bytes=$size",
            if (body.isBlank()) "(no output)" else null
        ).joinToString(" ")
        val (spilled, artId) = spill(ctx, body, "bash", command.take(80))
        ToolResult(content = "$header\n$spilled", artifactId = artId)
    }
}

class BackgroundJobs(
    private val appContext: Context,
    private val settingsProvider: () -> BorerSettings,
    private val runId: String,
    private val runDir: File,
    private val db: RunDatabase,
    private val log: EventLog,
    private val workdir: File,
    private val scope: CoroutineScope,
    private val proxyUrl: String? = null
) : JobRegistry {
    private val live = ConcurrentHashMap<String, LiveJob>()

    private data class LiveJob(
        val id: String,
        val logFile: File,
        val process: Process? = null,
        val coroutineJob: kotlinx.coroutines.Job? = null,
        var exitCode: Int? = null
    )

    init {
        File(runDir, "jobs").mkdirs()
    }

    override suspend fun start(name: String, command: String): StartedJob = withContext(Dispatchers.IO) {
        val safeName = name.replace(Regex("[^a-zA-Z0-9]+"), "-").take(24).ifBlank { "job" }
        val id = "$safeName-${UUID.randomUUID().toString().take(6)}"
        val logFile = File(File(runDir, "jobs"), "$id.log")
        val env = buildMap {
            put("BORER_RUN_DIR", runDir.absolutePath)
            put("BORER_WORKDIR", workdir.absolutePath)
            proxyUrl?.let { px ->
                put("HTTP_PROXY", px)
                put("HTTPS_PROXY", px)
                put("http_proxy", px)
                put("https_proxy", px)
            }
        }
        val settings = settingsProvider()
        val startedAt = Instant.now().toString()
        db.jobDao().upsertSync(
            JobEntity(
                id = id,
                runId = runId,
                command = command,
                pid = null,
                logPath = logFile.absolutePath,
                startedAt = startedAt,
                status = "running"
            )
        )
        log.append("tool.call", mapOf("job" to id, "command" to command.take(400)))

        if (settings.shellBackend == ShellBackend.TERMUX_PROOT) {
            val jobEntry = LiveJob(id = id, logFile = logFile)
            live[id] = jobEntry
            val runner = scope.launch(Dispatchers.IO) {
                val res = UbuntuProotRuntime.exec(appContext, command, workdir, env, 1_800_000L, null, settings)
                logFile.writeText(res.output)
                val code = res.exitCode ?: -1
                jobEntry.exitCode = code
                db.jobDao().completeSync(id, Instant.now().toString(), code, if (code == 0) "done" else "failed")
                log.append("tool.result", mapOf("job" to id, "exitCode" to code))
            }
            live[id] = jobEntry.copy(coroutineJob = runner)
        } else {
            val pb = UbuntuProotRuntime.buildProcessBuilder(appContext, command, workdir, env, settings)
                .getOrElse {
                    ProcessBuilder("/system/bin/sh", "-c", "echo '[job error] ${it.message}' && exit 1")
                        .directory(workdir)
                        .redirectErrorStream(true)
                }
                .redirectOutput(logFile)
            val proc = pb.start()
            val job = LiveJob(id = id, logFile = logFile, process = proc)
            live[id] = job
            scope.launch(Dispatchers.IO) {
                val code = proc.waitFor()
                job.exitCode = code
                db.jobDao().completeSync(id, Instant.now().toString(), code, if (code == 0) "done" else "failed")
                log.append("tool.result", mapOf("job" to id, "exitCode" to code))
            }
        }
        StartedJob(id = id, pid = 0L, log = logFile.absolutePath)
    }

    override fun tail(id: String, lines: Int): String {
        val path = live[id]?.logFile ?: db.jobDao().getJobSync(id)?.logPath?.let { File(it) }
        if (path == null || !path.exists()) return "error: no log for job $id"
        return path.readLines().takeLast(lines.coerceAtLeast(1)).joinToString("\n").ifBlank { "(no output yet)" }
    }

    override fun poll(id: String): JobPollResult {
        val l = live[id]
        if (l != null) {
            val code = l.exitCode
            val status = when (code) {
                null -> "running"
                0 -> "done"
                else -> "failed"
            }
            return JobPollResult(status, code)
        }
        val row = db.jobDao().getJobSync(id) ?: return JobPollResult("unknown", null)
        return JobPollResult(row.status, row.exitCode)
    }

    override fun kill(id: String) {
        val l = live[id] ?: return
        l.process?.destroyForcibly()
        l.coroutineJob?.cancel()
        l.exitCode = -1
        db.jobDao().completeSync(id, Instant.now().toString(), -1, "killed")
    }

    override fun list(): List<JobSummary> =
        db.jobDao().listJobsSync(runId).map { JobSummary(it.id, it.command, it.status, it.startedAt) }
}

val jobTool = object : Tool {
    override val name = "job"
    override val description = "Run something in the background and keep the turn moving. start, tail, poll, kill, or list."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "start | tail | poll | kill | list"),
            "command" to schemaProp("string"),
            "name" to schemaProp("string"),
            "id" to schemaProp("string"),
            "lines" to schemaProp("number")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = ctx.jobs ?: return ToolResult("error: background jobs are not enabled in this session", isError = true)
        return when (val action = args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
            "start" -> {
                val cmd = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (cmd.isBlank()) return ToolResult("error: command is required", isError = true)
                val started = jobs.start(args["name"]?.jsonPrimitive?.contentOrNull ?: "job", cmd)
                ToolResult("started ${started.id} log=${started.log}")
            }
            "list" -> {
                val rows = jobs.list()
                ToolResult(
                    if (rows.isEmpty()) "no jobs"
                    else rows.joinToString("\n") { "${it.id}  ${it.status.padEnd(8)} ${it.command.take(120)}" }
                )
            }
            else -> {
                val id = args["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isEmpty()) return ToolResult("error: id is required", isError = true)
                when (action) {
                    "tail" -> ToolResult(jobs.tail(id, args["lines"]?.jsonPrimitive?.intOrNull ?: 80))
                    "poll" -> {
                        val st = jobs.poll(id)
                        ToolResult("$id: ${st.status}${st.exitCode?.let { " exit=$it" } ?: ""}")
                    }
                    "kill" -> {
                        jobs.kill(id)
                        ToolResult("killed $id")
                    }
                    else -> ToolResult("unknown action: $action", isError = true)
                }
            }
        }
    }
}

val sessionTool = object : Tool {
    override val name = "session"
    override val description = "Save or branch your work. Actions: snapshot | list | fork | info."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "snapshot | list | fork | info"),
            "label" to schemaProp("string"),
            "messages" to schemaProp("string")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val snapRoot = File(ctx.runDir, "snapshots")
        if (action == "list") {
            val dirs = snapRoot.listFiles()?.map { it.name }.orEmpty()
            return ToolResult(if (dirs.isEmpty()) "no snapshots yet" else dirs.joinToString("\n"))
        }
        if (action == "info") {
            return ToolResult(
                "run dir: ${ctx.runDir.absolutePath}\nartifacts: ${ctx.artifacts.list().size}\nnotes: ${ctx.store.digest()}"
            )
        }
        val label = (args["label"]?.jsonPrimitive?.contentOrNull ?: Instant.now().toString().replace(':', '-')).take(40)
        if (action == "snapshot") {
            val dir = File(snapRoot, label).apply { mkdirs() }
            val artSrc = File(ctx.runDir, "artifacts")
            if (artSrc.exists()) artSrc.copyRecursively(File(dir, "artifacts"), overwrite = true)
            val msgStr = args["messages"]?.jsonPrimitive?.contentOrNull
            if (!msgStr.isNullOrBlank()) {
                runCatching { Json.parseToJsonElement(msgStr) }.getOrElse {
                    return ToolResult("error: messages must be valid JSON", isError = true)
                }
                File(dir, "messages.json").writeText(msgStr)
            }
            ctx.log.append("session.snapshot", mapOf("dir" to dir.absolutePath, "label" to label))
            return ToolResult("snapshot written: ${dir.name}")
        }
        if (action == "fork") {
            val target = File(ctx.runDir.parentFile, "${ctx.runDir.name}-fork-$label").apply { mkdirs() }
            val artSrc = File(ctx.runDir, "artifacts")
            if (artSrc.exists()) artSrc.copyRecursively(File(target, "artifacts"), overwrite = true)
            File(target, "FORK.md").writeText("forked from ${ctx.runDir.name} at ${Instant.now()}\nlabel: $label\n")
            ctx.log.append("session.snapshot", mapOf("fork" to target.absolutePath, "label" to label))
            return ToolResult("forked to ${target.name}")
        }
        return ToolResult("unknown action: $action", isError = true)
    }
}
