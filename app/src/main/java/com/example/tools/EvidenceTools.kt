package com.example.tools

import com.example.data.NoteKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class CvssScore(
    val base: Double,
    val severity: String,
    val vector: String
)

object Cvss31Calculator {
    fun score(vectorRaw: String): CvssScore? {
        val vector = vectorRaw.trim()
        if (!vector.startsWith("CVSS:3.1/")) return null
        val m = vector.removePrefix("CVSS:3.1/")
            .split('/')
            .mapNotNull { part ->
                val idx = part.indexOf(':')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }.toMap()

        val av = mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.2)[m["AV"]] ?: return null
        val ac = mapOf("L" to 0.77, "H" to 0.44)[m["AC"]] ?: return null
        val scopeChanged = m["S"] == "C"
        val prMap = if (scopeChanged) mapOf("N" to 0.85, "L" to 0.68, "H" to 0.5) else mapOf("N" to 0.85, "L" to 0.62, "H" to 0.27)
        val pr = prMap[m["PR"]] ?: return null
        val ui = mapOf("N" to 0.85, "R" to 0.62)[m["UI"]] ?: return null
        val cia = mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)
        val c = cia[m["C"]] ?: return null
        val i = cia[m["I"]] ?: return null
        val a = cia[m["A"]] ?: return null

        val issBase = 1.0 - (1.0 - c) * (1.0 - i) * (1.0 - a)
        val iss = if (scopeChanged) {
            7.52 * (issBase - 0.029) - 3.25 * (issBase - 0.02).pow(15)
        } else {
            6.42 * issBase
        }
        val exploitability = 8.22 * av * ac * pr * ui

        fun roundup(value: Double): Double {
            val intVal = (value * 100_000.0).roundToInt()
            return if (intVal % 10_000 == 0) intVal / 100_000.0 else ((intVal / 10_000) + 1) / 10.0
        }

        val base = when {
            iss <= 0.0 -> 0.0
            scopeChanged -> roundup(min(1.08 * (iss + exploitability), 10.0))
            else -> roundup(min(iss + exploitability, 10.0))
        }
        val severity = when {
            base == 0.0 -> "none"
            base < 4.0 -> "low"
            base < 7.0 -> "medium"
            base < 9.0 -> "high"
            else -> "critical"
        }
        return CvssScore(base = base, severity = severity, vector = vector)
    }
}

val verifyTool = object : Tool {
    override val name = "verify"
    override val description =
        "Re-run a recorded proof-of-concept and measure whether it is reproducible. Runs N times, hashes each output, checks your expected pattern, and reports a pass/fail verdict."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "command" to schemaProp("string", "shell command that demonstrates the issue"),
            "runs" to schemaProp("number", "default 3"),
            "expect" to schemaProp("string", "regex that must match in a successful run"),
            "must_pass" to schemaProp("number", "minimum successful runs (default 2)"),
            "timeout_ms" to schemaProp("number", "per-run timeout, default 60000"),
            "compare" to schemaProp("string", "stable | any (default any)")
        ),
        required = listOf("command")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (command.isBlank()) return@withContext ToolResult("error: command is required", isError = true)
        val runs = ((args["runs"] as? JsonPrimitive)?.intOrNull ?: ctx.settings.evidenceRecords).coerceIn(1, 10)
        val mustPass = ((args["must_pass"] as? JsonPrimitive)?.intOrNull ?: ((runs + 1) / 2).coerceAtLeast(1)).coerceIn(1, runs)
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.intOrNull ?: 60_000).coerceIn(500, 600_000).toLong()
        val expectPattern = (args["expect"] as? JsonPrimitive)?.contentOrNull
        val expectRegex = expectPattern?.let {
            runCatching { Regex(it) }.getOrElse { ex ->
                return@withContext ToolResult("error: invalid expect regex: ${ex.message}", isError = true)
            }
        }
        val stableRequired = (args["compare"] as? JsonPrimitive)?.contentOrNull == "stable"

        data class RunEntry(val idx: Int, val exit: Int?, val ms: Long, val output: String, val hash: String, val matched: Boolean)

        val env = buildMap {
            put("BORER_RUN_DIR", ctx.runDir.absolutePath)
            put("BORER_WORKDIR", ctx.workdir.absolutePath)
            ctx.proxyUrl?.let { px ->
                put("HTTP_PROXY", px)
                put("HTTPS_PROXY", px)
                put("http_proxy", px)
                put("https_proxy", px)
            }
        }

        val results = mutableListOf<RunEntry>()
        for (i in 1..runs) {
            if (ctx.isCancelled()) break
            val started = System.currentTimeMillis()
            val execRes = ctx.shellExecutor(command, ctx.workdir, env, timeoutMs, null)
            val exit = execRes.exitCode
            val output = if (execRes.timedOut) "[timeout after ${timeoutMs}ms]\n${execRes.output}" else execRes.output.take(512 * 1024)
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(output.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            val matched = if (expectRegex != null) expectRegex.containsMatchIn(output) else exit == 0
            results.add(RunEntry(i, exit, System.currentTimeMillis() - started, output, hash, matched))
        }

        val passed = results.count { it.matched }
        val uniqueHashes = results.map { it.hash }.toSet().size
        val verdict = if (passed >= mustPass && (!stableRequired || uniqueHashes == 1)) "PASS" else "FAIL"

        ctx.log.append(
            "tool.result",
            mapOf(
                "name" to "verify",
                "verdict" to verdict,
                "passed" to passed,
                "runs" to results.size,
                "uniqueHashes" to uniqueHashes,
                "command" to command.take(400)
            )
        )

        if (verdict == "PASS") {
            val cmdHash = MessageDigest.getInstance("SHA-256")
                .digest(command.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            ctx.store.putNote(
                kind = NoteKind.FACT,
                key = "verified:$cmdHash",
                body = "verified PoC ($passed/${results.size} runs)\n\n```\n$command\n```\n\nexpect: ${expectPattern ?: "(exit 0)"}",
                tags = listOf("verified")
            )
        }

        val previewChars = if (ctx.settings.bulkEvidence) 4000 else 800
        val body = results.joinToString("\n---\n") { r ->
            "run ${r.idx}: exit=${r.exit} ${r.ms}ms hash=${r.hash} matched=${r.matched}\n${r.output.take(previewChars)}"
        }
        val (spilled, artId) = spill(ctx, body, "verify", command.take(60))
        val msg = buildString {
            appendLine("verdict: $verdict · $passed/${results.size} matched · distinct outputs: $uniqueHashes")
            appendLine(
                if (verdict == "PASS") "reproducible: safe to report"
                else "not reproducible: do not report this as a finding yet — tighten the PoC or treat it as a false positive"
            )
            appendLine("--- runs ---")
            append(spilled)
        }
        ToolResult(content = msg, isError = verdict != "PASS", artifactId = artId)
    }
}

val reportTool = object : Tool {
    override val name = "report"
    override val description =
        "Write a structured finding. Validates the fields, computes the CVSS 3.1 base score when you pass a v3.1 vector, and writes reports/<slug>.md."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "write | list (default write)"),
            "title" to schemaProp("string"),
            "asset" to schemaProp("string", "affected host / endpoint"),
            "severity" to schemaProp("string", "critical | high | medium | low | informational"),
            "cvss_vector" to schemaProp("string", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N"),
            "summary" to schemaProp("string"),
            "steps" to schemaProp("array", "reproduction steps", "string"),
            "impact" to schemaProp("string"),
            "remediation" to schemaProp("string"),
            "evidence" to schemaProp("array", "artifact ids, note ids, file paths", "string"),
            "cwe" to schemaProp("string"),
            "references" to schemaProp("array", "references", "string")
        ),
        required = listOf("title")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if ((args["action"] as? JsonPrimitive)?.contentOrNull == "list") {
            val rows = ctx.store.listFindings()
            if (rows.isEmpty()) return ToolResult("no findings recorded yet")
            return ToolResult(
                rows.joinToString("\n") { f ->
                    "#${f.id} [${f.status}] ${f.severity} ${f.cvss ?: "-"} ${f.asset} — ${f.title}"
                }
            )
        }

        val title = (args["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult("error: title is required", isError = true)

        val vector = (args["cvss_vector"] as? JsonPrimitive)?.contentOrNull?.trim()
        val cvss = if (!vector.isNullOrEmpty()) {
            Cvss31Calculator.score(vector)
                ?: return ToolResult(
                    "error: invalid CVSS 3.1 vector \"$vector\". expected CVSS:3.1/AV:.../AC:.../PR:.../UI:.../S:.../C:.../I:.../A:...",
                    isError = true
                )
        } else null
        val rawSeverity = (args["severity"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val severity = (rawSeverity ?: cvss?.severity ?: "medium").lowercase()
        val asset = (args["asset"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val cwe = (args["cwe"] as? JsonPrimitive)?.contentOrNull
        val summary = (args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val impact = (args["impact"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val remediation = (args["remediation"] as? JsonPrimitive)?.contentOrNull
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.ifEmpty { null }
            ?: listOf("(not provided)")
        val evidence = (args["evidence"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.ifEmpty { null }
            ?: listOf("(none)")
        val references = (args["references"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "finding" }
        val note = ctx.store.putNote(
            kind = NoteKind.FINDING,
            key = "$slug-${System.currentTimeMillis()}",
            body = "$summary\n\nimpact: $impact",
            tags = listOfNotNull(severity, asset.takeIf { it.isNotBlank() })
        )
        val finding = ctx.store.addFinding(
            title = title,
            severity = severity,
            cvss = cvss?.base,
            cvssVector = cvss?.vector,
            asset = asset,
            cwe = cwe,
            noteId = note.id
        )

        val md = buildString {
            appendLine("# $title")
            appendLine()
            append("**Severity:** $severity")
            if (cvss != null) append(" · CVSS 3.1 ${"%.1f".format(cvss.base)} (${cvss.vector})")
            appendLine()
            appendLine("**Asset:** ${asset.ifBlank { "n/a" }}")
            if (!cwe.isNullOrBlank()) appendLine("**CWE:** $cwe")
            appendLine("**Date:** ${LocalDate.now()}")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine(summary)
            appendLine()
            appendLine("## Steps to reproduce")
            appendLine()
            steps.forEachIndexed { idx, s -> appendLine("${idx + 1}. $s") }
            appendLine()
            appendLine("## Impact")
            appendLine()
            appendLine(impact)
            appendLine()
            appendLine("## Evidence")
            appendLine()
            evidence.forEach { appendLine("- `$it`") }
            if (!remediation.isNullOrBlank()) {
                appendLine()
                appendLine("## Remediation")
                appendLine()
                appendLine(remediation)
            }
            if (references.isNotEmpty()) {
                appendLine()
                appendLine("## References")
                appendLine()
                references.forEach { appendLine("- $it") }
            }
            appendLine()
            append("<sub>finding #${finding.id} · note #${note.id} · generated by borer</sub>")
        }

        val reportsDir = File(ctx.runDir, "reports").apply { mkdirs() }
        val file = File(reportsDir, "${finding.id}-$slug.md")
        file.writeText(md)
        ctx.store.updateFindingReport(finding.id, file.absolutePath, "verified")
        ctx.log.append("note.put", mapOf("finding" to finding.id, "path" to file.absolutePath, "severity" to severity, "cvss" to cvss?.base))

        return ToolResult("finding #${finding.id} written: ${file.name}\n\n${md.take(1500)}")
    }
}
