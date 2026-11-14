package com.example.data

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ArtifactRef(
    val id: String,
    val kind: String,
    val label: String,
    val bytes: Long,
    val lines: Int,
    val preview: String,
    val path: String
)

class ArtifactStore(
    runDir: File,
    private val previewBytes: Int = 1024
) {
    val dir: File = File(runDir, "artifacts").apply { mkdirs() }
    private val index = ConcurrentHashMap<String, ArtifactRef>()

    fun put(content: String, kind: String, label: String): ArtifactRef =
        putBytes(content.toByteArray(Charsets.UTF_8), kind, label)

    fun putBytes(bytes: ByteArray, kind: String, label: String): ArtifactRef {
        val id = sha256Hex(bytes).take(16)
        index[id]?.let { return it }

        val file = File(dir, id)
        if (!file.exists()) {
            file.writeBytes(bytes)
        }
        val text = bytes.toString(Charsets.UTF_8)
        val ref = ArtifactRef(
            id = id,
            kind = kind,
            label = label,
            bytes = bytes.size.toLong(),
            lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1,
            preview = text.take(previewBytes),
            path = file.absolutePath
        )
        index[id] = ref
        return ref
    }

    fun get(id: String): ArtifactRef? {
        val cleanId = id.trim()
        if (cleanId.isEmpty() || cleanId == "." || cleanId == ".." || cleanId.contains('/') || cleanId.contains('\\')) {
            return null
        }
        index[cleanId]?.let { return it }
        val file = File(dir, cleanId)
        val baseCanonical = runCatching { dir.canonicalFile.toPath() }.getOrNull() ?: return null
        val targetCanonical = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return null
        if (!targetCanonical.startsWith(baseCanonical) || targetCanonical == baseCanonical) return null
        if (!file.exists() || !file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        val ref = ArtifactRef(
            id = cleanId,
            kind = "unknown",
            label = cleanId,
            bytes = file.length(),
            lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1,
            preview = text.take(previewBytes),
            path = file.absolutePath
        )
        index[cleanId] = ref
        return ref
    }

    fun read(id: String): String? {
        val ref = get(id) ?: return null
        val file = File(ref.path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun slice(id: String, offsetBytes: Int, lengthBytes: Int): String? {
        val ref = get(id) ?: return null
        val file = File(ref.path)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val start = offsetBytes.coerceIn(0, bytes.size)
        val end = (start + lengthBytes.coerceAtLeast(0)).coerceIn(start, bytes.size)
        return bytes.copyOfRange(start, end).toString(Charsets.UTF_8)
    }

    fun grep(
        id: String,
        pattern: String,
        context: Int = 0,
        ignoreCase: Boolean = true,
        max: Int = 200
    ): List<String>? {
        val text = read(id) ?: return null
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = Regex(pattern, options)
        val lines = text.split('\n')
        val hits = mutableListOf<String>()
        for (i in lines.indices) {
            if (hits.size >= max) break
            if (!regex.containsMatchIn(lines[i])) continue
            val from = (i - context).coerceAtLeast(0)
            val to = (i + context).coerceAtMost(lines.lastIndex)
            val snippet = lines.subList(from, to + 1).joinToString("\n")
            hits.add("${from + 1}-${to + 1}: $snippet")
        }
        return hits
    }

    fun list(): List<ArtifactRef> {
        if (index.isEmpty() && dir.exists()) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name != "index.jsonl") get(f.name)
            }
        }
        return index.values.toList()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
