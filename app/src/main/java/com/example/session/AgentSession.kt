package com.example.session

import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.SessionFiles
import com.example.provider.ChatMessage
import com.example.provider.CompletionRequest
import com.example.provider.FallbackClient
import com.example.provider.ProviderEvent
import com.example.provider.ToolCallRequest
import com.example.provider.Usage
import com.example.tools.ToolContext
import com.example.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

interface UiHooks {
    fun onTurn(turn: Int) {}
    fun onText(delta: String) {}
    fun onReasoning(delta: String) {}
    fun onToolStart(call: ToolCallRequest) {}
    fun onToolEnd(call: ToolCallRequest, ok: Boolean, ms: Long, preview: String) {}
    fun onUsage(usage: Usage, totals: Usage, contextTokens: Int) {}
    fun onInfo(text: String) {}
}

data class SessionDeps(
    val profile: ProviderProfile?,
    val client: FallbackClient,
    val contextWindow: Int,
    val tools: ToolRegistry,
    val ctx: ToolContext,
    val settingsProvider: () -> BorerSettings = { BorerSettings() },
    val log: EventLog,
    val extraInstructions: String? = null,
    val ui: UiHooks? = null,
    val shouldStop: () -> Boolean = { false },
    val initialMessages: List<ChatMessage> = emptyList(),
    val initialTotals: Usage = Usage(),
    val initialContextTokens: Int = 0
) {
    val settings: BorerSettings get() = settingsProvider()
}

data class RunResult(
    val summary: String,
    val outcome: String,
    val turns: Int,
    val usage: Usage,
    val messages: List<ChatMessage>
)

class AgentSession(private val deps: SessionDeps) {
    val messages: MutableList<ChatMessage> = mutableListOf()
    var totals: Usage = deps.initialTotals
        private set
    var contextTokens: Int = deps.initialContextTokens
        private set
    private var noProgressTurns = 0
    private var consecutiveErrors = 0
    private var lastDigestCount = 0
    private var finished = false
    private var finishSummary = ""
    private var finishOutcome = "exhausted"

    private val toolBytes: Int = deps.tools.defs().sumOf { it.name.length + it.description.length + it.parameters.toString().length + 32 }

    init {
        messages.add(ChatMessage(role = "system", content = buildSystemPrompt(deps)))
        if (deps.initialMessages.isNotEmpty()) {
            messages.addAll(deps.initialMessages.filter { it.role != "system" })
        }
        deps.ctx.finish = { summary, outcome ->
            finished = true
            finishSummary = summary
            finishOutcome = outcome ?: "exhausted"
        }
    }

    suspend fun run(userMessage: String, maxTurns: Int = 400): RunResult = withContext(Dispatchers.IO) {
        finished = false
        finishSummary = ""
        finishOutcome = "exhausted"
        consecutiveErrors = 0
        messages[0] = ChatMessage(role = "system", content = buildSystemPrompt(deps))
        messages.add(ChatMessage(role = "user", content = userMessage))
        deps.log.append("message", mapOf("role" to "user", "bytes" to userMessage.length))
        var turns = 0

        while (turns < maxTurns) {
            if (finished) break
            if (deps.shouldStop() || deps.ctx.isCancelled()) {
                deps.ui?.onInfo("stop signal received")
                break
            }
            turns++
            deps.ui?.onTurn(turns)
            deps.log.append("turn.start", mapOf("turn" to turns, "contextTokens" to contextTokens))

            val (text, reasoning, toolCalls) = completeTurn()
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage(role = "assistant", content = text, reasoning = reasoning.takeIf { it.isNotEmpty() }))
                deps.log.append("message", mapOf("role" to "assistant", "bytes" to text.length, "toolCalls" to 0))
                break
            }

            messages.add(
                ChatMessage(
                    role = "assistant",
                    content = text,
                    toolCalls = toolCalls,
                    reasoning = reasoning.takeIf { it.isNotEmpty() }
                )
            )
            deps.log.append(
                "message",
                mapOf("role" to "assistant", "bytes" to text.length, "toolCalls" to toolCalls.joinToString(",") { it.name })
            )

            var turnErrors = 0
            for (call in toolCalls) {
                deps.ui?.onToolStart(call)
                deps.log.append("tool.call", mapOf("id" to call.id, "name" to call.name, "args" to call.arguments.take(8000)))
                val result = withContext(Dispatchers.IO) {
                    deps.tools.execute(call.name, call.arguments, deps.ctx)
                }
                if (result.isError) turnErrors++ else consecutiveErrors = 0
                deps.log.append(
                    "tool.result",
                    mapOf(
                        "id" to call.id,
                        "name" to call.name,
                        "isError" to result.isError,
                        "ms" to result.ms,
                        "bytes" to result.content.length,
                        "preview" to result.content.take(2000)
                    )
                )
                deps.ui?.onToolEnd(call, !result.isError, result.ms, result.content.take(240).replace('\n', ' '))
                messages.add(ChatMessage(role = "tool", toolCallId = call.id, content = result.content))
            }
            if (toolCalls.isNotEmpty() && turnErrors == toolCalls.size) {
                consecutiveErrors += turnErrors
                val limit = deps.settings.softStopErrors
                if (consecutiveErrors >= limit) {
                    finishOutcome = "blocked"
                    finishSummary = "Soft-stop triggered after $consecutiveErrors consecutive tool errors."
                    deps.ui?.onInfo(finishSummary)
                    break
                }
            }

            maybeNudge()
            if (maybeCompact()) break
            if (finished) break
        }

        SessionFiles.saveMessages(deps.ctx.runDir, messages.filter { it.role != "system" })
        deps.log.append(
            "run.end",
            mapOf(
                "outcome" to finishOutcome,
                "turns" to turns,
                "promptTokens" to totals.promptTokens,
                "completionTokens" to totals.completionTokens,
                "summary" to finishSummary.take(4000)
            )
        )
        RunResult(
            summary = finishSummary,
            outcome = finishOutcome,
            turns = turns,
            usage = totals,
            messages = messages.toList()
        )
    }

    private suspend fun completeTurn(): Triple<String, String, List<ToolCallRequest>> {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var sawUsage = false
        val toolCalls = linkedMapOf<Int, ToolCallRequest>()

        val req = CompletionRequest(
            messages = messages.toList(),
            tools = deps.tools.defs(),
            maxOutputTokens = deps.profile?.maxOutputTokens ?: 8192,
            temperature = deps.profile?.temperature,
            topP = deps.profile?.topP,
            parallelToolCalls = deps.profile?.parallelToolCalls ?: true,
            reasoningEffort = deps.profile?.reasoningEffort
        )

        deps.client.stream(req).collect { event ->
            when (event) {
                is ProviderEvent.Text -> {
                    text.append(event.delta)
                    deps.ui?.onText(event.delta)
                }
                is ProviderEvent.Reasoning -> {
                    reasoning.append(event.delta)
                    if (deps.settings.showReasoning) deps.ui?.onReasoning(event.delta)
                }
                is ProviderEvent.ToolCall -> {
                    val existing = toolCalls[event.index]
                    if (existing != null) {
                        toolCalls[event.index] = existing.copy(
                            id = event.id.ifEmpty { existing.id },
                            arguments = event.arguments.ifEmpty { existing.arguments },
                            name = event.name.ifEmpty { existing.name }
                        )
                    } else {
                        toolCalls[event.index] = ToolCallRequest(
                            id = event.id.ifEmpty { "call_${event.index}_${System.currentTimeMillis()}" },
                            name = event.name,
                            arguments = event.arguments
                        )
                    }
                }
                is ProviderEvent.UsageUpdate -> {
                    sawUsage = true
                    val u = event.usage
                    contextTokens = u.promptTokens
                    totals += u
                    deps.log.append(
                        "usage",
                        mapOf(
                            "promptTokens" to u.promptTokens,
                            "completionTokens" to u.completionTokens,
                            "cachedTokens" to u.cachedTokens,
                            "totalTokens" to u.totalTokens
                        )
                    )
                    deps.ui?.onUsage(u, totals, contextTokens)
                }
                is ProviderEvent.Done -> Unit
            }
        }

        if (!sawUsage) {
            estimateUsage(text.toString(), reasoning.toString())
        }
        return Triple(text.toString(), reasoning.toString(), toolCalls.values.toList())
    }

    private fun estimateUsage(text: String, reasoning: String) {
        val msgBytes = messages.sumOf { (it.content?.length ?: 0) + (it.toolCalls?.sumOf { tc -> tc.name.length + tc.arguments.length + 16 } ?: 0) + 8 }
        val promptTokens = ceil((toolBytes + msgBytes) / 3.6).toInt()
        val completionTokens = ceil((text.length + reasoning.length) / 3.6).toInt()
        contextTokens = promptTokens
        val u = Usage(promptTokens, completionTokens, 0, promptTokens + completionTokens)
        totals += u
        deps.log.append("usage", mapOf("promptTokens" to promptTokens, "completionTokens" to completionTokens, "estimated" to true))
        deps.ui?.onUsage(u, totals, contextTokens)
    }

    private fun maybeNudge() {
        val digest = deps.ctx.store.digest()
        val count = Regex("=(\\d+)").findAll(digest.substringBefore("|")).sumOf { it.groupValues[1].toIntOrNull() ?: 0 }
        if (count > lastDigestCount) {
            lastDigestCount = count
            noProgressTurns = 0
            return
        }
        noProgressTurns++
        if (noProgressTurns > 0 && noProgressTurns % deps.settings.noProgressNudge == 0) {
            messages.add(
                ChatMessage(
                    role = "user",
                    content = "[harness] $noProgressTurns turns without a new note or finding. Current state: $digest. Consider a different signal or call finish with an honest summary."
                )
            )
            deps.log.append("nudge", mapOf("afterTurns" to noProgressTurns, "digest" to digest))
        }
    }

    private fun maybeCompact(): Boolean {
        if (contextTokens < deps.contextWindow * deps.settings.compactAt) return false
        val keep = (deps.settings.verbatimTurns / 2).coerceAtLeast(2)
        var users = 0
        var cut = 2
        for (i in messages.lastIndex downTo 2) {
            val m = messages[i]
            if (m.role == "user" && m.content?.startsWith("[harness]") != true) {
                users++
                if (users >= keep) {
                    cut = i
                    break
                }
            }
        }
        if (cut <= 2) {
            if (deps.settings.strictContext && contextTokens >= deps.contextWindow) {
                finishOutcome = "blocked"
                finishSummary = "Strict context cap reached ($contextTokens/${deps.contextWindow} tokens) with no compactable turns left."
                deps.ui?.onInfo(finishSummary)
                return true
            }
            return false
        }
        val droppedCount = cut - 2
        repeat(droppedCount) { messages.removeAt(2) }
        val digest = deps.ctx.store.digest()
        val notes = deps.ctx.store.queryNotes(limit = 60).joinToString("\n") { "#${it.id} [${it.kind.wire}] ${it.key}" }
        val msg = listOfNotNull(
            "[harness] Earlier turns were folded away to free context. Nothing is lost: the full transcript is on disk and every artifact and note is still reachable.",
            "State: $digest",
            notes.takeIf { it.isNotBlank() }?.let { "Existing notes:\n$it" },
            "Re-read what you need with recall/notes instead of assuming."
        ).joinToString("\n")
        messages.add(2, ChatMessage(role = "user", content = msg))
        deps.log.append("compaction", mapOf("droppedMessages" to droppedCount, "contextTokens" to contextTokens, "window" to deps.contextWindow))
        deps.ui?.onInfo("compacted $droppedCount messages at $contextTokens/${deps.contextWindow} tokens")
        return false
    }
}

fun buildSystemPrompt(deps: SessionDeps): String {
    val gate = deps.ctx.gate
    val hosts = gate.reached.keys.joinToString(", ")
    val scopeBlock = listOfNotNull(
        "requests made so far: ${gate.requestsMade}",
        hosts.takeIf { it.isNotBlank() }?.let { "hosts reached: $it" }
    ).joinToString("\n")
    val s = deps.settings
    val runtimeHint = when (s.shellBackend) {
        com.example.data.ShellBackend.ANDROID_SH -> "Android sandbox; shell runs via /system/bin/sh"
        com.example.data.ShellBackend.TERMUX_PROOT -> "Android Termux proot-distro ${s.prootDistro}; bash runs inside Ubuntu userland"
        com.example.data.ShellBackend.PROOT_DIRECT -> "Android proot ${s.prootDistro}; bash runs inside Ubuntu rootfs with /workspace bound"
    }

    return listOfNotNull(
        "You are borer, an autonomous security testing agent running inside workspace ${deps.ctx.workdir.absolutePath} ($runtimeHint).",
        "",
        "You are the operator's hands. Their instructions ARE the authorisation: decide yourself which hosts that implies, and go. Do not ask which target to use when the request already contains one.",
        "Every host on the network is reachable: there is no allowlist, no per-port restriction, no request budget and no soft stop. Produce maximum-impact findings with reproducible proof.",
        "",
        "<engagement>",
        scopeBlock,
        "</engagement>",
        "",
        "Model: ${deps.profile?.model ?: "unconfigured"}. Context window: ${deps.contextWindow} tokens.",
        "",
        "You have a shell, file tools, a persistent notes database, artifact storage, HTTP and raw socket clients, and text/data utilities. The full list is in your tool schema.",
        "",
        "Things that help you work effectively here:",
        "- facts you write to `notes` survive context compaction, facts you only keep in your head do not",
        "- large tool outputs are stored as artifacts and referenced by id; `recall` can page, grep and slice them",
        "- the run directory is on disk, so you can write scripts, logs and PoCs and come back to them",
        "",
        "Obey the operator. When the work is done, or when you conclude the target cannot be broken this way, call `finish` with an honest summary.",
        "",
        "Output style (always):",
        "- plain text only. Avoid heavy markdown decoration",
        "- lists use a plain hyphen and one short line each",
        "- be terse. Lead with the answer or the finding, then the minimum detail that proves it.",
        "",
        deps.extraInstructions?.takeIf { it.isNotBlank() },
        s.customInstructions.takeIf { it.isNotBlank() }
    ).joinToString("\n")
}

object NotifySender {
    private val sentCount = AtomicInteger(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun send(cfg: NotifyConfig, subject: String, body: String) = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured || sentCount.get() >= cfg.limit) return@withContext
        val text = "**$subject**\n\n$body"
        var sentAny = false

        cfg.webhookUrl?.let { url ->
            sentAny = true
            val payload = buildJsonObject {
                put("subject", subject)
                put("text", text)
                put("body", body)
            }.toString()
            runCatching {
                client.newCall(
                    Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().close()
            }
        }
        if (!cfg.telegramToken.isNullOrBlank() && !cfg.telegramChatId.isNullOrBlank()) {
            sentAny = true
            val payload = buildJsonObject {
                put("chat_id", cfg.telegramChatId)
                put("text", text.take(4000))
            }.toString()
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("https://api.telegram.org/bot${cfg.telegramToken}/sendMessage")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().close()
            }
        }
        cfg.discordWebhookUrl?.let { url ->
            sentAny = true
            val payload = buildJsonObject {
                put("content", text.take(1900))
            }.toString()
            runCatching {
                client.newCall(
                    Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().close()
            }
        }
        if (sentAny) sentCount.incrementAndGet()
    }

    suspend fun sendWithStatus(cfg: NotifyConfig, subject: String, body: String): String = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured) return@withContext "No webhook, Telegram, or Discord destination configured."
        val text = "**$subject**\n\n$body"
        val results = mutableListOf<String>()

        cfg.webhookUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val payload = buildJsonObject {
                put("subject", subject)
                put("text", text)
                put("body", body)
            }.toString()
            val status = runCatching {
                client.newCall(
                    Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().use { "webhook: HTTP ${it.code}" }
            }.getOrElse { "webhook: error ${it.message}" }
            results.add(status)
        }
        if (!cfg.telegramToken.isNullOrBlank() && !cfg.telegramChatId.isNullOrBlank()) {
            val payload = buildJsonObject {
                put("chat_id", cfg.telegramChatId)
                put("text", text.take(4000))
            }.toString()
            val status = runCatching {
                client.newCall(
                    Request.Builder()
                        .url("https://api.telegram.org/bot${cfg.telegramToken}/sendMessage")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { "telegram: HTTP ${it.code}" }
            }.getOrElse { "telegram: error ${it.message}" }
            results.add(status)
        }
        cfg.discordWebhookUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val payload = buildJsonObject {
                put("content", text.take(1900))
            }.toString()
            val status = runCatching {
                client.newCall(
                    Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
                ).execute().use { "discord: HTTP ${it.code}" }
            }.getOrElse { "discord: error ${it.message}" }
            results.add(status)
        }
        results.joinToString(" · ")
    }
}
