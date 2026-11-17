package com.example.session

import android.content.Context
import com.example.BorerApplication
import com.example.data.AgentProfile
import com.example.data.AppConfig
import com.example.data.ArtifactEntity
import com.example.data.ArtifactStore
import com.example.data.BorerSettings
import com.example.data.CheckpointManager
import com.example.data.ConfigLoader
import com.example.data.ContextSource
import com.example.data.EventLog
import com.example.data.EventRecord
import com.example.data.ExecutionMode
import com.example.data.FileDiffItem
import com.example.data.FindingEntity
import com.example.data.HttpLogRecord
import com.example.data.JobEntity
import com.example.data.LearningMemory
import com.example.data.LearningStats
import com.example.data.McpServerConfig
import com.example.data.NoteEntity
import com.example.data.NoteKind
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.RunStore
import com.example.data.SessionEntity
import com.example.data.SessionFiles
import com.example.data.ShellBackend
import com.example.data.defaultWhsearchMcpServer
import com.example.mcp.McpConnectionStatus
import com.example.mcp.McpManager
import com.example.mcp.McpServerState
import com.example.provider.ChatMessage
import com.example.provider.FallbackClient
import com.example.provider.ModelCatalogEntry
import com.example.provider.OpenAICompatClient
import com.example.provider.ToolCallRequest
import com.example.provider.Usage
import com.example.runtime.BackgroundKeepAliveController
import com.example.runtime.BackgroundSessionSnapshot
import com.example.runtime.ProotDiagnostics
import com.example.runtime.UbuntuProotRuntime
import com.example.scope.ChildProxyServer
import com.example.scope.EngagementGate
import com.example.tools.BackgroundJobs
import com.example.tools.BorerSkill
import com.example.tools.Cvss31Calculator
import com.example.tools.SkillStore
import com.example.tools.ToolContext
import com.example.tools.ToolRegistry
import com.example.tools.ToolResult
import com.example.tools.applyPatchTool
import com.example.tools.bashTool
import com.example.tools.diffTool
import com.example.tools.fsTools
import com.example.tools.httpTool
import com.example.tools.jobTool
import com.example.tools.learningTool
import com.example.tools.metaTools
import com.example.tools.mutateTool
import com.example.tools.netTool
import com.example.tools.proposePlanTool
import com.example.tools.reportTool
import com.example.tools.sessionTool
import com.example.tools.skillTool
import com.example.tools.taskTool
import com.example.tools.transformTool
import com.example.tools.verifyTool
import com.example.tools.webFetchTool
import com.example.tools.webSearchTool
import com.example.ui.ApprovalDecision
import com.example.ui.ConsoleUiState
import com.example.ui.HuntItemStatus
import com.example.ui.HuntQueueItem
import com.example.ui.MentionSuggestion
import com.example.ui.PendingToolApproval
import com.example.ui.RunMode
import com.example.ui.TranscriptEntry
import com.example.ui.screens.ToolSummaryFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class BorerSessionController(
    private val app: BorerApplication,
    private val scope: CoroutineScope
) {
    private val runDb = app.runDatabase
    val memory: LearningMemory = app.learningMemory
    val workspaceDir: File = app.workspaceDir
    val runsDir: File = app.runsDir

    private val prefs = app.getSharedPreferences("borer_settings", Context.MODE_PRIVATE)
    private val cfgJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionMutex = Mutex()

    private val _settings = MutableStateFlow(loadPersistedSettings())
    val settings: StateFlow<BorerSettings> = _settings.asStateFlow()

    private val _config = MutableStateFlow(loadEffectiveAppConfig(_settings.value))
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _skills = MutableStateFlow(SkillStore.loadSkills(workspaceDir))
    val skills: StateFlow<List<BorerSkill>> = _skills.asStateFlow()

    private val _prootDiagnostics = MutableStateFlow(UbuntuProotRuntime.inspectEnvironment(app, _settings.value))
    val prootDiagnostics: StateFlow<ProotDiagnostics> = _prootDiagnostics.asStateFlow()

    private val _prootTestOutput = MutableStateFlow<String?>(null)
    val prootTestOutput: StateFlow<String?> = _prootTestOutput.asStateFlow()

    private val _prootTesting = MutableStateFlow(false)
    val prootTesting: StateFlow<Boolean> = _prootTesting.asStateFlow()

    private val _providerTestResult = MutableStateFlow<String?>(null)
    val providerTestResult: StateFlow<String?> = _providerTestResult.asStateFlow()

    private val _providerTesting = MutableStateFlow(false)
    val providerTesting: StateFlow<Boolean> = _providerTesting.asStateFlow()

    private val _notifyTestResult = MutableStateFlow<String?>(null)
    val notifyTestResult: StateFlow<String?> = _notifyTestResult.asStateFlow()

    private val _console = MutableStateFlow(
        ConsoleUiState(
            mode = _settings.value.resolvedExecutionMode.toRunMode(),
            agentsMdLoaded = findWorkspaceAgentsFile(workspaceDir) != null,
            agentProfile = _settings.value.agentProfile
        )
    )
    val console: StateFlow<ConsoleUiState> = _console.asStateFlow()

    private val _activeRunId = MutableStateFlow("")
    val activeRunId: StateFlow<String> = _activeRunId.asStateFlow()

    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    private val _httpLogs = MutableStateFlow<List<HttpLogRecord>>(emptyList())
    val httpLogs: StateFlow<List<HttpLogRecord>> = _httpLogs.asStateFlow()

    private val _huntItems = MutableStateFlow<List<HuntQueueItem>>(emptyList())
    val huntItems: StateFlow<List<HuntQueueItem>> = _huntItems.asStateFlow()

    private val _huntRunning = MutableStateFlow(false)
    val huntRunning: StateFlow<Boolean> = _huntRunning.asStateFlow()

    val activeSessions: StateFlow<List<SessionEntity>> = runDb.sessionDao().observeActiveSessions()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedSessions: StateFlow<List<SessionEntity>> = runDb.sessionDao().observeArchivedSessions()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notes: StateFlow<List<NoteEntity>> = _activeRunId.flatMapLatest { id ->
        runDb.noteDao().observeNotes(id)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val findings: StateFlow<List<FindingEntity>> = _activeRunId.flatMapLatest { id ->
        runDb.findingDao().observeFindings(id)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artifacts: StateFlow<List<ArtifactEntity>> = _activeRunId.flatMapLatest { id ->
        runDb.artifactDao().observeArtifacts(id)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val jobs: StateFlow<List<JobEntity>> = _activeRunId.flatMapLatest { id ->
        runDb.jobDao().observeJobs(id)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExperience = memory.experienceFlow.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allTechniques = memory.techniquesFlow.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allTargets = memory.targetsFlow.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var gate = EngagementGate()
    private var childProxy: ChildProxyServer? = null
    private lateinit var log: EventLog
    private lateinit var artifactStore: ArtifactStore
    private lateinit var store: RunStore
    private lateinit var checkpointManager: CheckpointManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolCtx: ToolContext
    private var agentSession: AgentSession? = null
    private var sessionInitJob: Job? = null
    private var activeRunJob: Job? = null
    private var huntJob: Job? = null
    @Volatile
    private var stopFlag = false
    private var observersJob: Job? = null

    // Coalesced stream buffers
    private val streamLock = Any()
    private val pendingText = StringBuilder()
    private val pendingReasoning = StringBuilder()
    private var streamFlushJob: Job? = null

    // Supervised tool approvals
    private val sessionAllowedTools = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var allowAllToolsSession = false
    @Volatile
    private var pendingApprovalDeferred: CompletableDeferred<ApprovalDecision>? = null

    val mcpManager = McpManager(app, workspaceDir)
    val mcpStates: StateFlow<List<McpServerState>> = mcpManager.states

    private val _mcpInstallingId = MutableStateFlow<String?>(null)
    val mcpInstallingId: StateFlow<String?> = _mcpInstallingId.asStateFlow()

    private val _mcpInstallLog = MutableStateFlow<String?>(null)
    val mcpInstallLog: StateFlow<String?> = _mcpInstallLog.asStateFlow()

    init {
        BackgroundKeepAliveController.onStopRun = {
            if (_huntRunning.value) stopHuntQueue() else forceCancelTurn()
        }
        BackgroundKeepAliveController.onReleaseWakeLock = {
            setPersistentWakeLock(false)
        }
        mcpManager.onToolsChanged = {
            toolRegistry = buildToolRegistry(_skills.value, _settings.value)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { runDb.sessionDao().deleteEmptySessions() }
        }
        startNewSession()
        detectCatalogContextWindows()
        syncBackgroundKeepAlive()
        syncMcpServersAsync()
    }

    private fun RunMode.toExecutionMode(): ExecutionMode = when (this) {
        RunMode.PLAN -> ExecutionMode.PLAN
        RunMode.CHAT -> ExecutionMode.SUPERVISED
        RunMode.AUTONOMOUS -> ExecutionMode.AUTONOMOUS
    }

    private fun ExecutionMode.toRunMode(): RunMode = when (this) {
        ExecutionMode.PLAN -> RunMode.PLAN
        ExecutionMode.SUPERVISED -> RunMode.CHAT
        ExecutionMode.AUTONOMOUS -> RunMode.AUTONOMOUS
    }

    private fun syncBackgroundKeepAlive() {
        val c = _console.value
        val s = _settings.value
        val activeTool = c.entries.lastOrNull { it is TranscriptEntry.ToolExec && it.running } as? TranscriptEntry.ToolExec
        val toolLabel = activeTool?.let { t ->
            val summary = ToolSummaryFormatter.summarize(t.name, t.arguments)
            if (summary.isNotBlank()) "Running ${t.name} $summary" else "Running ${t.name}"
        }
        BackgroundKeepAliveController.updateSnapshot(
            context = app,
            next = BackgroundSessionSnapshot(
                keepAliveEnabled = s.backgroundKeepAlive,
                persistentWakeLock = s.persistentWakeLock,
                busy = c.busy,
                huntRunning = _huntRunning.value,
                modeLabel = when (c.mode) {
                    RunMode.PLAN -> "Plan"
                    RunMode.CHAT -> "Chat"
                    RunMode.AUTONOMOUS -> "Autonomous"
                },
                activeModel = c.activeModel,
                turn = c.turn,
                activeToolLabel = toolLabel
            )
        )
    }

    private fun detectCatalogContextWindows() {
        val cfg = _config.value
        if (cfg.providers.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val detected = cfg.providers.associate { p ->
                if (p.contextSource == ContextSource.CUSTOM) return@associate (p.baseUrl to p.model) to null
                val entries = runCatching { OpenAICompatClient(p).catalog() }.getOrNull()
                val match = entries?.firstOrNull { e ->
                    e.id == p.model || e.id.endsWith("/${p.model}") || p.model.endsWith(e.id)
                }
                (p.baseUrl to p.model) to match?.context
            }
            var changed = false
            _config.update { current ->
                val merged = current.providers.map { p ->
                    val ctx = if (p.contextSource != ContextSource.CUSTOM) detected[p.baseUrl to p.model] else null
                    if (ctx != null && ctx != p.contextWindow) {
                        changed = true
                        p.copy(contextWindow = ctx, contextSource = ContextSource.DETECTED)
                    } else p
                }
                current.copy(providers = merged)
            }
            if (changed) {
                withContext(Dispatchers.Main) {
                    applyConfigToIdleSession()
                }
            }
        }
    }

    fun setRunMode(mode: RunMode) {
        _console.update { it.copy(mode = mode) }
        syncBackgroundKeepAlive()
    }

    fun setAgentProfile(profile: AgentProfile) {
        updateSettings { it.copy(agentProfile = profile) }
    }

    fun setPersistentWakeLock(enabled: Boolean) {
        updateSettings { it.copy(persistentWakeLock = enabled) }
    }

    fun startNewSession(
        runId: String = EventLog.newRunId(),
        initialMessages: List<ChatMessage> = emptyList(),
        taskHint: String? = null
    ) {
        if (_console.value.busy) return
        sessionInitJob?.cancel()
        flushPendingStreams()
        pendingApprovalDeferred?.cancel()
        pendingApprovalDeferred = null
        sessionAllowedTools.clear()
        allowAllToolsSession = false

        val cfg = _config.value
        val profile = cfg.providers.firstOrNull()
        val window = cfg.providers.minOfOrNull { it.contextWindow } ?: 32_768
        val restoredEntries = initialMessages.toTranscriptEntries()

        _activeRunId.value = runId
        _events.value = emptyList()
        _httpLogs.value = emptyList()
        _console.value = ConsoleUiState(
            runId = runId,
            mode = _console.value.mode,
            busy = false,
            isStreaming = false,
            stopRequested = false,
            contextWindow = window,
            contextSource = profile?.contextSource?.name?.lowercase() ?: "fallback",
            activeModel = profile?.model ?: "unconfigured",
            providerConfigured = cfg.providers.isNotEmpty(),
            entries = restoredEntries,
            learningStats = _console.value.learningStats,
            agentsMdLoaded = findWorkspaceAgentsFile(workspaceDir) != null,
            agentProfile = _settings.value.agentProfile
        )
        syncBackgroundKeepAlive()

        sessionInitJob = scope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    val runDir = File(runsDir, runId).apply { mkdirs() }
                    runCatching { childProxy?.close() }
                    gate = EngagementGate()
                    log = EventLog(runDir)
                    val proxy = ChildProxyServer(gate, log).also { childProxy = it }
                    val proxyUrl = runCatching { proxy.start() }.getOrNull()
                    artifactStore = ArtifactStore(runDir)
                    store = RunStore(runId, runDb)
                    checkpointManager = CheckpointManager(runId, runDir, workspaceDir, runDb)

                    val currentCfg = _config.value
                    val currentProfile = currentCfg.providers.firstOrNull()
                    val jobsRegistry = BackgroundJobs(
                        appContext = app,
                        settingsProvider = { _settings.value },
                        runId = runId,
                        runDir = runDir,
                        db = runDb,
                        log = log,
                        workdir = workspaceDir,
                        scope = scope,
                        proxyUrl = proxyUrl
                    )

                    val subagentRunner: suspend (String, String, Int) -> ToolResult = { role, prompt, maxTurns ->
                        executeSubagent(role, prompt, maxTurns)
                    }

                    toolCtx = ToolContext(
                        runDir = runDir,
                        workdir = workspaceDir,
                        gate = gate,
                        log = log,
                        artifacts = artifactStore,
                        store = store,
                        db = runDb,
                        settingsProvider = { _settings.value },
                        isCancelled = { stopFlag },
                        notify = { text -> log.append("notify", mapOf("text" to text)) },
                        jobs = jobsRegistry,
                        proxyUrl = proxyUrl,
                        checkpoints = checkpointManager,
                        onPlanProposal = { title, summary, steps ->
                            appendEntry(
                                TranscriptEntry.PlanProposal(
                                    id = UUID.randomUUID().toString(),
                                    title = title,
                                    summary = summary,
                                    steps = steps
                                )
                            )
                        },
                        subagentRunner = subagentRunner,
                        shellExecutor = { command, cwd, env, timeoutMs, stdin ->
                            UbuntuProotRuntime.exec(app, command, cwd, env, timeoutMs, stdin, _settings.value)
                        },
                        mcpManager = mcpManager
                    )

                    val currentSkills = _skills.value
                    toolRegistry = buildToolRegistry(currentSkills, _settings.value)

                    val fallbackClient = FallbackClient(currentCfg.providers, log) { attempt, delayMs, reason ->
                        appendInfo("retry $attempt in ${delayMs}ms: $reason")
                    }

                    val stats = runCatching { memory.stats() }.getOrDefault(LearningStats(0, 0, 0, 0))
                    val extra = listOfNotNull(
                        SkillStore.skillIndex(currentSkills).takeIf { it.isNotBlank() },
                        buildMemoryBrief(taskHint).takeIf { it.isNotBlank() }
                    ).joinToString("\n\n").ifBlank { null }

                    val session = AgentSession(
                        SessionDeps(
                            profile = currentProfile,
                            client = fallbackClient,
                            contextWindow = fallbackClient.contextWindow,
                            tools = toolRegistry,
                            ctx = toolCtx,
                            settingsProvider = { _settings.value },
                            log = log,
                            extraInstructions = extra,
                            ui = makeUiHooks(),
                            shouldStop = { stopFlag },
                            initialMessages = initialMessages,
                            executionMode = { _console.value.mode.toExecutionMode() },
                            approvalGate = { call -> requestUserApproval(call) },
                            toolsProvider = { buildToolRegistry(_skills.value, _settings.value) }
                        )
                    )
                    agentSession = session

                    log.append(
                        "run.start",
                        mapOf(
                            "model" to (currentProfile?.model ?: "unconfigured"),
                            "baseUrl" to (currentProfile?.baseUrl ?: ""),
                            "contextWindow" to fallbackClient.contextWindow,
                            "task" to taskHint.orEmpty(),
                            "runDir" to runDir.absolutePath
                        )
                    )

                    if (initialMessages.isNotEmpty() && runDb.sessionDao().getSession(runId) == null) {
                        val now = Instant.now().toString()
                        runDb.sessionDao().upsert(
                            SessionEntity(
                                id = runId,
                                title = initialMessages.firstOrNull { it.role == "user" }?.content?.take(80) ?: "(session)",
                                model = currentProfile?.model ?: "unconfigured",
                                baseUrl = currentProfile?.baseUrl ?: "",
                                contextWindow = fallbackClient.contextWindow,
                                turns = initialMessages.count { it.role == "assistant" },
                                messageCount = initialMessages.size,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }

                    val diffs = checkpointManager.sessionDiffs()
                    withContext(Dispatchers.Main) {
                        _console.update {
                            it.copy(
                                contextWindow = fallbackClient.contextWindow,
                                contextSource = currentProfile?.contextSource?.name?.lowercase() ?: "fallback",
                                activeModel = currentProfile?.model ?: "unconfigured",
                                providerConfigured = currentCfg.providers.isNotEmpty(),
                                learningStats = stats,
                                changedFilesCount = diffs.size,
                                checkpointCount = checkpointManager.latestTurn() ?: 0,
                                agentsMdLoaded = findWorkspaceAgentsFile(workspaceDir) != null,
                                agentProfile = _settings.value.agentProfile
                            )
                        }
                        observeLogStreams()
                        syncBackgroundKeepAlive()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    appendInfo("Session init error: ${t.message ?: t::class.simpleName}", isWarn = true)
                }
            }
        }
    }

    private fun buildToolRegistry(skillsList: List<BorerSkill>, s: BorerSettings): ToolRegistry {
        val ts = s.toolsets
        val selected = buildList {
            if (ts.filesystem) {
                addAll(fsTools)
                add(applyPatchTool)
                add(diffTool)
            }
            if (ts.shell) {
                add(bashTool)
                add(jobTool)
            }
            if (ts.network) {
                add(webSearchTool)
                add(webFetchTool)
                add(httpTool)
                add(netTool)
                add(verifyTool)
                add(mutateTool)
            }
            if (ts.subagent) {
                add(taskTool)
            }
            if (ts.memory) {
                add(learningTool(memory))
            }
            add(sessionTool)
            add(transformTool)
            add(reportTool)
            add(proposePlanTool)
            addAll(metaTools)
            if (skillsList.isNotEmpty()) {
                add(skillTool(skillsList))
            }
            if (ts.mcp) {
                addAll(mcpManager.buildBuiltinMetaTools())
                val seen = map { it.name }.toMutableSet()
                mcpManager.buildDynamicTools().forEach { t ->
                    if (seen.add(t.name)) add(t)
                }
            }
        }
        return ToolRegistry(selected)
    }

    private suspend fun executeSubagent(role: String, prompt: String, maxTurns: Int): ToolResult {
        val currentCfg = _config.value
        val currentProfile = currentCfg.providers.firstOrNull()
        val scopedTools = when (role) {
            "explore" -> toolRegistry.filtered { AgentSession.isPlanAllowedTool(it) && it != "propose_plan" }
            else -> toolRegistry.filtered { it != "task" && it != "propose_plan" }
        }
        val childCtx = ToolContext(
            runDir = toolCtx.runDir,
            workdir = toolCtx.workdir,
            gate = gate,
            log = log,
            artifacts = artifactStore,
            store = store,
            db = runDb,
            settingsProvider = { _settings.value },
            isCancelled = { stopFlag },
            notify = { text -> log.append("subagent.notify", mapOf("role" to role, "text" to text)) },
            jobs = toolCtx.jobs,
            proxyUrl = toolCtx.proxyUrl,
            checkpoints = checkpointManager,
            currentTurnProvider = toolCtx.currentTurnProvider,
            isSubagent = true,
            shellExecutor = toolCtx.shellExecutor,
            mcpManager = mcpManager
        )
        val childClient = FallbackClient(currentCfg.providers, log)
        val childSession = AgentSession(
            SessionDeps(
                profile = currentProfile,
                client = childClient,
                contextWindow = childClient.contextWindow,
                tools = scopedTools,
                ctx = childCtx,
                settingsProvider = { _settings.value },
                log = log,
                extraInstructions = "You are a scoped '$role' subagent. Return a concise, evidence-backed summary with file paths, artifact IDs, and key results.",
                shouldStop = { stopFlag },
                executionMode = {
                    if (role == "explore") ExecutionMode.PLAN else ExecutionMode.AUTONOMOUS
                }
            )
        )
        _console.update { it.copy(subagentActiveLabel = "Subagent ($role)") }
        log.append("subagent.start", mapOf("role" to role, "maxTurns" to maxTurns, "prompt" to prompt.take(400)))
        return try {
            val res = childSession.run(prompt, maxTurns = maxTurns)
            val finalAssistant = res.messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
            val report = res.summary.ifBlank { finalAssistant }.ifBlank { "(subagent finished with empty response)" }
            log.append("subagent.end", mapOf("role" to role, "turns" to res.turns, "tokens" to res.usage.totalTokens))
            ToolResult(
                "[subagent:$role turns=${res.turns} tokens=${res.usage.totalTokens} outcome=${res.outcome}]\n$report"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ToolResult("error: subagent($role) failed: ${t.message ?: t::class.simpleName}", isError = true)
        } finally {
            _console.update { it.copy(subagentActiveLabel = null) }
        }
    }

    private suspend fun requestUserApproval(call: ToolCallRequest): ApprovalDecision {
        if (allowAllToolsSession || sessionAllowedTools.contains(call.name)) {
            return ApprovalDecision.AllowOnce
        }
        val summary = ToolSummaryFormatter.summarize(call.name, call.arguments)
        val riskReason = when (call.name) {
            "bash", "job" -> "Executes shell command on runtime"
            "write", "edit", "apply_patch" -> "Modifies workspace file(s)"
            "http", "net", "verify" -> "Performs outbound network request"
            else -> "Requires operator approval"
        }
        val deferred = CompletableDeferred<ApprovalDecision>()
        pendingApprovalDeferred = deferred
        _console.update {
            it.copy(
                pendingApproval = PendingToolApproval(
                    callId = call.id,
                    toolName = call.name,
                    summary = summary.ifBlank { call.name },
                    argumentsPreview = call.arguments.take(500),
                    riskReason = riskReason
                )
            )
        }
        syncBackgroundKeepAlive()
        return try {
            val decision = deferred.await()
            when (decision) {
                is ApprovalDecision.AllowToolSession -> sessionAllowedTools.add(call.name)
                is ApprovalDecision.AllowAllSession -> allowAllToolsSession = true
                else -> Unit
            }
            decision
        } catch (_: CancellationException) {
            ApprovalDecision.Deny("Cancelled by operator")
        } finally {
            if (pendingApprovalDeferred === deferred) pendingApprovalDeferred = null
            _console.update { it.copy(pendingApproval = null) }
            syncBackgroundKeepAlive()
        }
    }

    fun resolveToolApproval(decision: ApprovalDecision) {
        val deferred = pendingApprovalDeferred
        _console.update { it.copy(pendingApproval = null) }
        deferred?.complete(decision)
    }

    fun approvePlan(entryId: String, targetMode: RunMode) {
        _console.update { cur ->
            cur.copy(
                entries = cur.entries.map {
                    if (it is TranscriptEntry.PlanProposal && it.id == entryId) it.copy(approved = true) else it
                }
            )
        }
        setRunMode(targetMode)
        submitPrompt("Plan approved. Proceed with execution.")
    }

    private fun observeLogStreams() {
        observersJob?.cancel()
        observersJob = scope.launch {
            launch { log.events.collect { _events.value = it } }
            launch { log.httpLogs.collect { _httpLogs.value = it } }
            launch { gate.hostsSnapshot.collect { hosts -> _console.update { it.copy(hostsReached = hosts) } } }
            launch { log.allowedCount.collect { allowed -> _console.update { it.copy(requestsAllowed = allowed) } } }
            launch { log.deniedCount.collect { denied -> _console.update { it.copy(requestsDenied = denied) } } }
        }
    }

    fun submitPrompt(rawInput: String) {
        val text = rawInput.trim()
        if (text.isEmpty()) return

        when {
            text == "/help" -> {
                appendInfo("commands: /plan · /mode [plan|chat|auto] · /persona [general|code|research|security] · /mcp [refresh|connect <id>] · /compact · /diff · /undo · /status · /usage · /stop · /clear · /help")
                return
            }
            text == "/mcp" || text.startsWith("/mcp ") -> {
                val arg = text.removePrefix("/mcp").trim()
                when {
                    arg.equals("refresh", ignoreCase = true) || arg.equals("sync", ignoreCase = true) -> {
                        refreshAllMcpServers()
                        appendInfo("refreshing MCP servers...")
                    }
                    arg.startsWith("connect ", ignoreCase = true) -> {
                        val id = arg.removePrefix("connect").trim()
                        connectMcpServer(id)
                        appendInfo("connecting MCP server '$id'...")
                    }
                    arg.startsWith("disconnect ", ignoreCase = true) -> {
                        val id = arg.removePrefix("disconnect").trim()
                        disconnectMcpServer(id)
                        appendInfo("disconnected MCP server '$id'")
                    }
                    else -> {
                        val states = mcpStates.value
                        if (states.isEmpty()) {
                            appendInfo("no MCP servers configured (add in Settings -> MCP)")
                        } else {
                            val summary = states.joinToString(" | ") { st ->
                                "${st.config.id}:${st.status.label}(${st.tools.size}t/${st.resources.size}r/${st.prompts.size}p)"
                            }
                            appendInfo("MCP: $summary")
                        }
                    }
                }
                return
            }
            text == "/usage" -> {
                val c = _console.value
                appendInfo(
                    "context ${c.contextTokens}/${c.contextWindow} tokens · prompt=${c.totals.promptTokens} (cached=${c.totals.cachedTokens}) completion=${c.totals.completionTokens}"
                )
                return
            }
            text == "/status" -> {
                val c = _console.value
                val s = _settings.value
                val mcpConnected = mcpStates.value.count { it.status == McpConnectionStatus.CONNECTED }
                appendInfo(
                    "run=${c.runId} · persona=${c.agentProfile.label} · mode=${c.mode.name} · backend=${s.shellBackend.wire} · mcp=$mcpConnected/${s.mcpServers.size} · checkpoints=${c.checkpointCount} (${c.changedFilesCount} files)"
                )
                return
            }
            text == "/persona" || text.startsWith("/persona ") || text == "/agent" || text.startsWith("/agent ") -> {
                val arg = text.removePrefix("/persona").removePrefix("/agent").trim().lowercase()
                val next = when (arg) {
                    "" -> {
                        val all = AgentProfile.entries
                        all[(all.indexOf(_settings.value.agentProfile) + 1) % all.size]
                    }
                    "general", "assistant", "chat" -> AgentProfile.GENERAL
                    "code", "coding", "dev" -> AgentProfile.CODING
                    "research", "web" -> AgentProfile.RESEARCH
                    "sec", "security", "pentest" -> AgentProfile.SECURITY
                    else -> {
                        appendInfo("unknown persona '$arg' (use general, code, research, or security)", isWarn = true)
                        return
                    }
                }
                setAgentProfile(next)
                appendInfo("persona switched to ${next.label}")
                return
            }
            text == "/plan" -> {
                setRunMode(RunMode.PLAN)
                appendInfo("mode switched to Plan (read-only)")
                return
            }
            text == "/mode" || text.startsWith("/mode ") -> {
                val arg = text.removePrefix("/mode").trim().lowercase()
                val next = when (arg) {
                    "" -> if (_console.value.mode == RunMode.CHAT) RunMode.AUTONOMOUS else RunMode.CHAT
                    "plan" -> RunMode.PLAN
                    "chat", "ask", "supervised" -> RunMode.CHAT
                    "auto", "autonomous" -> RunMode.AUTONOMOUS
                    else -> {
                        appendInfo("unknown mode '$arg' (use plan, chat, or auto)", isWarn = true)
                        return
                    }
                }
                setRunMode(next)
                val label = when (next) {
                    RunMode.PLAN -> "Plan"
                    RunMode.CHAT -> "Chat"
                    RunMode.AUTONOMOUS -> "Autonomous"
                }
                appendInfo("mode switched to $label")
                return
            }
            text == "/compact" -> {
                compactContextNow()
                return
            }
            text == "/undo" -> {
                undoLastTurn()
                return
            }
            text == "/stop" -> {
                requestStop()
                return
            }
            text == "/clear" -> {
                if (!_console.value.busy) startNewSession()
                else appendInfo("cannot start new session while turn is running", isWarn = true)
                return
            }
        }

        if (_console.value.busy) {
            appendEntry(TranscriptEntry.User(UUID.randomUUID().toString(), text))
            _console.update { it.copy(queuedPrompts = it.queuedPrompts + text) }
            return
        }

        executePrompt(text, echoUser = true)
    }

    fun compactContextNow() {
        if (_console.value.busy) {
            appendInfo("cannot compact while turn is running", isWarn = true)
            return
        }
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            val session = agentSession ?: return@launch
            val res = session.compactNow(force = true)
            if (res.compacted) {
                SessionFiles.saveMessages(toolCtx.runDir, session.messages.filter { it.role != "system" })
            } else {
                appendInfo("nothing to compact yet (${res.remainingMessages} messages)")
            }
        }
    }

    fun undoLastTurn() {
        if (_console.value.busy) {
            appendInfo("cannot undo while turn is running", isWarn = true)
            return
        }
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::checkpointManager.isInitialized) return@launch
            val res = checkpointManager.undoLastTurn()
            val diffs = checkpointManager.sessionDiffs()
            _console.update {
                it.copy(
                    changedFilesCount = diffs.size,
                    checkpointCount = checkpointManager.latestTurn() ?: 0
                )
            }
            if (res.ok) {
                log.append("checkpoint.undo", mapOf("turn" to res.turn, "files" to res.restoredFiles.joinToString(",")))
            }
            appendInfo(res.message, isWarn = !res.ok)
        }
    }

    suspend fun getSessionDiffs(): List<FileDiffItem> = withContext(Dispatchers.IO) {
        sessionInitJob?.join()
        if (!::checkpointManager.isInitialized) emptyList() else checkpointManager.sessionDiffs()
    }

    suspend fun getMentionSuggestions(query: String): List<MentionSuggestion> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        val out = mutableListOf<MentionSuggestion>()

        val skipDirs = setOf(".git", "node_modules", ".gradle", "build")
        workspaceDir.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile }
            .take(60)
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).path.replace(File.separatorChar, '/')
                if (q.isBlank() || rel.lowercase().contains(q)) {
                    out.add(MentionSuggestion("@file:$rel", rel, "${file.length()}b", "file"))
                }
            }

        if (::artifactStore.isInitialized) {
            runDb.artifactDao().listArtifactsSync(_console.value.runId).takeLast(12).forEach { art ->
                if (q.isBlank() || art.id.lowercase().contains(q) || art.label.lowercase().contains(q)) {
                    out.add(MentionSuggestion("@artifact:${art.id}", "${art.id} · ${art.label.take(28)}", "${art.lines} lines", "artifact"))
                }
            }
        }

        if (::store.isInitialized) {
            store.queryNotes(limit = 15).forEach { note ->
                if (q.isBlank() || note.key.lowercase().contains(q) || note.kind.wire.contains(q)) {
                    out.add(MentionSuggestion("@note:${note.id}", "#${note.id} [${note.kind.wire}] ${note.key}", note.body.take(40), "note"))
                }
            }
        }
        out.take(15)
    }

    private fun resolveMentionContext(userText: String): String {
        val regex = Regex("@(file|artifact|note):([^\\s]+)")
        val matches = regex.findAll(userText).take(4).toList()
        if (matches.isEmpty()) return userText

        val sections = mutableListOf<String>()
        for (m in matches) {
            val type = m.groupValues[1]
            val ref = m.groupValues[2]
            when (type) {
                "file" -> {
                    val f = File(workspaceDir, ref)
                    if (f.exists() && f.isFile && f.length() <= 256 * 1024) {
                        val content = runCatching { f.readText().take(4000) }.getOrNull()
                        if (content != null) sections.add("<file path=\"$ref\">\n$content\n</file>")
                    }
                }
                "artifact" -> {
                    if (::artifactStore.isInitialized) {
                        val slice = artifactStore.slice(ref, 0, 4000)
                        if (!slice.isNullOrBlank()) sections.add("<artifact id=\"$ref\">\n$slice\n</artifact>")
                    }
                }
                "note" -> {
                    val id = ref.toLongOrNull()
                    if (id != null && ::store.isInitialized) {
                        store.getNote(id)?.let { n ->
                            sections.add("<note id=\"${n.id}\" kind=\"${n.kind.wire}\" key=\"${n.key}\">\n${n.body.take(3000)}\n</note>")
                        }
                    }
                }
            }
        }
        if (sections.isEmpty()) return userText
        return "$userText\n\n<attached-context>\n${sections.joinToString("\n")}\n</attached-context>"
    }

    private fun executePrompt(text: String, echoUser: Boolean) {
        if (!_console.value.providerConfigured) {
            if (echoUser) appendEntry(TranscriptEntry.User(UUID.randomUUID().toString(), text))
            appendInfo(
                "No AI provider configured. Open Menu -> Settings -> Providers to add your Base URL, Model, and API Key.",
                isWarn = true
            )
            return
        }

        stopFlag = false
        if (echoUser) appendEntry(TranscriptEntry.User(UUID.randomUUID().toString(), text))
        _console.update { it.copy(busy = true, stopRequested = false) }
        syncBackgroundKeepAlive()

        activeRunJob = scope.launch {
            val myJob = coroutineContext[Job]
            var runOutcome: String? = null
            var runSummary: String? = null
            var runTurns = 0
            try {
                sessionInitJob?.join()
                val session = agentSession
                if (session == null) {
                    appendInfo("Session could not initialize.", isWarn = true)
                    return@launch
                }
                val enrichedText = withContext(Dispatchers.IO) { resolveMentionContext(text) }
                val mode = _console.value.mode
                val maxTurns = when (mode) {
                    RunMode.AUTONOMOUS -> 400
                    RunMode.PLAN -> 30
                    RunMode.CHAT -> 60
                }
                val result = session.run(enrichedText, maxTurns = maxTurns)
                runOutcome = result.outcome
                runSummary = result.summary
                runTurns = result.turns
                if (mode == RunMode.AUTONOMOUS && result.summary.isNotBlank()) {
                    withContext(Dispatchers.IO) { SessionFiles.saveSummary(toolCtx.runDir, result.summary) }
                    appendInfo("autonomous run finished (${result.outcome}) in ${result.turns} turns")
                    val findingList = withContext(Dispatchers.IO) { store.listFindings() }
                    val findingLines = findingList.joinToString("\n") { "- ${it.severity} ${it.asset} — ${it.title}" }
                    val title = if (findingList.isNotEmpty()) {
                        "borer: ${result.outcome} — ${findingList.size} report(s)"
                    } else {
                        "borer: ${result.outcome} (${result.turns} turns)"
                    }
                    NotifySender.send(
                        _config.value.notify,
                        title,
                        listOf(result.summary.take(2500), "", "run: ${_console.value.runId}", findingLines).filter { it.isNotBlank() }.joinToString("\n")
                    )
                }
            } catch (_: CancellationException) {
                runOutcome = "cancelled"
                appendInfo("turn cancelled", isWarn = true)
            } catch (t: Throwable) {
                runOutcome = "error"
                appendInfo("turn failed: ${t.message ?: t::class.simpleName}", isWarn = true)
            } finally {
                flushPendingStreams()
                pendingApprovalDeferred?.cancel()
                pendingApprovalDeferred = null
                withContext(NonCancellable) {
                    val hasMessages = agentSession?.messages?.any { it.role == "user" || it.role == "assistant" } == true
                    if (hasMessages) {
                        persistSessionRecord(runOutcome, runSummary, runTurns)
                    }
                    refreshCheckpointCounts()
                }
                if (activeRunJob !== myJob) return@launch
                activeRunJob = null
                if (stopFlag || runOutcome == "cancelled") {
                    _console.update { it.copy(busy = false, isStreaming = false, stopRequested = false, queuedPrompts = emptyList(), pendingApproval = null) }
                    syncBackgroundKeepAlive()
                } else {
                    var next: String? = null
                    _console.update { cur ->
                        next = cur.queuedPrompts.firstOrNull()
                        cur.copy(
                            busy = false,
                            isStreaming = false,
                            stopRequested = false,
                            pendingApproval = null,
                            queuedPrompts = if (next != null) cur.queuedPrompts.drop(1) else emptyList()
                        )
                    }
                    if (next != null) {
                        executePrompt(next!!, echoUser = false)
                    } else {
                        syncBackgroundKeepAlive()
                    }
                }
            }
        }
    }

    private fun refreshCheckpointCounts() {
        if (!::checkpointManager.isInitialized) return
        val diffs = runCatching { checkpointManager.sessionDiffs() }.getOrDefault(emptyList())
        val latest = runCatching { checkpointManager.latestTurn() ?: 0 }.getOrDefault(0)
        _console.update { it.copy(changedFilesCount = diffs.size, checkpointCount = latest) }
    }

    fun requestStop() {
        stopFlag = true
        pendingApprovalDeferred?.complete(ApprovalDecision.Deny("Stopped by operator"))
        _console.update { it.copy(stopRequested = true, pendingApproval = null) }
        syncBackgroundKeepAlive()
    }

    fun forceCancelTurn() {
        stopFlag = true
        pendingApprovalDeferred?.complete(ApprovalDecision.Deny("Force stopped by operator"))
        pendingApprovalDeferred = null
        val job = activeRunJob
        activeRunJob = null
        job?.cancel()
        flushPendingStreams()
        _console.update { it.copy(busy = false, isStreaming = false, stopRequested = false, queuedPrompts = emptyList(), pendingApproval = null) }
        syncBackgroundKeepAlive()
    }

    fun toggleReasoningCollapsed(entryId: String) {
        _console.update { cur ->
            cur.copy(
                entries = cur.entries.map {
                    if (it is TranscriptEntry.Reasoning && it.id == entryId) it.copy(collapsed = !it.collapsed) else it
                }
            )
        }
    }

    fun resumeSession(sessionEntity: SessionEntity): Boolean {
        if (_console.value.busy) {
            appendInfo("cannot switch session while a turn is running", isWarn = true)
            return false
        }
        scope.launch {
            val msgs = withContext(Dispatchers.IO) {
                SessionFiles.loadMessages(File(runsDir, sessionEntity.id))
            }
            startNewSession(runId = sessionEntity.id, initialMessages = msgs, taskHint = sessionEntity.title)
            sessionInitJob?.join()
            appendInfo("resumed ${sessionEntity.id} · ${sessionEntity.turns} turns")
        }
        return true
    }

    fun forkSession(sessionEntity: SessionEntity): Boolean {
        if (_console.value.busy) {
            appendInfo("cannot fork session while a turn is running", isWarn = true)
            return false
        }
        scope.launch {
            val newId = "${sessionEntity.id}-fork-${EventLog.newRunId().takeLast(6)}"
            val msgs = withContext(Dispatchers.IO) {
                val srcDir = File(runsDir, sessionEntity.id)
                val dstDir = File(runsDir, newId).apply { mkdirs() }
                File(srcDir, "artifacts").takeIf { it.exists() }?.copyRecursively(File(dstDir, "artifacts"), overwrite = true)
                val loaded = SessionFiles.loadMessages(srcDir)
                SessionFiles.saveMessages(dstDir, loaded)
                runDb.noteDao().queryNotesSync(sessionEntity.id, null, 500).forEach { n ->
                    runDb.noteDao().insertSync(n.copy(id = 0, runId = newId))
                }
                loaded
            }
            startNewSession(runId = newId, initialMessages = msgs, taskHint = sessionEntity.title)
            sessionInitJob?.join()
            appendInfo("forked ${sessionEntity.id} -> $newId")
        }
        return true
    }

    fun archiveSession(id: String, archived: Boolean) {
        scope.launch(Dispatchers.IO) {
            runDb.sessionDao().setArchived(id, archived, Instant.now().toString())
        }
    }

    fun deleteSession(id: String) {
        if (id == _activeRunId.value && _console.value.busy) return
        scope.launch(Dispatchers.IO) {
            runDb.sessionDao().deleteSession(id)
            runDb.noteDao().deleteByRun(id)
            runDb.artifactDao().deleteByRun(id)
            runDb.findingDao().deleteByRun(id)
            runDb.jobDao().deleteByRun(id)
            runDb.checkpointDao().deleteByRun(id)
            File(runsDir, id).deleteRecursively()
            if (id == _activeRunId.value) {
                withContext(Dispatchers.Main) { startNewSession() }
            }
        }
    }

    suspend fun buildSessionMarkdownExport(sessionId: String? = null): String = withContext(Dispatchers.IO) {
        val targetId = sessionId ?: _console.value.runId
        val meta = runDb.sessionDao().getSession(targetId)
        val msgs = SessionFiles.loadMessages(File(runsDir, targetId)).ifEmpty {
            if (targetId == _console.value.runId) agentSession?.messages?.filter { it.role != "system" }.orEmpty() else emptyList()
        }
        val notesList = runDb.noteDao().queryNotesSync(targetId, null, 200)
        val findingsList = runDb.findingDao().listFindingsSync(targetId)

        buildString {
            appendLine("# Borer Session Export: $targetId")
            appendLine()
            if (meta != null) {
                appendLine("- **Title:** ${meta.title}")
                appendLine("- **Model:** ${meta.model} (ctx ${meta.contextWindow})")
                appendLine("- **Turns:** ${meta.turns} · **Tokens:** prompt=${meta.totalPromptTokens} (cached=${meta.totalCachedTokens}), completion=${meta.totalCompletionTokens}")
                appendLine("- **Outcome:** ${meta.outcome ?: "active"}")
                appendLine()
            }
            if (findingsList.isNotEmpty()) {
                appendLine("## Findings (${findingsList.size})")
                findingsList.forEach { f ->
                    appendLine("- **[${f.severity.uppercase()}]** ${f.title} (${f.asset})${f.cvss?.let { " · CVSS $it" } ?: ""}")
                }
                appendLine()
            }
            if (notesList.isNotEmpty()) {
                appendLine("## Notes (${notesList.size})")
                notesList.forEach { n ->
                    appendLine("### #${n.id} [${n.kind}] ${n.key}")
                    appendLine(n.body)
                    appendLine()
                }
            }
            appendLine("## Transcript")
            appendLine()
            for (m in msgs) {
                when (m.role) {
                    "user" -> {
                        appendLine("### User")
                        appendLine(m.content.orEmpty())
                        appendLine()
                    }
                    "assistant" -> {
                        appendLine("### Assistant")
                        if (!m.reasoning.isNullOrBlank()) {
                            appendLine("<details><summary>Reasoning</summary>\n\n${m.reasoning}\n</details>\n")
                        }
                        if (!m.toolCalls.isNullOrEmpty()) {
                            m.toolCalls.forEach { tc ->
                                appendLine("- Tool Call: `${tc.name}` `${tc.arguments.take(300)}`")
                            }
                            appendLine()
                        }
                        if (!m.content.isNullOrBlank()) {
                            appendLine(m.content)
                            appendLine()
                        }
                    }
                    "tool" -> {
                        appendLine("**Tool Output (${m.toolCallId}):**")
                        appendLine("```")
                        appendLine(m.content.orEmpty().take(4000))
                        appendLine("```")
                        appendLine()
                    }
                }
            }
        }
    }

    suspend fun buildSessionJsonExport(sessionId: String? = null): String = withContext(Dispatchers.IO) {
        val targetId = sessionId ?: _console.value.runId
        val meta = runDb.sessionDao().getSession(targetId)
        val msgs = SessionFiles.loadMessages(File(runsDir, targetId)).ifEmpty {
            if (targetId == _console.value.runId) agentSession?.messages?.filter { it.role != "system" }.orEmpty() else emptyList()
        }
        val root = buildJsonObject {
            put("runId", targetId)
            put("exportedAt", Instant.now().toString())
            meta?.let {
                put("title", it.title)
                put("model", it.model)
                put("turns", it.turns)
                put("promptTokens", it.totalPromptTokens)
                put("cachedTokens", it.totalCachedTokens)
                put("completionTokens", it.totalCompletionTokens)
            }
            put("messages", cfgJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), msgs))
            val fArr = buildJsonArray {
                runDb.findingDao().listFindingsSync(targetId).forEach { f ->
                    add(buildJsonObject {
                        put("title", f.title)
                        put("severity", f.severity)
                        put("asset", f.asset)
                        f.cvss?.let { put("cvss", it) }
                    })
                }
            }
            put("findings", fArr)
        }
        Json { prettyPrint = true }.encodeToString(root)
    }

    fun startHuntQueue(targetsRaw: String, templateRaw: String) {
        if (_huntRunning.value || _console.value.busy) return
        val targets = targetsRaw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (targets.isEmpty()) return
        if (_config.value.providers.isEmpty()) {
            appendInfo("Cannot start hunt queue: configure a provider in Settings -> Providers first.", isWarn = true)
            return
        }

        _huntItems.value = targets.map { HuntQueueItem(target = it) }
        _huntRunning.value = true
        _console.update { it.copy(busy = true, stopRequested = false) }
        syncBackgroundKeepAlive()

        huntJob = scope.launch {
            try {
                for (idx in targets.indices) {
                    if (!_huntRunning.value) break
                    val target = targets[idx]
                    val safeSlug = target.replace(Regex("[^a-zA-Z0-9]+"), "-").take(28).trim('-')
                    val runId = "${EventLog.newRunId()}-$safeSlug"
                    updateHuntItem(idx) { it.copy(status = HuntItemStatus.RUNNING, runId = runId) }

                    val prompt = if (templateRaw.isNotBlank()) {
                        templateRaw.replace("{target}", target)
                    } else {
                        "Complete this task independently: $target. Gather any necessary context, verify your work with real tools, write a structured summary or report, and finish."
                    }

                    var runOutcome: String? = null
                    var runSummary: String? = null
                    var runTurns = 0
                    try {
                        stopFlag = false
                        setRunMode(RunMode.AUTONOMOUS)
                        // Release busy briefly only to invoke startNewSession cleanly under hunt guard
                        _console.update { it.copy(busy = false) }
                        startNewSession(runId = runId, taskHint = target)
                        _console.update { it.copy(busy = true, stopRequested = false) }
                        sessionInitJob?.join()
                        appendEntry(TranscriptEntry.User(UUID.randomUUID().toString(), prompt))
                        syncBackgroundKeepAlive()

                        val res = agentSession?.run(prompt, maxTurns = 200)
                        if (res != null) {
                            runOutcome = res.outcome
                            runSummary = res.summary
                            runTurns = res.turns
                            withContext(Dispatchers.IO) { SessionFiles.saveSummary(toolCtx.runDir, res.summary) }
                            val count = withContext(Dispatchers.IO) { store.listFindings().size }
                            updateHuntItem(idx) {
                                it.copy(status = HuntItemStatus.DONE, findingsCount = count, message = "${res.outcome} · ${res.turns}t")
                            }
                        } else {
                            updateHuntItem(idx) { it.copy(status = HuntItemStatus.FAILED, message = "session not initialized") }
                        }
                    } catch (_: CancellationException) {
                        runOutcome = "cancelled"
                        updateHuntItem(idx) { it.copy(status = HuntItemStatus.FAILED, message = "cancelled") }
                        break
                    } catch (t: Throwable) {
                        runOutcome = "error"
                        updateHuntItem(idx) { it.copy(status = HuntItemStatus.FAILED, message = t.message ?: t::class.simpleName) }
                    } finally {
                        flushPendingStreams()
                        withContext(NonCancellable) {
                            if (agentSession?.messages?.any { it.role == "user" || it.role == "assistant" } == true) {
                                persistSessionRecord(runOutcome, runSummary, runTurns)
                            }
                        }
                    }
                }
            } finally {
                _huntRunning.value = false
                _console.update { it.copy(busy = false, isStreaming = false, stopRequested = false) }
                syncBackgroundKeepAlive()
            }
        }
    }

    fun stopHuntQueue() {
        _huntRunning.value = false
        stopFlag = true
        pendingApprovalDeferred?.complete(ApprovalDecision.Deny("Hunt stopped"))
        huntJob?.cancel()
        flushPendingStreams()
        _console.update { it.copy(busy = false, isStreaming = false, stopRequested = false, pendingApproval = null) }
        syncBackgroundKeepAlive()
    }

    private fun updateHuntItem(index: Int, transform: (HuntQueueItem) -> HuntQueueItem) {
        _huntItems.value = _huntItems.value.mapIndexed { i, item -> if (i == index) transform(item) else item }
    }

    // --- Intel CRUD ---

    fun saveNote(kind: NoteKind, key: String, body: String, tags: List<String> = emptyList()) {
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized) return@launch
            store.putNote(kind, key.trim(), body, tags)
        }
    }

    fun deleteNote(id: Long) {
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized) return@launch
            store.deleteNote(id)
        }
    }

    fun addTodo(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized) return@launch
            val current = parseTodos(store.queryNotes(kind = NoteKind.TODO, limit = 1).firstOrNull()?.body)
            val updated = current + (false to text.trim())
            val body = updated.mapIndexed { i, (d, t) -> "${i + 1}. [${if (d) "x" else " "}] $t" }.joinToString("\n")
            store.putNote(NoteKind.TODO, "current", body)
        }
    }

    fun toggleTodo(index: Int) {
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized) return@launch
            val current = parseTodos(store.queryNotes(kind = NoteKind.TODO, limit = 1).firstOrNull()?.body).toMutableList()
            if (index in current.indices) {
                current[index] = (!current[index].first) to current[index].second
                val body = current.mapIndexed { i, (d, t) -> "${i + 1}. [${if (d) "x" else " "}] $t" }.joinToString("\n")
                store.putNote(NoteKind.TODO, "current", body)
            }
        }
    }

    fun clearTodos() {
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized) return@launch
            store.putNote(NoteKind.TODO, "current", "(empty)")
        }
    }

    fun createFindingReport(
        title: String,
        asset: String,
        cvssVector: String,
        summary: String,
        impact: String,
        remediation: String
    ) {
        scope.launch(Dispatchers.IO) {
            sessionInitJob?.join()
            if (!::store.isInitialized || !::toolCtx.isInitialized) return@launch
            val cvss = Cvss31Calculator.score(cvssVector.trim())
            val severity = cvss?.severity ?: "medium"
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "finding" }
            val note = store.putNote(NoteKind.FINDING, "$slug-${System.currentTimeMillis()}", "$summary\n\nimpact: $impact", listOf(severity, asset))
            val finding = store.addFinding(title.trim(), severity, cvss?.base, cvss?.vector, asset.trim(), null, note.id)
            val reportsDir = File(toolCtx.runDir, "reports").apply { mkdirs() }
            val file = File(reportsDir, "${finding.id}-$slug.md")
            file.writeText(
                buildString {
                    appendLine("# ${title.trim()}")
                    appendLine()
                    append("**Severity:** $severity")
                    if (cvss != null) append(" · CVSS 3.1 ${"%.1f".format(cvss.base)} (${cvss.vector})")
                    appendLine("\n**Asset:** ${asset.ifBlank { "n/a" }}\n")
                    appendLine("## Summary\n\n$summary\n")
                    appendLine("## Impact\n\n$impact\n")
                    if (remediation.isNotBlank()) appendLine("## Remediation\n\n$remediation\n")
                }
            )
            store.updateFindingReport(finding.id, file.absolutePath, "verified")
        }
    }

    fun readArtifact(id: String): String =
        if (::artifactStore.isInitialized) artifactStore.read(id).orEmpty() else ""
    fun grepArtifact(id: String, pattern: String): List<String> =
        if (::artifactStore.isInitialized) runCatching { artifactStore.grep(id, pattern).orEmpty() }.getOrDefault(emptyList()) else emptyList()
    fun sliceArtifact(id: String, offset: Int, length: Int): String =
        if (::artifactStore.isInitialized) artifactStore.slice(id, offset, length).orEmpty() else ""

    // --- Memory, Skills, AGENTS.md & Settings ---

    fun addExperience(fingerprint: String, approach: String, outcome: String, worked: Boolean) {
        scope.launch(Dispatchers.IO) {
            memory.recordExperience(fingerprint.trim(), approach.trim(), outcome.trim(), worked)
        }
    }

    fun addTechnique(situation: String, steps: String, worked: Boolean) {
        scope.launch(Dispatchers.IO) {
            memory.recordTechnique(situation.trim(), steps.trim(), worked)
        }
    }

    fun deleteExperience(id: Long) = scope.launch(Dispatchers.IO) { memory.deleteExperience(id) }
    fun deleteTechnique(id: Long) = scope.launch(Dispatchers.IO) { memory.deleteTechnique(id) }
    fun deleteTargetHistory(host: String) = scope.launch(Dispatchers.IO) { memory.deleteTarget(host) }

    fun saveCustomSkill(name: String, description: String, body: String) {
        scope.launch(Dispatchers.IO) {
            val cleanName = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "custom-skill" }
            val dir = File(workspaceDir, ".borer/skills/$cleanName").apply { mkdirs() }
            File(dir, "SKILL.md").writeText("---\nname: $cleanName\ndescription: $description\n---\n$body\n")
            _skills.value = SkillStore.loadSkills(workspaceDir)
            applyConfigToIdleSession()
        }
    }

    suspend fun loadAgentsMd(): String = withContext(Dispatchers.IO) {
        val target = findWorkspaceAgentsFile(workspaceDir) ?: File(workspaceDir, "AGENTS.md")
        if (target.exists() && target.isFile) runCatching { target.readText() }.getOrDefault("") else ""
    }

    fun saveAgentsMd(content: String) {
        scope.launch(Dispatchers.IO) {
            val file = File(workspaceDir, "AGENTS.md")
            if (content.isBlank()) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(content)
            }
            _console.update { it.copy(agentsMdLoaded = findWorkspaceAgentsFile(workspaceDir) != null) }
            applyConfigToIdleSession()
        }
    }

    fun refreshProotDiagnostics() {
        _prootDiagnostics.value = UbuntuProotRuntime.inspectEnvironment(app, _settings.value)
    }

    fun launchTermuxUbuntuSetup(): String? {
        val res = UbuntuProotRuntime.launchTermuxUbuntuSetup(app, _settings.value.prootDistro)
        return res.exceptionOrNull()?.message
    }

    fun testProotUbuntu() {
        if (_prootTesting.value) return
        _prootTesting.value = true
        _prootTestOutput.value = null
        scope.launch {
            val res = UbuntuProotRuntime.exec(
                context = app,
                command = "uname -a && ([ -r /etc/os-release ] && grep -E '^(PRETTY_NAME|VERSION)=' /etc/os-release || true)",
                cwd = workspaceDir,
                env = emptyMap(),
                timeoutMs = 25_000L,
                stdin = null,
                settings = _settings.value
            )
            val prefix = "exit=${res.exitCode ?: "null"}${if (res.timedOut) " (timeout)" else ""}"
            _prootTestOutput.value = "$prefix\n${res.output.ifBlank { "(no output)" }}"
            _prootTesting.value = false
            refreshProotDiagnostics()
        }
    }

    fun upsertProvider(profile: ProviderProfile, editIndex: Int? = null) {
        val cleanedUrl = profile.baseUrl.trim()
            .trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/responses")
            .trimEnd('/')
        val cleanedModel = profile.model.trim()
        val cleanedName = profile.name.trim().ifBlank { "provider" }
        val (ctxWindow, ctxSrc) = when {
            profile.contextWindow > 0 && profile.contextSource == ContextSource.CUSTOM -> profile.contextWindow to ContextSource.CUSTOM
            profile.contextWindow > 0 -> profile.contextWindow to profile.contextSource
            else -> {
                val detected = ConfigLoader.detectContextWindow(cleanedModel)
                if (detected != null) detected to ContextSource.DETECTED else 32_768 to ContextSource.FALLBACK
            }
        }
        val normalized = profile.copy(
            id = cleanedName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").ifBlank { "provider" },
            name = cleanedName,
            baseUrl = cleanedUrl,
            model = cleanedModel,
            apiKey = profile.apiKey.trim(),
            contextWindow = ctxWindow,
            contextSource = ctxSrc,
            reasoningEffort = profile.reasoningEffort?.trim()?.takeIf { it.isNotBlank() }
        )
        val current = _config.value.providers.toMutableList()
        if (editIndex != null && editIndex in current.indices) {
            current[editIndex] = normalized
        } else {
            current.add(normalized)
        }
        saveProvidersList(current, customized = true)
        detectCatalogContextWindows()
    }

    fun deleteProvider(index: Int) {
        val current = _config.value.providers.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        saveProvidersList(current, customized = true)
    }

    fun moveProvider(index: Int, delta: Int) {
        val current = _config.value.providers.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        saveProvidersList(current, customized = true)
    }

    fun resetProvidersToDefaults() {
        prefs.edit().putBoolean("providers_customized", false).remove("providers_json").apply()
        val defaults = ConfigLoader.load(_settings.value).providers
        _config.value = _config.value.copy(providers = defaults)
        applyConfigToIdleSession()
        detectCatalogContextWindows()
    }

    suspend fun fetchProviderModels(profile: ProviderProfile): Result<List<ModelCatalogEntry>> =
        withContext(Dispatchers.IO) {
            runCatching { OpenAICompatClient(profile).catalog() }
        }

    fun testProviderConnection(profile: ProviderProfile) {
        if (_providerTesting.value) return
        _providerTesting.value = true
        _providerTestResult.value = null
        scope.launch {
            val started = System.currentTimeMillis()
            val res = runCatching { OpenAICompatClient(profile).catalog() }
            val ms = System.currentTimeMillis() - started
            _providerTestResult.value = res.fold(
                onSuccess = { models ->
                    val match = models.firstOrNull { it.id == profile.model || it.id.endsWith("/${profile.model}") }
                    val ctxHint = match?.context?.let { " · ctx $it" }.orEmpty()
                    val matchHint = if (match != null) "model '${profile.model}' found$ctxHint" else "connected (${models.size} models listed)"
                    "OK (${ms}ms): $matchHint"
                },
                onFailure = { err ->
                    "Failed (${ms}ms): ${err.message ?: err::class.simpleName}"
                }
            )
            _providerTesting.value = false
        }
    }

    fun clearProviderTestResult() {
        _providerTestResult.value = null
    }

    fun updateNotifyConfig(next: NotifyConfig) {
        val clean = next.copy(
            webhookUrl = next.webhookUrl?.trim()?.takeIf { it.isNotBlank() },
            telegramToken = next.telegramToken?.trim()?.takeIf { it.isNotBlank() },
            telegramChatId = next.telegramChatId?.trim()?.takeIf { it.isNotBlank() },
            discordWebhookUrl = next.discordWebhookUrl?.trim()?.takeIf { it.isNotBlank() },
            limit = next.limit.coerceIn(1, 200)
        )
        prefs.edit()
            .putBoolean("notify_customized", true)
            .putString("notify_json", cfgJson.encodeToString(clean))
            .apply()
        _config.value = _config.value.copy(notify = clean)
    }

    fun resetNotifyToDefaults() {
        prefs.edit().putBoolean("notify_customized", false).remove("notify_json").apply()
        val defaults = ConfigLoader.load(_settings.value).notify
        _config.value = _config.value.copy(notify = defaults)
    }

    fun testNotification() {
        _notifyTestResult.value = "Sending..."
        scope.launch {
            _notifyTestResult.value = NotifySender.sendWithStatus(
                _config.value.notify,
                "borer test notification",
                "Test ping from Borer Android Settings."
            )
        }
    }

    fun updateSettings(transform: (BorerSettings) -> BorerSettings) {
        val prev = _settings.value
        val next = transform(prev)
        _settings.value = next
        _config.value = _config.value.copy(settings = next)
        savePersistedSettings(next)
        refreshProotDiagnostics()
        syncBackgroundKeepAlive()
        if (prev.toolsets != next.toolsets || prev.agentsMdEnabled != next.agentsMdEnabled || prev.agentProfile != next.agentProfile) {
            _console.update { it.copy(agentProfile = next.agentProfile) }
            applyConfigToIdleSession()
        }
        if (prev.mcpServers != next.mcpServers || prev.shellBackend != next.shellBackend) {
            syncMcpServersAsync()
        }
    }

    fun resetAllSettingsToDefaults() {
        val def = BorerSettings()
        _settings.value = def
        _config.value = _config.value.copy(settings = def)
        savePersistedSettings(def)
        refreshProotDiagnostics()
        syncBackgroundKeepAlive()
        _console.update { it.copy(agentProfile = def.agentProfile) }
        applyConfigToIdleSession()
        syncMcpServersAsync()
    }

    private fun syncMcpServersAsync() {
        scope.launch(Dispatchers.IO) {
            mcpManager.syncServers(_settings.value.mcpServers, _settings.value)
        }
    }

    fun upsertMcpServer(server: McpServerConfig) {
        val normalizedId = McpManager.sanitizeToken(server.id.ifBlank { server.name })
        val clean = server.copy(
            id = normalizedId,
            name = server.name.trim().ifBlank { normalizedId },
            url = server.url.trim(),
            command = server.command.trim(),
            repoUrl = server.repoUrl.trim()
        )
        updateSettings { s ->
            val list = s.mcpServers.toMutableList()
            val idx = list.indexOfFirst { it.id == clean.id }
            if (idx >= 0) list[idx] = clean else list.add(clean)
            s.copy(mcpServers = list)
        }
    }

    fun deleteMcpServer(serverId: String) {
        updateSettings { s ->
            s.copy(mcpServers = s.mcpServers.filterNot { it.id == serverId })
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        updateSettings { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) it.copy(enabled = enabled) else it })
        }
    }

    fun connectMcpServer(serverId: String) {
        scope.launch(Dispatchers.IO) {
            val s = _settings.value
            val target = s.mcpServers.firstOrNull { it.id == serverId } ?: return@launch
            val enabledTarget = if (target.enabled) target else target.copy(enabled = true)
            if (!target.enabled) {
                val updated = s.mcpServers.map { if (it.id == serverId) enabledTarget else it }
                _settings.value = s.copy(mcpServers = updated)
                _config.value = _config.value.copy(settings = _settings.value)
                savePersistedSettings(_settings.value)
            }
            mcpManager.connectOrReconnect(enabledTarget, _settings.value, _settings.value.mcpServers)
        }
    }

    fun disconnectMcpServer(serverId: String) {
        scope.launch(Dispatchers.IO) {
            val s = _settings.value
            val updated = s.mcpServers.map { if (it.id == serverId) it.copy(enabled = false) else it }
            _settings.value = s.copy(mcpServers = updated)
            _config.value = _config.value.copy(settings = _settings.value)
            savePersistedSettings(_settings.value)
            mcpManager.disconnect(serverId, updated)
        }
    }

    fun refreshAllMcpServers() {
        scope.launch(Dispatchers.IO) {
            mcpManager.syncServers(_settings.value.mcpServers, _settings.value)
        }
    }

    fun installWhsearchPreset() {
        val existing = _settings.value.mcpServers.firstOrNull { it.id == "whsearch" }
        val cfg = existing ?: defaultWhsearchMcpServer()
        if (existing == null) upsertMcpServer(cfg)
        installGitHubMcpServer(cfg.id)
    }

    fun installGitHubMcpServer(serverId: String) {
        if (_mcpInstallingId.value != null) return
        val cfg = _settings.value.mcpServers.firstOrNull { it.id == serverId } ?: return
        val repoUrl = cfg.repoUrl.trim()
        if (repoUrl.isBlank()) {
            _mcpInstallLog.value = "No GitHub repoUrl configured for '${cfg.name}'."
            return
        }
        _mcpInstallingId.value = serverId
        _mcpInstallLog.value = "Cloning & setting up $repoUrl..."
        scope.launch(Dispatchers.IO) {
            try {
                val script = McpManager.buildGitHubInstallScript(repoUrl, cfg.id)
                val res = UbuntuProotRuntime.exec(
                    context = app,
                    command = script,
                    cwd = workspaceDir,
                    env = mapOf("BORER_WORKDIR" to workspaceDir.absolutePath),
                    timeoutMs = 300_000L,
                    stdin = null,
                    settings = _settings.value
                )
                val outLines = res.output.lines()
                val detectedCmd = outLines.firstOrNull { it.startsWith("[BORER_MCP_CMD]") }
                    ?.removePrefix("[BORER_MCP_CMD]")?.trim().orEmpty()
                val detectedArgs = outLines.firstOrNull { it.startsWith("[BORER_MCP_ARGS]") }
                    ?.removePrefix("[BORER_MCP_ARGS]")?.trim().orEmpty()

                val cleanLog = res.output.ifBlank { "(exit=${res.exitCode})" }
                _mcpInstallLog.value = cleanLog

                if (res.exitCode == 0 && detectedCmd.isNotBlank()) {
                    val newCfg = cfg.copy(
                        enabled = true,
                        transport = com.example.data.McpTransport.STDIO,
                        command = detectedCmd,
                        args = if (detectedArgs.isNotBlank()) listOf(detectedArgs) else emptyList()
                    )
                    upsertMcpServer(newCfg)
                    mcpManager.connectOrReconnect(newCfg, _settings.value, _settings.value.mcpServers)
                } else if (res.exitCode == 0) {
                    _mcpInstallLog.value = "$cleanLog\n\nRepo cloned. If it uses a custom entrypoint, set Command & Args in Edit and tap Connect."
                }
            } catch (t: Throwable) {
                _mcpInstallLog.value = "Setup failed: ${t.message}"
            } finally {
                _mcpInstallingId.value = null
            }
        }
    }

    fun clearMcpInstallLog() {
        _mcpInstallLog.value = null
    }

    fun importMcpServersJson(rawJson: String): Result<Int> {
        val parsed = McpManager.parseMcpServersJson(rawJson)
        val imported = parsed.getOrElse { return Result.failure(it) }
        updateSettings { s ->
            val merged = s.mcpServers.toMutableList()
            for (item in imported) {
                val idx = merged.indexOfFirst { it.id == item.id }
                if (idx >= 0) merged[idx] = item else merged.add(item)
            }
            s.copy(mcpServers = merged)
        }
        return Result.success(imported.size)
    }

    fun exportMcpServersJson(): String = McpManager.exportMcpServersJson(_settings.value.mcpServers)

    private fun saveProvidersList(list: List<ProviderProfile>, customized: Boolean) {
        prefs.edit()
            .putBoolean("providers_customized", customized)
            .putString("providers_json", cfgJson.encodeToString(list))
            .apply()
        _config.value = _config.value.copy(providers = list)
        applyConfigToIdleSession()
    }

    private fun applyConfigToIdleSession() {
        val cfg = _config.value
        val first = cfg.providers.firstOrNull()
        val window = cfg.providers.minOfOrNull { it.contextWindow } ?: 32_768
        _console.update {
            it.copy(
                providerConfigured = cfg.providers.isNotEmpty(),
                activeModel = first?.model ?: "unconfigured",
                contextWindow = window,
                contextSource = first?.contextSource?.name?.lowercase() ?: "fallback",
                agentProfile = _settings.value.agentProfile
            )
        }
        syncBackgroundKeepAlive()
        if (!_console.value.busy && !_huntRunning.value) {
            scope.launch(Dispatchers.IO) {
                sessionInitJob?.join()
                sessionMutex.withLock {
                    if (_console.value.busy || _huntRunning.value || !::toolCtx.isInitialized || !::log.isInitialized) {
                        return@withLock
                    }
                    val currentCfg = _config.value
                    val currentFirst = currentCfg.providers.firstOrNull()
                    val currentMsgs = agentSession?.messages?.filter { it.role != "system" }.orEmpty()
                    val currentSkills = _skills.value
                    toolRegistry = buildToolRegistry(currentSkills, _settings.value)
                    val fallbackClient = FallbackClient(currentCfg.providers, log) { attempt, delayMs, reason ->
                        appendInfo("retry $attempt in ${delayMs}ms: $reason")
                    }
                    val extra = listOfNotNull(
                        SkillStore.skillIndex(currentSkills).takeIf { it.isNotBlank() },
                        buildMemoryBrief(currentMsgs.firstOrNull { it.role == "user" }?.content).takeIf { it.isNotBlank() }
                    ).joinToString("\n\n").ifBlank { null }

                    val snap = _console.value
                    agentSession = AgentSession(
                        SessionDeps(
                            profile = currentFirst,
                            client = fallbackClient,
                            contextWindow = fallbackClient.contextWindow,
                            tools = toolRegistry,
                            ctx = toolCtx,
                            settingsProvider = { _settings.value },
                            log = log,
                            extraInstructions = extra,
                            ui = makeUiHooks(),
                            shouldStop = { stopFlag },
                            initialMessages = currentMsgs,
                            initialTotals = snap.totals,
                            initialContextTokens = snap.contextTokens,
                            executionMode = { _console.value.mode.toExecutionMode() },
                            approvalGate = { call -> requestUserApproval(call) },
                            toolsProvider = { buildToolRegistry(_skills.value, _settings.value) }
                        )
                    )
                }
            }
        }
    }

    private fun loadEffectiveAppConfig(settings: BorerSettings): AppConfig {
        val base = ConfigLoader.load(settings)
        val providers = if (prefs.getBoolean("providers_customized", false)) {
            val raw = prefs.getString("providers_json", null)
            if (!raw.isNullOrBlank()) {
                runCatching { cfgJson.decodeFromString<List<ProviderProfile>>(raw) }.getOrDefault(base.providers)
            } else base.providers
        } else {
            base.providers
        }
        val notify = if (prefs.getBoolean("notify_customized", false)) {
            val raw = prefs.getString("notify_json", null)
            if (!raw.isNullOrBlank()) {
                runCatching { cfgJson.decodeFromString<NotifyConfig>(raw) }.getOrDefault(base.notify)
            } else base.notify
        } else {
            base.notify
        }
        return AppConfig(providers = providers, settings = settings, notify = notify)
    }

    private fun loadPersistedSettings(): BorerSettings {
        val rawJson = prefs.getString("settings_json", null)
        if (!rawJson.isNullOrBlank()) {
            runCatching { return cfgJson.decodeFromString<BorerSettings>(rawJson) }
        }
        return BorerSettings(
            inlineBudget = prefs.getInt("inlineBudget", 8 * 1024),
            compactAt = prefs.getFloat("compactAt", 0.8f).toDouble(),
            verbatimTurns = prefs.getInt("verbatimTurns", 12),
            noProgressNudge = prefs.getInt("noProgressNudge", 40),
            showReasoning = prefs.getBoolean("showReasoning", true),
            strictContext = prefs.getBoolean("strictContext", false),
            shellBackend = ShellBackend.fromWire(prefs.getString("shellBackend", ShellBackend.ANDROID_SH.wire)),
            prootDistro = prefs.getString("prootDistro", "ubuntu") ?: "ubuntu",
            prootBinaryPath = prefs.getString("prootBinaryPath", "") ?: "",
            prootRootfsPath = prefs.getString("prootRootfsPath", "") ?: "",
            backgroundKeepAlive = prefs.getBoolean("backgroundKeepAlive", true),
            persistentWakeLock = prefs.getBoolean("persistentWakeLock", false)
        )
    }

    private fun savePersistedSettings(s: BorerSettings) {
        prefs.edit()
            .putString("settings_json", cfgJson.encodeToString(s))
            .putInt("inlineBudget", s.inlineBudget)
            .putFloat("compactAt", s.compactAt.toFloat())
            .putInt("verbatimTurns", s.verbatimTurns)
            .putInt("noProgressNudge", s.noProgressNudge)
            .putBoolean("showReasoning", s.showReasoning)
            .putBoolean("strictContext", s.strictContext)
            .putString("shellBackend", s.shellBackend.wire)
            .putString("prootDistro", s.prootDistro)
            .putString("prootBinaryPath", s.prootBinaryPath)
            .putString("prootRootfsPath", s.prootRootfsPath)
            .putBoolean("backgroundKeepAlive", s.backgroundKeepAlive)
            .putBoolean("persistentWakeLock", s.persistentWakeLock)
            .apply()
    }

    private suspend fun persistSessionRecord(outcome: String?, summary: String?, turnsDelta: Int) {
        withContext(Dispatchers.IO) {
            val id = _console.value.runId
            val existing = runDb.sessionDao().getSession(id)
            val msgs = agentSession?.messages?.filter { it.role != "system" }.orEmpty()
            val title = msgs.firstOrNull { it.role == "user" }?.content?.substringBefore("\n\n<attached-context>")?.take(80)
                ?: existing?.title
                ?: "(session)"
            runDb.sessionDao().upsert(
                SessionEntity(
                    id = id,
                    title = title,
                    model = _console.value.activeModel,
                    baseUrl = _config.value.providers.firstOrNull()?.baseUrl.orEmpty(),
                    contextWindow = _console.value.contextWindow,
                    turns = msgs.count { it.role == "assistant" },
                    messageCount = msgs.size,
                    archived = existing?.archived ?: false,
                    summary = summary?.takeIf { it.isNotBlank() } ?: existing?.summary,
                    outcome = outcome ?: existing?.outcome,
                    createdAt = existing?.createdAt ?: Instant.now().toString(),
                    updatedAt = Instant.now().toString(),
                    totalPromptTokens = _console.value.totals.promptTokens,
                    totalCompletionTokens = _console.value.totals.completionTokens,
                    totalCachedTokens = _console.value.totals.cachedTokens,
                    requestsAllowed = _console.value.requestsAllowed,
                    requestsDenied = _console.value.requestsDenied
                )
            )
        }
    }

    private fun buildMemoryBrief(task: String?): String {
        val hint = task?.take(40)
        val parts = mutableListOf<String>()
        val brief = runCatching { memory.brief(hint) }.getOrDefault("")
        if (brief.isNotBlank()) parts.add("<memory>\n$brief\n</memory>")
        if (!task.isNullOrBlank()) {
            val hostMatch = Regex("\\b([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\\b", RegexOption.IGNORE_CASE).find(task)
            val host = hostMatch?.groupValues?.getOrNull(1)
            if (host != null) {
                runCatching { memory.targetHistory(host) }.getOrNull()?.let { h ->
                    val inner = listOfNotNull(
                        h.profile.takeIf { it.isNotBlank() }?.let { "stack: $it" },
                        h.findings.takeIf { it.isNotBlank() }?.let { "already found: $it" },
                        h.deadEnds.takeIf { it.isNotBlank() }?.let { "do not retry: $it" }
                    ).joinToString("\n")
                    parts.add("<target-history host=\"${h.host}\" visits=\"${h.visits}\">\n$inner\n</target-history>")
                }
            }
        }
        return parts.joinToString("\n")
    }

    private fun flushPendingStreams() {
        val textDelta: String
        val reasoningDelta: String
        synchronized(streamLock) {
            streamFlushJob?.cancel()
            streamFlushJob = null
            textDelta = pendingText.toString()
            reasoningDelta = pendingReasoning.toString()
            pendingText.setLength(0)
            pendingReasoning.setLength(0)
        }
        if (reasoningDelta.isEmpty() && textDelta.isEmpty()) return

        _console.update { cur ->
            val list = cur.entries.toMutableList()
            if (reasoningDelta.isNotEmpty()) {
                val last = list.lastOrNull()
                if (last is TranscriptEntry.Reasoning && !last.collapsed) {
                    list[list.lastIndex] = last.copy(text = last.text + reasoningDelta)
                } else {
                    list.add(TranscriptEntry.Reasoning(UUID.randomUUID().toString(), reasoningDelta))
                }
            }
            if (textDelta.isNotEmpty()) {
                for (i in list.lastIndex downTo 0) {
                    val item = list[i]
                    if (item is TranscriptEntry.Reasoning && !item.collapsed) {
                        list[i] = item.copy(collapsed = true)
                    } else if (item is TranscriptEntry.User || item is TranscriptEntry.Reasoning) {
                        break
                    }
                }
                val last = list.lastOrNull()
                if (last is TranscriptEntry.Assistant) {
                    list[list.lastIndex] = last.copy(text = last.text + textDelta)
                } else {
                    list.add(TranscriptEntry.Assistant(UUID.randomUUID().toString(), textDelta))
                }
            }
            cur.copy(entries = list)
        }
    }

    private fun scheduleStreamFlushLocked() {
        if (pendingText.length + pendingReasoning.length >= 256) {
            streamFlushJob?.cancel()
            streamFlushJob = null
            scope.launch(Dispatchers.Main.immediate) { flushPendingStreams() }
            return
        }
        if (streamFlushJob?.isActive == true) return
        streamFlushJob = scope.launch {
            delay(50L)
            flushPendingStreams()
        }
    }

    private fun makeUiHooks(): UiHooks = object : UiHooks {
        override fun onTurn(turn: Int) {
            _console.update { it.copy(turn = turn) }
            syncBackgroundKeepAlive()
        }

        override fun onStreamState(streaming: Boolean) {
            if (!streaming) flushPendingStreams()
            _console.update { it.copy(isStreaming = streaming) }
        }

        override fun onText(delta: String) {
            if (delta.isEmpty()) return
            synchronized(streamLock) {
                pendingText.append(delta)
                scheduleStreamFlushLocked()
            }
        }

        override fun onReasoning(delta: String) {
            if (delta.isEmpty()) return
            synchronized(streamLock) {
                pendingReasoning.append(delta)
                scheduleStreamFlushLocked()
            }
        }

        override fun onToolStart(call: ToolCallRequest) {
            flushPendingStreams()
            appendEntry(
                TranscriptEntry.ToolExec(
                    id = UUID.randomUUID().toString(),
                    callId = call.id,
                    name = call.name,
                    arguments = call.arguments,
                    running = true
                )
            )
            syncBackgroundKeepAlive()
        }

        override fun onToolEnd(call: ToolCallRequest, ok: Boolean, ms: Long, preview: String) {
            _console.update { cur ->
                val list = cur.entries.toMutableList()
                val idx = list.indexOfLast { it is TranscriptEntry.ToolExec && it.callId == call.id }
                if (idx >= 0) {
                    val existing = list[idx] as TranscriptEntry.ToolExec
                    list[idx] = existing.copy(running = false, ok = ok, ms = ms, preview = preview)
                } else {
                    list.add(
                        TranscriptEntry.ToolExec(
                            id = UUID.randomUUID().toString(),
                            callId = call.id,
                            name = call.name,
                            arguments = call.arguments,
                            running = false,
                            ok = ok,
                            ms = ms,
                            preview = preview
                        )
                    )
                }
                cur.copy(entries = list)
            }
            if (ok && call.name in setOf("write", "edit", "apply_patch")) {
                scope.launch(Dispatchers.IO) { refreshCheckpointCounts() }
            }
            syncBackgroundKeepAlive()
        }

        override fun onUsage(usage: Usage, totals: Usage, contextTokens: Int) {
            _console.update { it.copy(contextTokens = contextTokens, totals = totals) }
        }

        override fun onInfo(text: String) {
            flushPendingStreams()
            appendInfo(text, isWarn = true)
        }
    }

    private fun appendEntry(entry: TranscriptEntry) {
        _console.update { it.copy(entries = it.entries + entry) }
    }

    private fun appendInfo(text: String, isWarn: Boolean = false) {
        appendEntry(TranscriptEntry.Info(UUID.randomUUID().toString(), text, isWarn))
    }

    private fun List<ChatMessage>.toTranscriptEntries(): List<TranscriptEntry> {
        val toolOutputs = filter { it.role == "tool" && !it.toolCallId.isNullOrBlank() }
            .associate { it.toolCallId!! to it.content.orEmpty() }
        val entries = mutableListOf<TranscriptEntry>()
        for (m in this) {
            when (m.role) {
                "user" -> {
                    val txt = m.content.orEmpty()
                    if (txt.startsWith("[harness]")) {
                        entries.add(TranscriptEntry.Info(UUID.randomUUID().toString(), txt))
                    } else {
                        val clean = txt.substringBefore("\n\n<attached-context>")
                        entries.add(TranscriptEntry.User(UUID.randomUUID().toString(), clean))
                    }
                }
                "assistant" -> {
                    if (!m.reasoning.isNullOrBlank()) {
                        entries.add(TranscriptEntry.Reasoning(UUID.randomUUID().toString(), m.reasoning, collapsed = true))
                    }
                    m.toolCalls?.forEach { tc ->
                        val out = toolOutputs[tc.id].orEmpty()
                        val isErr = out.startsWith("error:") || out.startsWith("refused by scope gate:")
                        entries.add(
                            TranscriptEntry.ToolExec(
                                id = UUID.randomUUID().toString(),
                                callId = tc.id,
                                name = tc.name,
                                arguments = tc.arguments,
                                running = false,
                                ok = !isErr,
                                ms = 0,
                                preview = out.take(240).replace('\n', ' ')
                            )
                        )
                    }
                    if (!m.content.isNullOrBlank()) {
                        entries.add(TranscriptEntry.Assistant(UUID.randomUUID().toString(), m.content))
                    }
                }
            }
        }
        return entries
    }

    companion object {
        fun parseTodos(body: String?): List<Pair<Boolean, String>> {
            if (body.isNullOrBlank()) return emptyList()
            val regex = Regex("^\\d+\\.\\s*\\[( |x)]\\s*(.*)$")
            return body.lines().mapNotNull { line ->
                regex.matchEntire(line.trim())?.let { m -> (m.groupValues[1] == "x") to m.groupValues[2] }
            }
        }
    }
}
