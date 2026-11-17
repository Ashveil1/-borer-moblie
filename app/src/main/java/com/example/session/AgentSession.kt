package com.example.session

import com.example.data.AgentProfile
import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.ExecutionMode
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
import com.example.ui.ApprovalDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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
    fun onStreamState(streaming: Boolean) {}
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
    val initialContextTokens: Int = 0,
    val executionMode: () -> ExecutionMode = { ExecutionMode.AUTONOMOUS },
    val approvalGate: (suspend (ToolCallRequest) -> ApprovalDecision)? = null,
    val toolsProvider: (() -> ToolRegistry)? = null
) {
    val settings: BorerSettings get() = settingsProvider()
    val effectiveTools: ToolRegistry get() = toolsProvider?.invoke() ?: tools
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
    private var currentTurn = 0
    private var lastCallSignature = ""
    private var repeatedCallStreak = 0

    private val toolBytes: Int = deps.tools.defs().sumOf { it.name.length + it.description.length + it.parameters.toString().length + 32 }

    init {
        messages.add(ChatMessage(role = "system", content = buildSystemPrompt(deps)))
        if (deps.initialMessages.isNotEmpty()) {
            messages.addAll(deps.initialMessages.filter { it.role != "system" })
        }
        deps.ctx.currentTurnProvider = { currentTurn }
        deps.ctx.finish = { summary, outcome ->
            finished = true
            finishSummary = summary
            finishOutcome = outcome ?: "exhausted"
        }
    }

    private fun activeToolsForMode(): ToolRegistry {
        val current = deps.effectiveTools
        return when (deps.executionMode()) {
            ExecutionMode.PLAN -> current.filtered { isPlanAllowedTool(it) }
            ExecutionMode.SUPERVISED, ExecutionMode.AUTONOMOUS -> current
        }
    }

    suspend fun run(userMessage: String, maxTurns: Int = 400): RunResult = withContext(Dispatchers.IO) {
        finished = false
        finishSummary = ""
        finishOutcome = "exhausted"
        consecutiveErrors = 0
        lastCallSignature = ""
        repeatedCallStreak = 0
        messages[0] = ChatMessage(role = "system", content = buildSystemPrompt(deps))
        messages.add(ChatMessage(role = "user", content = userMessage))
        deps.log.append("message", mapOf("role" to "user", "bytes" to userMessage.length))
        var turns = 0

        while (turns < maxTurns) {
            if (finished) break
            if (deps.shouldStop() || deps.ctx.isCancelled()) {
                finishOutcome = "stopped"
                if (finishSummary.isBlank()) finishSummary = "Stopped by operator."
                deps.ui?.onInfo("stop signal received")
                break
            }
            turns++
            currentTurn = turns
            deps.ui?.onTurn(turns)
            deps.log.append("turn.start", mapOf("turn" to turns, "contextTokens" to contextTokens, "mode" to deps.executionMode().wire))

            val activeTools = activeToolsForMode()
            val (text, reasoning, toolCalls) = completeTurn(activeTools)
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
            var stoppedMidTurn = false
            for (callIdx in toolCalls.indices) {
                val call = toolCalls[callIdx]
                if (deps.shouldStop() || deps.ctx.isCancelled()) {
                    stoppedMidTurn = true
                    for (remIdx in callIdx until toolCalls.size) {
                        val skipped = toolCalls[remIdx]
                        val abortedMsg = "error: cancelled by operator before execution"
                        deps.ui?.onToolEnd(skipped, false, 0, abortedMsg)
                        deps.log.append("tool.result", mapOf("id" to skipped.id, "name" to skipped.name, "isError" to true, "aborted" to true))
                        messages.add(ChatMessage(role = "tool", toolCallId = skipped.id, content = abortedMsg))
                    }
                    break
                }

                deps.ui?.onToolStart(call)
                deps.log.append("tool.call", mapOf("id" to call.id, "name" to call.name, "args" to call.arguments.take(8000)))

                // Enforce Plan mode restriction
                val modeNow = deps.executionMode()
                if (modeNow == ExecutionMode.PLAN && !isPlanAllowedTool(call.name)) {
                    val blockedMsg = "error: blocked in Plan mode — '${call.name}' is restricted. Use read-only tools or propose_plan."
                    turnErrors++
                    deps.log.append("tool.denied", mapOf("id" to call.id, "tool" to call.name, "reason" to "plan_mode_readonly"))
                    deps.ui?.onToolEnd(call, false, 0, blockedMsg)
                    messages.add(ChatMessage(role = "tool", toolCallId = call.id, content = blockedMsg))
                    continue
                }

                // Supervised approval gate for mutating/exec tools
                val gateFn = deps.approvalGate
                if (modeNow == ExecutionMode.SUPERVISED && gateFn != null && call.name in RISKY_TOOLS) {
                    val decision = gateFn(call)
                    if (decision is ApprovalDecision.Deny) {
                        val deniedMsg = "error: denied by operator (${decision.reason})"
                        turnErrors++
                        deps.log.append("tool.denied", mapOf("id" to call.id, "tool" to call.name, "reason" to decision.reason))
                        deps.ui?.onToolEnd(call, false, 0, deniedMsg)
                        messages.add(ChatMessage(role = "tool", toolCallId = call.id, content = deniedMsg))
                        continue
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    activeTools.execute(call.name, call.arguments, deps.ctx)
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

                // Doom-loop circuit breaker check
                val sig = "${call.name}|${call.arguments.trim().take(240)}|${result.isError}|${result.content.take(120)}"
                if (sig == lastCallSignature) {
                    repeatedCallStreak++
                } else {
                    lastCallSignature = sig
                    repeatedCallStreak = 1
                }
            }

            if (stoppedMidTurn) {
                finishOutcome = "stopped"
                if (finishSummary.isBlank()) finishSummary = "Stopped by operator."
                deps.ui?.onInfo("stop signal received")
                break
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

            val doomLimit = deps.settings.doomLoopThreshold.coerceAtLeast(2)
            if (repeatedCallStreak >= doomLimit) {
                if (repeatedCallStreak >= doomLimit * 2) {
                    finishOutcome = "blocked"
                    finishSummary = "Doom-loop circuit breaker stopped session after $repeatedCallStreak identical calls."
                    deps.ui?.onInfo(finishSummary)
                    break
                }
                messages.add(
                    ChatMessage(
                        role = "user",
                        content = "[harness] doom-loop guard: identical tool call '${toolCalls.lastOrNull()?.name}' repeated $repeatedCallStreak times. Change approach, inspect inputs/errors, or call finish."
                    )
                )
                deps.log.append("doom_loop", mapOf("streak" to repeatedCallStreak, "tool" to (toolCalls.lastOrNull()?.name ?: "")))
                deps.ui?.onInfo("doom-loop guard triggered ($repeatedCallStreak identical calls)")
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

    fun compactNow(force: Boolean = true): CompactResult {
        if (!force && contextTokens < deps.contextWindow * deps.settings.compactAt) {
            return CompactResult(compacted = false, droppedMessages = 0, remainingMessages = messages.size)
        }
        val groups = groupAtomicTurns(startIndex = 2)
        val keepGroups = (deps.settings.verbatimTurns / 2).coerceAtLeast(2)
        if (groups.size <= keepGroups) {
            if (force) {
                return CompactResult(compacted = false, droppedMessages = 0, remainingMessages = messages.size)
            }
            if (deps.settings.strictContext && contextTokens >= deps.contextWindow) {
                finishOutcome = "blocked"
                finishSummary = "Strict context cap reached ($contextTokens/${deps.contextWindow} tokens) with no compactable turns left."
                deps.ui?.onInfo(finishSummary)
                return CompactResult(compacted = false, droppedMessages = 0, remainingMessages = messages.size, hitStrictLimit = true)
            }
            return CompactResult(compacted = false, droppedMessages = 0, remainingMessages = messages.size)
        }

        val dropUntilIdx = groups[groups.size - keepGroups].first
        val droppedCount = (dropUntilIdx - 2).coerceAtLeast(0)
        if (droppedCount <= 0) {
            return CompactResult(compacted = false, droppedMessages = 0, remainingMessages = messages.size)
        }

        // Capture brief summary of dropped actions before folding
        val droppedSlice = messages.subList(2, dropUntilIdx).toList()
        val toolsUsed = droppedSlice.flatMap { it.toolCalls.orEmpty() }.groupingBy { it.name }.eachCount()
        val toolDigest = if (toolsUsed.isNotEmpty()) {
            "Tools folded: " + toolsUsed.entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key}×${it.value}" }
        } else null

        repeat(droppedCount) { messages.removeAt(2) }
        val digest = deps.ctx.store.digest()
        val notes = deps.ctx.store.queryNotes(limit = 60).joinToString("\n") { "#${it.id} [${it.kind.wire}] ${it.key}" }
        val msg = listOfNotNull(
            "[harness] Earlier turns were folded away to free context ($droppedCount messages compacted). Nothing is lost: the full transcript is on disk and every artifact and note is still reachable.",
            "State: $digest",
            toolDigest,
            notes.takeIf { it.isNotBlank() }?.let { "Existing notes:\n$it" },
            "Re-read what you need with recall/notes instead of assuming."
        ).joinToString("\n")
        messages.add(2, ChatMessage(role = "user", content = msg))

        // Recalculate rough context estimate downward so immediate next check doesn't over-trigger
        val estimatedChars = messages.sumOf { (it.content?.length ?: 0) + (it.toolCalls?.sumOf { tc -> tc.arguments.length + 24 } ?: 0) }
        contextTokens = ceil((toolBytes + estimatedChars) / 3.6).toInt().coerceAtLeast(1)

        deps.log.append("compaction", mapOf("droppedMessages" to droppedCount, "contextTokens" to contextTokens, "window" to deps.contextWindow))
        deps.ui?.onInfo("compacted $droppedCount messages (est. $contextTokens/${deps.contextWindow} tokens)")
        return CompactResult(compacted = true, droppedMessages = droppedCount, remainingMessages = messages.size)
    }

    private fun groupAtomicTurns(startIndex: Int): List<IntRange> {
        if (startIndex > messages.lastIndex) return emptyList()
        val groups = mutableListOf<IntRange>()
        var i = startIndex
        while (i <= messages.lastIndex) {
            val m = messages[i]
            if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                var end = i
                while (end + 1 <= messages.lastIndex && messages[end + 1].role == "tool") {
                    end++
                }
                groups.add(i..end)
                i = end + 1
            } else {
                groups.add(i..i)
                i++
            }
        }
        return groups
    }

    private suspend fun completeTurn(activeTools: ToolRegistry): Triple<String, String, List<ToolCallRequest>> {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var sawUsage = false
        val toolCalls = linkedMapOf<Int, ToolCallRequest>()

        val req = CompletionRequest(
            messages = messages.toList(),
            tools = activeTools.defs(),
            maxOutputTokens = deps.profile?.maxOutputTokens ?: 8192,
            temperature = deps.profile?.temperature,
            topP = deps.profile?.topP,
            parallelToolCalls = deps.profile?.parallelToolCalls ?: true,
            reasoningEffort = deps.profile?.reasoningEffort
        )

        deps.ui?.onStreamState(true)
        try {
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
        } finally {
            deps.ui?.onStreamState(false)
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
            val nudgeText = if (deps.settings.agentProfile == AgentProfile.SECURITY) {
                "[harness] $noProgressTurns turns without a new note or finding. Current state: $digest. Consider a different signal or call finish with an honest summary."
            } else {
                "[harness] $noProgressTurns turns without a recorded note or update. Current state: $digest. Consider trying a different approach or finishing with an honest summary."
            }
            messages.add(
                ChatMessage(
                    role = "user",
                    content = nudgeText
                )
            )
            deps.log.append("nudge", mapOf("afterTurns" to noProgressTurns, "digest" to digest))
        }
    }

    private fun maybeCompact(): Boolean {
        val res = compactNow(force = false)
        return res.hitStrictLimit
    }

    data class CompactResult(
        val compacted: Boolean,
        val droppedMessages: Int,
        val remainingMessages: Int,
        val hitStrictLimit: Boolean = false
    )

    companion object {
        val PLAN_ALLOWED_TOOLS = setOf(
            "read", "ls", "glob", "grep",
            "web_search", "web_fetch",
            "mcp_resource", "mcp_prompt",
            "notes", "todo", "recall", "scope", "learn", "skill",
            "diff", "transform", "propose_plan", "finish"
        )

        fun isPlanAllowedTool(name: String): Boolean {
            if (name in PLAN_ALLOWED_TOOLS) return true
            if (name.startsWith("mcp__")) {
                val lower = name.lowercase()
                return lower.contains("search") ||
                    lower.contains("list") ||
                    lower.contains("read") ||
                    lower.contains("get") ||
                    lower.contains("fetch")
            }
            return false
        }

        val RISKY_TOOLS = setOf(
            "bash", "job", "write", "edit", "apply_patch", "http", "net", "verify"
        )
    }
}

fun findWorkspaceAgentsFile(workdir: File): File? {
    val candidates = listOf(
        File(workdir, "AGENTS.md"),
        File(workdir, "BORER.md"),
        File(workdir, ".borer/AGENTS.md")
    )
    return candidates.firstOrNull { it.exists() && it.isFile }
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

    val modeBlock = when (deps.executionMode()) {
        ExecutionMode.PLAN -> "Execution Mode: PLAN (read-only). Do NOT modify files or run mutating shell commands; explore and analyze using read-only tools and submit an actionable plan via `propose_plan`."
        ExecutionMode.SUPERVISED -> if (s.supervisedApprovals) {
            "Execution Mode: INTERACTIVE CHAT (supervised). Answer conversational questions directly; when mutating files, running shell commands, or calling raw network tools, the operator will approve."
        } else {
            "Execution Mode: INTERACTIVE CHAT."
        }
        ExecutionMode.AUTONOMOUS -> "Execution Mode: AUTONOMOUS. Execute multi-step planning, implementation, web research, or verification continuously until complete, then call `finish`."
    }

    val agentsRules = if (s.agentsMdEnabled) {
        findWorkspaceAgentsFile(deps.ctx.workdir)?.let { file ->
            runCatching { file.readText().trim().take(12_000) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { txt ->
                "<workspace-rules file=\"${file.name}\">\n$txt\n</workspace-rules>"
            }
        }
    } else null

    val personaHeader = when (s.agentProfile) {
        AgentProfile.GENERAL -> listOf(
            "You are Borer, a capable general-purpose AI assistant and autonomous agent running in workspace ${deps.ctx.workdir.absolutePath} ($runtimeHint).",
            "",
            "Adapt naturally to whatever the user asks—conversation, brainstorming, writing, translation, coding, Linux/Android CLI tasks, math, data analysis, or live web research:",
            "- For normal Q&A, explanations, writing, or quick code snippets that do not need local files or live data, answer directly and clearly without forcing unnecessary tool calls.",
            "- When a task benefits from local files, shell execution, live web search (`web_search`), reading web pages (`web_fetch`), HTTP APIs (`http`), or multi-step work, use your tools proactively and report only verified results.",
            "- Reply in the same language as the user (e.g., Thai if the user writes in Thai, English if in English).",
            "- Never invent file contents, command outputs, tool calls, or citations."
        )
        AgentProfile.CODING -> listOf(
            "You are Borer, an expert software engineering and terminal agent working in workspace ${deps.ctx.workdir.absolutePath} ($runtimeHint).",
            "",
            "Engineering workflow:",
            "- Inspect files and repository structure (`ls`, `glob`, `grep`, `read`) before making assumptions or edits.",
            "- Prefer clean, surgical changes using `edit` or `apply_patch` (multi-file diffs). File edits automatically create checkpoints for `/diff` and `/undo`.",
            "- Run builds, linters, or tests via `bash` or `verify` to confirm changes actually work before concluding.",
            "- Delegate noisy repository searches or independent subtasks via `task` (`explore` or `worker`) to keep main context clean."
        )
        AgentProfile.RESEARCH -> listOf(
            "You are Borer, a thorough research and synthesis agent operating in workspace ${deps.ctx.workdir.absolutePath} ($runtimeHint).",
            "",
            "Research workflow:",
            "- Use `web_search` to discover primary sources and `web_fetch` / `http` to read full pages, documentation, or APIs directly.",
            "- Cross-check facts across multiple sources, distinguish verified facts from uncertainty, and save key findings in `notes` or `report`.",
            "- Always include source URLs or workspace references for synthesized claims."
        )
        AgentProfile.SECURITY -> listOf(
            "You are borer, an autonomous security testing agent running inside workspace ${deps.ctx.workdir.absolutePath} ($runtimeHint).",
            "",
            "You are the operator's hands. Their instructions ARE the authorisation: decide yourself which hosts that implies, and go. Do not ask which target to use when the request already contains one.",
            "Every host on the network is reachable: there is no allowlist, no per-port restriction, no request budget and no soft stop. Produce maximum-impact findings with reproducible proof."
        )
    }

    val toolsAndMemoryBlock = listOfNotNull(
        "Model: ${deps.profile?.model ?: "unconfigured"}. Context window: ${deps.contextWindow} tokens.",
        "",
        "Available capabilities (when enabled in tool schema): web search & page reading (`web_search`, `web_fetch`, `http`), external MCP tools/resources/prompts (`mcp__*`, `mcp_resource`, `mcp_prompt`), shell & background jobs (`bash`, `job`), filesystem & patching (`read`, `write`, `edit`, `apply_patch`, `ls`, `glob`, `grep`, `diff`), isolated subagents (`task`), persistent memory & todos (`notes`, `todo`, `learn`, `recall`, `skill`), and structured reports (`report`, `verify`, `transform`).",
        "",
        "Workspace habits:",
        "- Important facts, preferences, or intermediate results written to `notes` / `todo` survive context compaction.",
        "- Large tool outputs spill to disk artifacts; use `recall` to page, grep, or slice them without blowing up context.",
        "- Workspace writes (`write`, `edit`, `apply_patch`) create automatic pre-turn checkpoints so the operator can inspect `/diff` or `/undo`.",
        "- Use `task` (`explore` or `worker`) for isolated subtasks or noisy exploration.",
        if (deps.executionMode() == ExecutionMode.AUTONOMOUS) {
            "- When an autonomous multi-step run is finished or cannot proceed further, call `finish` with an honest summary."
        } else {
            "- For conversational turns in Chat mode, answer directly once you have the information needed; you only need `finish` if running a long multi-step autonomous task."
        }
    )

    val outputStyleBlock = if (s.agentProfile == AgentProfile.SECURITY) {
        listOf(
            "Output style (always):",
            "- plain text only. Avoid heavy markdown decoration",
            "- lists use a plain hyphen and one short line each",
            "- be terse. Lead with the answer or the finding, then the minimum detail that proves it."
        )
    } else {
        listOf(
            "Output style:",
            "- Use clear, well-structured Markdown (headings, bullet lists, tables, and language-tagged fenced code blocks when helpful).",
            "- Be concise and direct for simple questions; thorough and well-organized for complex technical, research, or creative tasks."
        )
    }

    val mcpSection = deps.ctx.mcpManager?.buildSystemPromptSection()?.takeIf { it.isNotBlank() }

    return buildList {
        addAll(personaHeader)
        add("")
        add(modeBlock)
        if (s.agentProfile == AgentProfile.SECURITY || gate.requestsMade > 0 || hosts.isNotBlank()) {
            add("")
            add("<session-network>")
            add(scopeBlock)
            add("</session-network>")
        }
        mcpSection?.let { add(""); add(it) }
        add("")
        addAll(toolsAndMemoryBlock)
        add("")
        addAll(outputStyleBlock)
        agentsRules?.let { add(""); add(it) }
        deps.extraInstructions?.takeIf { it.isNotBlank() }?.let { add(""); add(it) }
        s.customInstructions.takeIf { it.isNotBlank() }?.let { add(""); add(it) }
    }.joinToString("\n")
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
