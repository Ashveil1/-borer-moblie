package com.example.data

import com.example.provider.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

object SessionFiles {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun saveMessages(runDir: File, messages: List<ChatMessage>) {
        runCatching {
            runDir.mkdirs()
            File(runDir, "messages.json").writeText(json.encodeToString(messages))
        }
    }

    fun loadMessages(runDir: File): List<ChatMessage> {
        val file = File(runDir, "messages.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatMessage>>(file.readText())
        }.getOrDefault(emptyList())
    }

    fun saveSummary(runDir: File, summary: String) {
        runCatching {
            runDir.mkdirs()
            File(runDir, "summary.md").writeText(summary.ifBlank { "(model produced no summary)" })
        }
    }

    fun loadSummary(runDir: File): String? {
        val file = File(runDir, "summary.md")
        if (!file.exists()) return null
        val text = file.readText().trim()
        return text.takeIf { it.isNotBlank() && it != "(model produced no summary)" }
    }

    fun listReports(runDir: File): List<File> {
        val dir = File(runDir, "reports")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun ageOf(iso: String, nowMs: Long = System.currentTimeMillis()): String {
        val parsed = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(nowMs)
        val seconds = ((nowMs - parsed) / 1000L).coerceAtLeast(0L)
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h"
        return "${hours / 24}d"
    }
}
