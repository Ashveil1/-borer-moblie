package com.example.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

private const val MAX_READ_BYTES = 2 * 1024 * 1024
private val SKIP_WALK_DIRS = setOf(".git", "node_modules", ".gradle", "build", ".idea", "__pycache__", ".venv", "venv")
private val BINARY_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "so", "jar", "apk", "dex", "zip", "gz", "tar", "pdf", "bin", "db", "sqlite", "woff", "woff2", "ttf")

private fun resolveSafePath(ctx: ToolContext, path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else File(ctx.workdir, path)
}

val readTool = object : Tool {
    override val name = "read"
    override val description = "Read a file with line numbers. Use offset/limit for big files."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to schemaProp("string"),
            "offset" to schemaProp("number", "1-based first line (default 1)"),
            "limit" to schemaProp("number", "max lines to return (default 2000)")
        ),
        required = listOf("path")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args["path"].asStr().orEmpty()
        if (rawPath.isBlank()) return ToolResult("error: path is required", isError = true)
        val file = resolveSafePath(ctx, rawPath)
        if (!file.exists()) return ToolResult("error: no such file: ${file.path}", isError = true)
        if (file.isDirectory) return ToolResult("error: ${file.path} is a directory; use ls", isError = true)
        if (file.length() > MAX_READ_BYTES) {
            return ToolResult("error: ${file.path} is ${file.length()} bytes; use recall or a smaller slice", isError = true)
        }
        val lines = file.readText().split('\n')
        val offset = (args["offset"].asInt() ?: 1).coerceAtLeast(1)
        val limit = (args["limit"].asInt() ?: 2000).coerceAtLeast(1)
        val startIdx = (offset - 1).coerceAtMost(lines.size)
        val endIdx = (startIdx + limit).coerceAtMost(lines.size)
        val slice = lines.subList(startIdx, endIdx)
        val body = slice.mapIndexed { idx, line -> "${offset + idx}\t$line" }.joinToString("\n")
        val note = if (endIdx < lines.size) {
            "\n\n[truncated view: ${lines.size} lines total, showing $offset-${offset + slice.size - 1}]"
        } else ""
        val (spilled, artId) = spill(ctx, body + note, "file", file.path)
        return ToolResult(spilled, artifactId = artId)
    }
}

val writeTool = object : Tool {
    override val name = "write"
    override val description = "Create or overwrite a file with the given content. Creates parent directories."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to schemaProp("string"),
            "content" to schemaProp("string")
        ),
        required = listOf("path", "content")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args["path"].asStr().orEmpty()
        if (rawPath.isBlank()) return ToolResult("error: path is required", isError = true)
        val file = resolveSafePath(ctx, rawPath)
        if (file.isDirectory) return ToolResult("error: ${file.path} is a directory", isError = true)
        ctx.checkpoints?.capturePreImage(ctx.currentTurnProvider(), file, "write")
        file.parentFile?.mkdirs()
        file.writeText(args["content"].asStr().orEmpty())
        return ToolResult("wrote ${file.path} (${file.length()} bytes)")
    }
}

val editTool = object : Tool {
    override val name = "edit"
    override val description = "Replace exact text in a file. old_text must appear once unless all=true."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to schemaProp("string"),
            "old_text" to schemaProp("string"),
            "new_text" to schemaProp("string"),
            "all" to schemaProp("boolean")
        ),
        required = listOf("path", "old_text", "new_text")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args["path"].asStr().orEmpty()
        if (rawPath.isBlank()) return ToolResult("error: path is required", isError = true)
        val file = resolveSafePath(ctx, rawPath)
        if (!file.exists()) return ToolResult("error: no such file: ${file.path}", isError = true)
        if (file.isDirectory) return ToolResult("error: ${file.path} is a directory", isError = true)
        val before = file.readText()
        val oldText = args["old_text"].asStr().orEmpty()
        val newText = args["new_text"].asStr().orEmpty()
        val replaceAll = args["all"].asBool() == true
        val count = countNonOverlapping(before, oldText)
        if (count == 0) return ToolResult("error: old_text not found", isError = true)
        if (count > 1 && !replaceAll) {
            return ToolResult("error: old_text appears $count times; add more context or pass all=true", isError = true)
        }
        val after = if (replaceAll) before.replace(oldText, newText) else before.replaceFirst(oldText, newText)
        ctx.checkpoints?.capturePreImage(ctx.currentTurnProvider(), file, "edit")
        file.writeText(after)
        return ToolResult("edited ${file.path} ($count replacement${if (count == 1) "" else "s"})")
    }
}

val lsTool = object : Tool {
    override val name = "ls"
    override val description = "List a directory. Set deep=true for a recursive tree (depth 2)."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "path" to schemaProp("string"),
            "all" to schemaProp("boolean"),
            "deep" to schemaProp("boolean")
        )
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val dir = resolveSafePath(ctx, args["path"].asStr() ?: ".")
        if (!dir.exists()) return ToolResult("error: no such directory: ${dir.path}", isError = true)
        if (dir.isFile) return ToolResult("${dir.name} (${dir.length()} bytes)")
        val showAll = args["all"].asBool() == true
        val depth = if (args["deep"].asBool() == true) 2 else 0
        val out = mutableListOf<String>()
        fun walk(cur: File, prefix: String, remDepth: Int) {
            if (out.size >= 400) return
            val entries = cur.listFiles()?.sortedBy { it.name } ?: return
            for (e in entries) {
                if (!showAll && e.name.startsWith(".")) continue
                out.add("$prefix${e.name}${if (e.isDirectory) "/" else ""}")
                if (e.isDirectory && remDepth > 0) walk(e, "$prefix${e.name}/", remDepth - 1)
                if (out.size >= 400) return
            }
        }
        walk(dir, "", depth)
        val rel = dir.relativeToOrSelf(ctx.workdir).path.ifBlank { "." }
        val text = "$rel/\n${out.joinToString("\n").ifBlank { "(empty)" }}"
        val (spilled, artId) = spill(ctx, text, "listing", rel)
        return ToolResult(spilled, artifactId = artId)
    }
}

val globTool = object : Tool {
    override val name = "glob"
    override val description = "Find files by glob pattern (e.g. \"**/*.txt\"), newest first."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "pattern" to schemaProp("string"),
            "path" to schemaProp("string"),
            "limit" to schemaProp("number")
        ),
        required = listOf("pattern")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val root = resolveSafePath(ctx, args["path"].asStr() ?: ".")
        if (!root.exists()) return ToolResult("error: no such directory: ${root.path}", isError = true)
        val pattern = args["pattern"].asStr().orEmpty()
        if (pattern.isBlank()) return ToolResult("error: pattern is required", isError = true)
        val limit = (args["limit"].asInt() ?: 200).coerceAtLeast(1)
        val re = globToRegex(pattern)
        val files = root.walkTopDown()
            .onEnter { dir -> !ctx.isCancelled() && dir.name !in SKIP_WALK_DIRS }
            .filter { !ctx.isCancelled() && it.isFile && re.matches(it.relativeTo(root).path.replace(File.separatorChar, '/')) }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { it.path }
            .toList()
        return ToolResult(if (files.isEmpty()) "no files matched $pattern" else files.joinToString("\n"))
    }
}

val grepTool = object : Tool {
    override val name = "grep"
    override val description = "Search file contents with a regular expression. Returns file:line matches."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "pattern" to schemaProp("string"),
            "path" to schemaProp("string"),
            "glob" to schemaProp("string"),
            "ignore_case" to schemaProp("boolean"),
            "context" to schemaProp("number"),
            "limit" to schemaProp("number")
        ),
        required = listOf("pattern")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val target = resolveSafePath(ctx, args["path"].asStr() ?: ".")
        if (!target.exists()) return ToolResult("error: no such path: ${target.path}", isError = true)
        val pattern = args["pattern"].asStr().orEmpty()
        if (pattern.isBlank()) return ToolResult("error: pattern is required", isError = true)
        val ignoreCase = args["ignore_case"].asBool() == true
        val regex = runCatching {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrElse { return ToolResult("error: invalid regex: ${it.message}", isError = true) }
        val fileGlob = args["glob"].asStr()?.let { globToRegex(it) }
        val limit = (args["limit"].asInt() ?: 200).coerceAtLeast(1)
        val contextLines = (args["context"].asInt() ?: 0).coerceAtLeast(0)
        val hits = mutableListOf<String>()

        fun scan(f: File) {
            if (ctx.isCancelled() || hits.size >= limit || !f.isFile || f.length() > MAX_READ_BYTES) return
            if (f.extension.lowercase() in BINARY_EXTENSIONS) return
            val text = runCatching { f.readText() }.getOrNull() ?: return
            if (text.contains('\u0000')) return
            val lines = text.split('\n')
            for (i in lines.indices) {
                if (hits.size >= limit || ctx.isCancelled()) break
                if (!regex.containsMatchIn(lines[i])) continue
                val from = (i - contextLines).coerceAtLeast(0)
                val to = (i + contextLines).coerceAtMost(lines.lastIndex)
                for (j in from..to) {
                    hits.add("${f.path}:${j + 1}:${if (j == i) "*" else " "} ${lines[j].take(500)}")
                }
            }
        }

        if (target.isFile) {
            scan(target)
        } else {
            target.walkTopDown()
                .onEnter { dir -> !ctx.isCancelled() && dir.name !in SKIP_WALK_DIRS }
                .filter { !ctx.isCancelled() && hits.size < limit && it.isFile && (fileGlob == null || fileGlob.matches(it.name)) }
                .forEach(::scan)
        }

        if (hits.isEmpty()) return ToolResult("no matches for /$pattern/ in ${target.path}")
        val (spilled, artId) = spill(ctx, hits.joinToString("\n"), "grep", pattern)
        return ToolResult(spilled, artifactId = artId)
    }
}

private fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                sb.append(".*")
                i++
                if (i + 1 < pattern.length && pattern[i + 1] == '/') i++
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c in ".+^$()[]{}|\\" -> sb.append("\\").append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append("$")
    return Regex(sb.toString())
}

val fsTools = listOf(readTool, writeTool, editTool, lsTool, globTool, grepTool)

private fun countNonOverlapping(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = 0
    while (true) {
        val found = haystack.indexOf(needle, idx)
        if (found < 0) break
        count++
        idx = found + needle.length
    }
    return count
}

private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
