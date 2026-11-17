package com.example.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

private fun resolvePatchFile(ctx: ToolContext, rawPath: String): File {
    val clean = rawPath.trim().removePrefix("a/").removePrefix("b/")
    val candidate = File(clean)
    return if (candidate.isAbsolute) candidate else File(ctx.workdir, clean)
}

private data class PatchHunk(val lines: List<String>)

private sealed interface PatchFileOp {
    val path: String
    data class Add(override val path: String, val content: String) : PatchFileOp
    data class Delete(override val path: String) : PatchFileOp
    data class Update(override val path: String, val hunks: List<PatchHunk>) : PatchFileOp
}

private data class PreparedPatchWrite(
    val file: File,
    val relPath: String,
    val delete: Boolean,
    val newText: String,
    val deltaSummary: String
)

val applyPatchTool = object : Tool {
    override val name = "apply_patch"
    override val description =
        "Apply structured multi-file / multi-hunk edits atomically with automatic turn checkpointing. " +
            "Supports '*** Begin Patch' blocks ('*** Update File: path', '*** Add File: path', '*** Delete File: path' with '@@' context/hunk lines using ' ', '-', '+') " +
            "or standard unified diffs ('--- a/path', '+++ b/path', '@@ ...')."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "patch" to schemaProp("string", "Unified diff or '*** Begin Patch' envelope covering one or more files")
        ),
        required = listOf("patch")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPatch = args["patch"].asStr().orEmpty()
        if (rawPatch.isBlank()) return ToolResult("error: patch is required", isError = true)

        val ops = runCatching { parsePatch(rawPatch) }.getOrElse {
            return ToolResult("error: failed to parse patch: ${it.message}", isError = true)
        }
        if (ops.isEmpty()) {
            return ToolResult("error: no file operations found in patch", isError = true)
        }

        // Phase 1: Validate and compute all resulting contents before touching disk
        val prepared = mutableListOf<PreparedPatchWrite>()

        for (op in ops) {
            val file = resolvePatchFile(ctx, op.path)
            when (op) {
                is PatchFileOp.Add -> {
                    if (file.isDirectory) return ToolResult("error: ${op.path} is a directory", isError = true)
                    prepared.add(PreparedPatchWrite(file, op.path, false, op.content, "added ${op.content.lines().size} lines"))
                }
                is PatchFileOp.Delete -> {
                    if (!file.exists()) return ToolResult("error: cannot delete missing file ${op.path}", isError = true)
                    if (file.isDirectory) return ToolResult("error: ${op.path} is a directory", isError = true)
                    prepared.add(PreparedPatchWrite(file, op.path, true, "", "deleted"))
                }
                is PatchFileOp.Update -> {
                    if (!file.exists()) return ToolResult("error: no such file: ${op.path}", isError = true)
                    if (file.isDirectory) return ToolResult("error: ${op.path} is a directory", isError = true)
                    val current = file.readText()
                    val updated = applyHunksToText(current, op.hunks, op.path)
                        .getOrElse { return ToolResult("error in ${op.path}: ${it.message}", isError = true) }
                    val added = op.hunks.sumOf { h -> h.lines.count { it.startsWith("+") } }
                    val removed = op.hunks.sumOf { h -> h.lines.count { it.startsWith("-") } }
                    prepared.add(PreparedPatchWrite(file, op.path, false, updated, "+$added / -$removed lines"))
                }
            }
        }

        // Phase 2: Capture checkpoints and commit writes
        val turn = ctx.currentTurnProvider()
        for (item in prepared) {
            ctx.checkpoints?.capturePreImage(turn, item.file, "apply_patch")
            if (item.delete) {
                item.file.delete()
            } else {
                item.file.parentFile?.mkdirs()
                item.file.writeText(item.newText)
            }
        }

        val summary = prepared.joinToString("\n") { "- ${it.relPath}: ${it.deltaSummary}" }
        return ToolResult("Applied patch to ${prepared.size} file(s):\n$summary")
    }

    private fun parsePatch(raw: String): List<PatchFileOp> {
        val lines = raw.replace("\r\n", "\n").split('\n')
        val ops = mutableListOf<PatchFileOp>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("*** Begin Patch") || line.startsWith("*** End Patch") -> i++
                line.startsWith("*** Add File:") -> {
                    val path = line.removePrefix("*** Add File:").trim()
                    i++
                    val body = mutableListOf<String>()
                    while (i < lines.size && !lines[i].startsWith("*** ")) {
                        val l = lines[i]
                        body.add(if (l.startsWith("+")) l.substring(1) else l)
                        i++
                    }
                    ops.add(PatchFileOp.Add(path, body.joinToString("\n")))
                }
                line.startsWith("*** Delete File:") -> {
                    val path = line.removePrefix("*** Delete File:").trim()
                    ops.add(PatchFileOp.Delete(path))
                    i++
                }
                line.startsWith("*** Update File:") -> {
                    val path = line.removePrefix("*** Update File:").trim()
                    i++
                    if (i < lines.size && lines[i].startsWith("*** Move to:")) i++
                    val hunks = mutableListOf<PatchHunk>()
                    while (i < lines.size && !lines[i].startsWith("*** ")) {
                        if (lines[i].startsWith("@@")) {
                            i++
                            val hLines = mutableListOf<String>()
                            while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("*** ")) {
                                val hl = lines[i]
                                if (hl.startsWith("\\ No newline")) {
                                    i++; continue
                                }
                                if (hl.startsWith(" ") || hl.startsWith("+") || hl.startsWith("-")) {
                                    hLines.add(hl)
                                } else if (hl.isEmpty()) {
                                    hLines.add(" ")
                                }
                                i++
                            }
                            if (hLines.isNotEmpty()) hunks.add(PatchHunk(hLines))
                        } else {
                            i++
                        }
                    }
                    ops.add(PatchFileOp.Update(path, hunks))
                }
                line.startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ") -> {
                    val oldPath = line.removePrefix("--- ").trim().substringBefore('\t')
                    val newPath = lines[i + 1].removePrefix("+++ ").trim().substringBefore('\t')
                    i += 2
                    val hunks = mutableListOf<PatchHunk>()
                    while (i < lines.size && !lines[i].startsWith("--- ") && !lines[i].startsWith("*** ")) {
                        if (lines[i].startsWith("@@")) {
                            i++
                            val hLines = mutableListOf<String>()
                            while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("--- ") && !lines[i].startsWith("*** ")) {
                                val hl = lines[i]
                                if (hl.startsWith("\\ No newline")) {
                                    i++; continue
                                }
                                if (hl.startsWith(" ") || hl.startsWith("+") || hl.startsWith("-")) {
                                    hLines.add(hl)
                                }
                                i++
                            }
                            if (hLines.isNotEmpty()) hunks.add(PatchHunk(hLines))
                        } else {
                            i++
                        }
                    }
                    when {
                        newPath == "/dev/null" -> ops.add(PatchFileOp.Delete(oldPath))
                        oldPath == "/dev/null" -> {
                            val content = hunks.flatMap { it.lines }
                                .filter { it.startsWith("+") || it.startsWith(" ") }
                                .joinToString("\n") { it.substring(1) }
                            ops.add(PatchFileOp.Add(newPath, content))
                        }
                        else -> ops.add(PatchFileOp.Update(newPath, hunks))
                    }
                }
                else -> i++
            }
        }
        return ops
    }

    private fun applyHunksToText(source: String, hunks: List<PatchHunk>, path: String): Result<String> {
        if (hunks.isEmpty()) return Result.failure(IllegalArgumentException("no hunks for $path"))
        val hadTrailingNewline = source.endsWith("\n")
        val lines = source.split('\n').toMutableList()
        if (hadTrailingNewline && lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        var searchFrom = 0
        for ((hunkIdx, hunk) in hunks.withIndex()) {
            val expectedBefore = hunk.lines
                .filter { it.startsWith(" ") || it.startsWith("-") }
                .map { it.substring(1) }
            val replacement = hunk.lines
                .filter { it.startsWith(" ") || it.startsWith("+") }
                .map { it.substring(1) }

            if (expectedBefore.isEmpty()) {
                val insertPos = lines.size.coerceAtMost(searchFrom)
                lines.addAll(insertPos, replacement)
                searchFrom = insertPos + replacement.size
                continue
            }

            val matchIdx = findSublist(lines, expectedBefore, searchFrom)
                ?: findSublist(lines, expectedBefore, 0)
                ?: findSublistTrimmed(lines, expectedBefore, searchFrom)
                ?: findSublistTrimmed(lines, expectedBefore, 0)
                ?: return Result.failure(
                    IllegalStateException("hunk #${hunkIdx + 1} context not found in $path (looking for '${expectedBefore.firstOrNull().orEmpty().take(60)}')")
                )

            repeat(expectedBefore.size) { lines.removeAt(matchIdx) }
            lines.addAll(matchIdx, replacement)
            searchFrom = matchIdx + replacement.size
        }

        val joined = lines.joinToString("\n")
        return Result.success(if (hadTrailingNewline && joined.isNotEmpty()) "$joined\n" else joined)
    }

    private fun findSublist(haystack: List<String>, needle: List<String>, startIdx: Int): Int? {
        if (needle.size > haystack.size) return null
        val maxStart = haystack.size - needle.size
        for (i in startIdx.coerceAtLeast(0)..maxStart) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return null
    }

    private fun findSublistTrimmed(haystack: List<String>, needle: List<String>, startIdx: Int): Int? {
        if (needle.size > haystack.size) return null
        val maxStart = haystack.size - needle.size
        for (i in startIdx.coerceAtLeast(0)..maxStart) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j].trimEnd() != needle[j].trimEnd()) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return null
    }
}

val taskTool = object : Tool {
    override val name = "task"
    override val description =
        "Spawn an isolated subagent with a clean context window to explore, implement, or verify a focused subtask, returning a compact report back to the main session."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "role" to schemaProp("string", "explore (read-only) | worker (full tools) | verify (evidence check)"),
            "prompt" to schemaProp("string", "Self-contained instructions and target files/endpoints for the subagent"),
            "max_turns" to schemaProp("number", "Max autonomous turns for the subagent (default 15, max 30)")
        ),
        required = listOf("prompt")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.isSubagent) {
            return ToolResult("error: subagents cannot spawn nested subagents", isError = true)
        }
        val runner = ctx.subagentRunner
            ?: return ToolResult("error: subagent runner is unavailable in this context", isError = true)
        val role = (args["role"].asStr() ?: "explore").lowercase()
        if (role !in setOf("explore", "worker", "verify")) {
            return ToolResult("error: role must be explore, worker, or verify", isError = true)
        }
        val prompt = args["prompt"].asStr().orEmpty().trim()
        if (prompt.isBlank()) return ToolResult("error: prompt is required", isError = true)
        val maxTurns = (args["max_turns"].asInt() ?: 15).coerceIn(1, 30)
        return runner(role, prompt, maxTurns)
    }
}

val proposePlanTool = object : Tool {
    override val name = "propose_plan"
    override val description =
        "Submit a structured execution plan card to the user in Plan mode (or before major multi-file changes) so the user can review and approve execution with one tap."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("title", schemaProp("string", "Short plan title"))
                put("summary", schemaProp("string", "High-level approach and key constraints"))
                put("steps", schemaProp("array", "Ordered concrete action steps", itemType = "string"))
            }
        )
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("steps"))))
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args["title"].asStr().orEmpty().trim().ifBlank { "Execution Plan" }
        val summary = args["summary"].asStr().orEmpty().trim()
        val steps = (args["steps"] as? JsonArray)
            ?.mapNotNull { it.asStr()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        if (steps.isEmpty()) {
            return ToolResult("error: steps array must contain at least one step", isError = true)
        }
        ctx.onPlanProposal?.invoke(title, summary, steps)
        ctx.log.append("plan.proposed", mapOf("title" to title, "steps" to steps.size))
        return ToolResult(
            "Plan card submitted to user: '$title' (${steps.size} steps). Stop making tool calls and await user approval."
        )
    }
}

val harnessTools = listOf(applyPatchTool, taskTool, proposePlanTool)

private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
