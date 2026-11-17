package com.example.data

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

data class FileDiffItem(
    val relativePath: String,
    val absolutePath: String,
    val turn: Int,
    val existedBefore: Boolean,
    val existsNow: Boolean,
    val addedLines: Int,
    val removedLines: Int,
    val diffText: String
)

data class UndoResult(
    val ok: Boolean,
    val turn: Int,
    val restoredFiles: List<String>,
    val message: String
)

class CheckpointManager(
    private val runId: String,
    private val runDir: File,
    private val workdir: File,
    private val db: RunDatabase
) {
    private val checkpointsRoot = File(runDir, "checkpoints")
    private val maxSnapshotBytes = 2L * 1024L * 1024L

    @Synchronized
    fun capturePreImage(turn: Int, targetFile: File, action: String) {
        val canonical = runCatching { targetFile.canonicalFile }.getOrElse { targetFile.absoluteFile }
        val targetPath = canonical.absolutePath
        if (db.checkpointDao().findSync(runId, turn, targetPath) != null) return

        val existedBefore = canonical.exists() && canonical.isFile
        val rel = relativeDisplayPath(canonical)
        val turnDir = File(checkpointsRoot, "turn-$turn").apply { mkdirs() }
        val snapFile = if (existedBefore && canonical.length() <= maxSnapshotBytes) {
            val hash = sha256Short(targetPath)
            File(turnDir, "${hash}_${canonical.name.take(48)}").also { dst ->
                runCatching { canonical.copyTo(dst, overwrite = true) }
            }
        } else {
            null
        }

        runCatching {
            db.checkpointDao().insertSync(
                CheckpointEntity(
                    runId = runId,
                    turn = turn,
                    targetPath = targetPath,
                    relativePath = rel,
                    existedBefore = existedBefore,
                    snapshotPath = snapFile?.absolutePath.orEmpty(),
                    action = action,
                    createdAt = Instant.now().toString()
                )
            )
        }
    }

    fun latestTurn(): Int? = runCatching { db.checkpointDao().latestTurnSync(runId) }.getOrNull()

    fun hasCheckpoints(): Boolean = latestTurn() != null

    fun sessionDiffs(): List<FileDiffItem> {
        val rows = runCatching { db.checkpointDao().listCheckpointsSync(runId) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()

        // Earliest checkpoint per targetPath represents the baseline before any edits in this session.
        val grouped = rows.groupBy { it.targetPath }
        return grouped.entries.map { (targetPath, entries) ->
            val baseline = entries.minByOrNull { it.turn } ?: entries.first()
            val latest = entries.maxByOrNull { it.turn } ?: entries.first()
            val currentFile = File(targetPath)
            val existsNow = currentFile.exists() && currentFile.isFile
            val beforeText = when {
                !baseline.existedBefore -> ""
                baseline.snapshotPath.isBlank() -> "(file >2MB or binary; snapshot omitted)"
                else -> runCatching {
                    val f = File(baseline.snapshotPath)
                    if (f.exists()) decodeTextOrMarker(f) else "(snapshot missing)"
                }.getOrDefault("(snapshot unreadable)")
            }
            val afterText = when {
                !existsNow -> ""
                currentFile.length() > maxSnapshotBytes -> "(file >2MB)"
                else -> runCatching { decodeTextOrMarker(currentFile) }.getOrDefault("(unreadable)")
            }
            val (added, removed, diff) = unifiedLineDiff(
                label = baseline.relativePath,
                before = beforeText,
                after = afterText,
                existedBefore = baseline.existedBefore,
                existsNow = existsNow
            )
            FileDiffItem(
                relativePath = baseline.relativePath,
                absolutePath = targetPath,
                turn = latest.turn,
                existedBefore = baseline.existedBefore,
                existsNow = existsNow,
                addedLines = added,
                removedLines = removed,
                diffText = diff
            )
        }.sortedByDescending { it.turn }
    }

    @Synchronized
    fun undoLastTurn(): UndoResult {
        val turn = latestTurn()
            ?: return UndoResult(ok = false, turn = 0, restoredFiles = emptyList(), message = "No checkpoints available to undo.")
        val rows = db.checkpointDao().listForTurnSync(runId, turn)
        if (rows.isEmpty()) {
            db.checkpointDao().deleteTurnSync(runId, turn)
            return UndoResult(ok = false, turn = turn, restoredFiles = emptyList(), message = "Checkpoint turn-$turn was empty.")
        }

        val restored = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (row in rows) {
            val target = File(row.targetPath)
            runCatching {
                if (!row.existedBefore) {
                    if (target.exists()) target.delete()
                } else {
                    val snap = File(row.snapshotPath)
                    if (row.snapshotPath.isBlank() || !snap.exists()) {
                        errors.add("${row.relativePath}: snapshot unavailable (>2MB or missing)")
                        return@runCatching
                    }
                    target.parentFile?.mkdirs()
                    snap.copyTo(target, overwrite = true)
                }
                restored.add(row.relativePath)
            }.onFailure { err ->
                errors.add("${row.relativePath}: ${err.message ?: err::class.simpleName}")
            }
        }

        db.checkpointDao().deleteTurnSync(runId, turn)
        runCatching { File(checkpointsRoot, "turn-$turn").deleteRecursively() }

        val msg = buildString {
            append("Reverted turn $turn (${restored.size} file(s) restored)")
            if (restored.isNotEmpty()) append(": ${restored.joinToString(", ")}")
            if (errors.isNotEmpty()) append(" | skipped: ${errors.joinToString("; ")}")
        }
        return UndoResult(
            ok = restored.isNotEmpty() && errors.isEmpty(),
            turn = turn,
            restoredFiles = restored,
            message = msg
        )
    }

    private fun relativeDisplayPath(file: File): String {
        val workRoot = runCatching { workdir.canonicalPath }.getOrElse { workdir.absolutePath }
        val abs = file.absolutePath
        return if (abs == workRoot) "."
        else if (abs.startsWith("$workRoot/")) abs.removePrefix("$workRoot/")
        else abs
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun decodeTextOrMarker(file: File): String {
        val bytes = file.readBytes()
        val probe = bytes.take(min(bytes.size, 2048))
        if (probe.any { it == 0.toByte() }) return "(binary ${bytes.size} bytes)"
        return String(bytes, Charsets.UTF_8)
    }

    private fun unifiedLineDiff(
        label: String,
        before: String,
        after: String,
        existedBefore: Boolean,
        existsNow: Boolean
    ): Triple<Int, Int, String> {
        if (before == after && existedBefore == existsNow) {
            return Triple(0, 0, "--- a/$label\n+++ b/$label\n(no changes)")
        }
        val a = if (before.isEmpty() && !existedBefore) emptyList() else before.lines()
        val b = if (after.isEmpty() && !existsNow) emptyList() else after.lines()
        val maxProcess = 600
        if (a.size > maxProcess || b.size > maxProcess) {
            val out = "--- a/$label\n+++ b/$label\n@@ -1,${a.size} +1,${b.size} @@\n(large file: ${a.size} -> ${b.size} lines)"
            return Triple(max(0, b.size - a.size), max(0, a.size - b.size), out)
        }

        // LCS DP table for accurate line-by-line diff
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else max(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val ops = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops.add(' ' to a[i])
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops.add('-' to a[i])
                    removed++; i++
                }
                else -> {
                    ops.add('+' to b[j])
                    added++; j++
                }
            }
        }
        while (i < n) {
            ops.add('-' to a[i]); removed++; i++
        }
        while (j < m) {
            ops.add('+' to b[j]); added++; j++
        }

        val sb = StringBuilder()
        sb.appendLine("--- ${if (existedBefore) "a/$label" else "/dev/null"}")
        sb.appendLine("+++ ${if (existsNow) "b/$label" else "/dev/null"}")
        var idx = 0
        var renderedLines = 0
        while (idx < ops.size && renderedLines < 220) {
            if (ops[idx].first == ' ') {
                idx++
                continue
            }
            val start = max(0, idx - 2)
            var end = idx
            var gap = 0
            while (end < ops.size) {
                if (ops[end].first != ' ') {
                    gap = 0
                } else {
                    gap++
                    if (gap > 4) break
                }
                end++
            }
            val hunkEnd = min(ops.size, end)
            sb.appendLine("@@ hunk @@")
            for (k in start until hunkEnd) {
                val (op, line) = ops[k]
                sb.append(op).append(line).append('\n')
                renderedLines++
                if (renderedLines >= 220) {
                    sb.appendLine("... (diff truncated)")
                    break
                }
            }
            idx = hunkEnd
        }
        return Triple(added, removed, sb.toString().trimEnd())
    }
}
