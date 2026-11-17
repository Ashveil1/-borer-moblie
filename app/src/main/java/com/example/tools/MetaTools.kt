package com.example.tools

import com.example.data.NoteKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val notesTool = object : Tool {
    override val name = "notes"
    override val description =
        "Your persistent structured knowledge base. Entries survive context compaction, are searchable, and can link to each other."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "put | get | query | delete | digest"),
            "kind" to schemaProp("string", "fact | todo | preference | project | reference | finding | asset | endpoint | param | hypothesis | credential | blocked"),
            "key" to schemaProp("string", "short unique identifier within the kind"),
            "body" to schemaProp("string", "markdown body"),
            "tags" to schemaProp("array", "tags", "string"),
            "links" to schemaProp("array", "note ids", "number"),
            "id" to schemaProp("number", "note id for get/delete"),
            "text" to schemaProp("string", "search query for action=query"),
            "limit" to schemaProp("number")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        return when ((args["action"] as? JsonPrimitive)?.contentOrNull ?: "query") {
            "put" -> {
                val kind = NoteKind.fromWire((args["kind"] as? JsonPrimitive)?.contentOrNull)
                val key = (args["key"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (key.isEmpty()) return ToolResult("error: key is required for put", isError = true)
                val body = (args["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val tags = (args["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val links = (args["links"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
                val note = ctx.store.putNote(kind, key, body, tags, links)
                ctx.log.append("note.put", mapOf("id" to note.id, "kind" to note.kind.wire, "key" to note.key))
                ctx.log.append("note.update", mapOf("id" to note.id, "bytes" to note.body.length))
                ToolResult("note #${note.id} saved (${note.kind.wire}: ${note.key}, ${note.body.length} bytes)")
            }
            "get" -> {
                val id = (args["id"] as? JsonPrimitive)?.longOrNull ?: -1L
                val note = ctx.store.getNote(id) ?: return ToolResult("error: no note #$id", isError = true)
                ToolResult(
                    "#${note.id} [${note.kind.wire}] ${note.key}\nupdated ${note.updated}\ntags ${note.tags.joinToString(",").ifBlank { "-" }} links ${note.links.joinToString(",").ifBlank { "-" }}\n\n${note.body}"
                )
            }
            "delete" -> {
                val id = (args["id"] as? JsonPrimitive)?.longOrNull ?: -1L
                val ok = ctx.store.deleteNote(id)
                ToolResult(if (ok) "deleted note #$id" else "error: no note #$id", isError = !ok)
            }
            "digest" -> ToolResult(ctx.store.digest())
            else -> {
                val kindStr = (args["kind"] as? JsonPrimitive)?.contentOrNull
                val kind = kindStr?.let { NoteKind.fromWire(it) }
                val text = (args["text"] as? JsonPrimitive)?.contentOrNull
                val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 50).coerceIn(1, 500)
                val notes = ctx.store.queryNotes(kind = kind, text = text, limit = limit)
                if (notes.isEmpty()) return ToolResult("no notes matched")
                val rendered = notes.joinToString("\n\n") { n ->
                    "#${n.id} [${n.kind.wire}] ${n.key} (${n.body.length}b, ${n.updated.take(19)})\n${n.body.take(400)}"
                }
                val (spilled, artId) = spill(ctx, rendered, "notes", text ?: kindStr ?: "query")
                ToolResult(spilled, artifactId = artId)
            }
        }
    }
}

val recallTool = object : Tool {
    override val name = "recall"
    override val description =
        "Page back any artifact (a tool result, file, http body...) that was too large for the context. Actions: list, read, head, tail, slice, grep."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "list | read | head | tail | slice | grep"),
            "id" to schemaProp("string", "artifact id"),
            "pattern" to schemaProp("string", "regex for action=grep"),
            "context" to schemaProp("number", "lines of context for grep"),
            "offset" to schemaProp("number", "byte offset for slice"),
            "length" to schemaProp("number", "bytes for slice, lines for head/tail"),
            "limit" to schemaProp("number", "max hits or entries")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull ?: "list"
        if (action == "list") {
            val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 50).coerceAtLeast(1)
            val refs = ctx.artifacts.list().takeLast(limit)
            if (refs.isEmpty()) return ToolResult("no artifacts yet")
            return ToolResult(
                refs.joinToString("\n") { r ->
                    "${r.id}  ${r.bytes.toString().padStart(8)}b  ${r.kind.padEnd(10)} ${r.label}"
                }
            )
        }
        val id = (args["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (id.isEmpty()) return ToolResult("error: id is required", isError = true)
        ctx.artifacts.get(id) ?: return ToolResult("error: unknown artifact $id", isError = true)

        return when (action) {
            "grep" -> {
                val pattern = (args["pattern"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val context = ((args["context"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
                val max = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 200).coerceAtLeast(1)
                val hits = runCatching { ctx.artifacts.grep(id, pattern, context = context, max = max) }
                    .getOrElse { return ToolResult("error: ${it.message}", isError = true) }
                    ?: return ToolResult("error: cannot read $id", isError = true)
                if (hits.isEmpty()) return ToolResult("no matches for $pattern in $id")
                val (spilled, artId) = spill(ctx, hits.joinToString("\n\n"), "recall", id)
                ToolResult(spilled, artifactId = artId)
            }
            "slice" -> {
                val offset = ((args["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
                val length = ((args["length"] as? JsonPrimitive)?.intOrNull ?: 2000).coerceAtLeast(1)
                val text = ctx.artifacts.slice(id, offset, length)
                if (text == null) ToolResult("error: cannot read $id", isError = true) else ToolResult(text)
            }
            else -> {
                val text = ctx.artifacts.read(id) ?: return ToolResult("error: cannot read $id", isError = true)
                if (action == "read") {
                    val (spilled, artId) = spill(ctx, text, "recall", id)
                    return ToolResult(spilled, artifactId = artId)
                }
                val lines = text.split('\n')
                val n = ((args["length"] as? JsonPrimitive)?.intOrNull ?: 200).coerceAtLeast(1)
                val view = if (action == "tail") lines.takeLast(n) else lines.take(n)
                val (spilled, artId) = spill(ctx, view.joinToString("\n"), "recall", id)
                ToolResult(spilled, artifactId = artId)
            }
        }
    }
}

val todoTool = object : Tool {
    override val name = "todo"
    override val description = "Manage a task list that survives compaction (add / list / toggle / clear)."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "add | list | toggle | clear"),
            "text" to schemaProp("string"),
            "id" to schemaProp("number")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull ?: "list"
        val note = ctx.store.queryNotes(kind = NoteKind.TODO, limit = 1).firstOrNull()
        val items = mutableListOf<Pair<Boolean, String>>()
        val regex = Regex("^\\d+\\.\\s*\\[( |x)]\\s*(.*)$")
        note?.body?.split('\n')?.forEach { line ->
            regex.matchEntire(line.trim())?.let { m ->
                items.add((m.groupValues[1] == "x") to m.groupValues[2])
            }
        }

        when (action) {
            "add" -> {
                val text = (args["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return ToolResult("error: text is required for add", isError = true)
                items.add(false to text)
            }
            "toggle" -> {
                val rawId = (args["id"] as? JsonPrimitive)?.intOrNull ?: 0
                val idx = rawId - 1
                if (idx !in items.indices) {
                    return ToolResult("error: todo #$rawId out of range (1..${items.size})", isError = true)
                }
                val cur = items[idx]
                items[idx] = (!cur.first) to cur.second
            }
            "clear" -> items.clear()
        }

        val body = items.mapIndexed { idx, (done, text) -> "${idx + 1}. [${if (done) "x" else " "}] $text" }.joinToString("\n")
        ctx.store.putNote(NoteKind.TODO, "current", body.ifBlank { "(empty)" })
        return ToolResult(body.ifBlank { "(empty)" })
    }
}

val scopeTool = object : Tool {
    override val name = "scope"
    override val description =
        "Read-only view of outbound network activity and hosts reached during the current session."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf("action" to schemaProp("string", "status")),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val hosts = ctx.gate.reached.entries.sortedByDescending { it.value }
        return ToolResult(
            buildList {
                add("mode: open — outbound requests tracked")
                add("requests made: ${ctx.gate.requestsMade}")
                if (hosts.isEmpty()) add("hosts reached: none yet")
                else {
                    add("hosts reached:")
                    hosts.forEach { (h, n) -> add("  $h ($n)") }
                }
            }.joinToString("\n")
        )
    }
}

val finishTool = object : Tool {
    override val name = "finish"
    override val description =
        "End an autonomous multi-step run. Call it when the objective is complete, when you have exhausted what your tools allow, or when the task is blocked."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "summary" to schemaProp("string", "markdown summary of what you did, outputs produced, and recommended next steps"),
            "outcome" to schemaProp("string", "done | found | exhausted | blocked | give_up")
        ),
        required = listOf("summary")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val summary = (args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val outcome = (args["outcome"] as? JsonPrimitive)?.contentOrNull ?: "exhausted"
        ctx.log.append("run.end", mapOf("outcome" to outcome, "summary" to summary))
        ctx.finish?.invoke(summary, outcome)
        return ToolResult("run finished ($outcome)")
    }
}

val metaTools = listOf(notesTool, recallTool, todoTool, scopeTool, finishTool)
