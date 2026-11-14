package com.example.scope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class Aggression(val wire: String) {
    READ("read"),
    ACTIVE("active"),
    DESTRUCTIVE("destructive")
}

data class ScopeDecision(
    val ok: Boolean,
    val reason: String? = null,
    val pinned: List<String> = emptyList(),
    val aggression: Aggression = Aggression.DESTRUCTIVE
)

class EngagementGate {
    private val reachedMap = ConcurrentHashMap<String, Int>()
    private val requestCounter = AtomicInteger(0)
    val defaultAggression: Aggression = Aggression.DESTRUCTIVE

    private val _hostsSnapshot = MutableStateFlow<Map<String, Int>>(emptyMap())
    val hostsSnapshot: StateFlow<Map<String, Int>> = _hostsSnapshot.asStateFlow()

    val requestsMade: Int
        get() = requestCounter.get()

    val reached: Map<String, Int>
        get() = reachedMap.toMap()

    suspend fun check(url: String? = null, host: String? = null): ScopeDecision {
        val hostname = when {
            !url.isNullOrBlank() -> {
                val parsed = runCatching { URI(url) }.getOrNull()
                    ?: return ScopeDecision(ok = false, reason = "malformed url", aggression = defaultAggression)
                val scheme = parsed.scheme?.lowercase().orEmpty()
                if (scheme != "http" && scheme != "https") {
                    return ScopeDecision(ok = false, reason = "protocol not allowed: $scheme:", aggression = defaultAggression)
                }
                parsed.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }
                    ?: return ScopeDecision(ok = false, reason = "malformed url host", aggression = defaultAggression)
            }
            !host.isNullOrBlank() -> parseHostWithoutPort(host)
            else -> return ScopeDecision(ok = false, reason = "no target given", aggression = defaultAggression)
        }

        requestCounter.incrementAndGet()
        reachedMap.merge(hostname, 1) { a, b -> a + b }
        _hostsSnapshot.value = reachedMap.toMap()

        val pinned = withContext(Dispatchers.IO) {
            runCatching {
                InetAddress.getAllByName(hostname).mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
        }
        return ScopeDecision(ok = true, pinned = pinned, aggression = defaultAggression)
    }

    fun recordFailure(hostname: String) = Unit
    fun recordSuccess(hostname: String) = Unit

    private fun parseHostWithoutPort(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 1) return trimmed.substring(1, end)
        }
        val firstColon = trimmed.indexOf(':')
        val lastColon = trimmed.lastIndexOf(':')
        return if (firstColon != -1 && firstColon == lastColon && trimmed.substring(lastColon + 1).all { it.isDigit() }) {
            trimmed.substring(0, lastColon)
        } else {
            trimmed.removePrefix("[").removeSuffix("]")
        }
    }
}
