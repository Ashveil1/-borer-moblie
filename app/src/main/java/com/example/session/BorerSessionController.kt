package com.example.session

import android.content.Context
import com.example.BorerApplication
import com.example.data.AppConfig
import com.example.data.ArtifactEntity
import com.example.data.ArtifactStore
import com.example.data.BorerSettings
import com.example.data.ConfigLoader
import com.example.data.ContextSource
import com.example.data.EventLog
import com.example.data.EventRecord
import com.example.data.FindingEntity
import com.example.data.HttpLogRecord
import com.example.data.JobEntity
import com.example.data.LearningMemory
import com.example.data.LearningStats
import com.example.data.NoteEntity
import com.example.data.NoteKind
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.RunStore
import com.example.data.SessionEntity
import com.example.data.SessionFiles
import com.example.data.ShellBackend
import com.example.provider.ChatMessage
import com.example.provider.FallbackClient
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
import com.example.tools.bashTool
import com.example.tools.diffTool
import com.example.tools.fsTools
import com.example.tools.httpTool
import com.example.tools.jobTool
import com.example.tools.learningTool
import com.example.tools.metaTools
import com.example.tools.mutateTool
import com.example.tools.netTool
import com.example.tools.reportTool
import com.example.tools.sessionTool
import com.example.tools.skillTool
import com.example.tools.transformTool
import com.example.tools.verifyTool
import com.example.ui.ConsoleUiState
import com.example.ui.HuntItemStatus
import com.example.ui.HuntQueueItem
import com.example.ui.RunMode
import com.example.ui.TranscriptEntry
import com.example.ui.screens.ToolSummaryFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

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
        ConsoleUiState(mode = if (_settings.value.defaultAutonomous) RunMode.AUTONOMOUS else RunMode.CHAT)
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
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolCtx: ToolContext
    private var agentSession: AgentSession? = null
    private var sessionInitJob: Job? = null
    private var activeRunJob: Job? = null
    private var huntJob: Job? = null
    private var stopFlag = false
    private var observersJob: Job? = null

    init {
        BackgroundKeepAliveController.onStopRun = {
            if (_huntRunning.value) stopHuntQueue() else forceCancelTurn()
        }
        BackgroundKeepAliveController.onReleaseWakeLock = {
            setPersistentWakeLock(false)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { runDb.sessionDao().deleteEmptySessions() }
        }
        startNewSession()
        detectCatalogContextWindows()
        syncBackgroundKeepAlive()
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
                modeLabel = if (c.mode == RunMode.AUTONOMOUS) "Autonomous" else "Chat",
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
            stopRequested = false,
            contextWindow = window,
            contextSource = profile?.contextSource?.name?.lowercase() ?: "fallback",
            activeModel = profile?.model ?: "unconfigured",
            providerConfigured = cfg.providers.isNotEmpty(),
            entries = restoredEntries,
            learningStats = _console.value.learningStats
        )
        syncBackgroundKeepAlive()

        sessionInitJob = scope.launch(Dispatchers.IO) {
            val runDir = File(runsDir, runId).apply { mkdirs() }
            childProxy?.close()
            gate = EngagementGate()
            log = EventLog(runDir)
            val proxy = ChildProxyServer(gate, log).also { childProxy = it }
            val proxyUrl = proxy.start()
            artifactStore = ArtifactStore(runDir)
            store = RunStore(runId, runDb)

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
                shellExecutor = { command, cwd, env, timeoutMs, stdin ->
                    UbuntuProotRuntime.exec(app, command, cwd, env, timeoutMs, stdin, _settings.value)
                }
            )

            val currentSkills = _skills.value
            toolRegistry = ToolRegistry(
                fsTools + listOf(
                    bashTool, jobTool, sessionTool, httpTool, netTool,
                    transformTool, diffTool, mutateTool, verifyTool, reportTool,
                    learningTool(memory)
                ) + metaTools + (if (currentSkills.isNotEmpty()) listOf(skillTool(currentSkills)) else emptyList())
            )

            val fallbackClient = FallbackClient(currentCfg.providers, log) { attempt, delayMs, reason ->
                appendInfo("retry $attempt in ${delayMs}ms: $reason")
            }

            val stats = memory.stats()
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
                    initialMessages = initialMessages
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

            withContext(Dispatchers.Main) {
                _console.update {
                    it.copy(
                        contextWindow = fallbackClient.contextWindow,
                        contextSource = currentProfile?.contextSource?.name?.lowercase() ?: "fallback",
                        activeModel = currentProfile?.model ?: "unconfigured",
                        providerConfigured = currentCfg.providers.isNotEmpty(),
                        learningStats = stats
                    )
                }
                observeLogStreams()
                syncBackgroundKeepAlive()
            }
        }
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
                appendInfo("commands: /usage · /mode · /stop · /clear · /help — type any prompt to run or queue")
                return
            }
            text == "/usage" -> {
                val c = _console.value
                appendInfo("context ${c.contextTokens}/${c.contextWindow} tokens · total prompt=${c.totals.promptTokens} completion=${c.totals.completionTokens}")
                return
            }
            text == "/mode" -> {
                val next = if (_console.value.mode == RunMode.CHAT) RunMode.AUTONOMOUS else RunMode.CHAT
                setRunMode(next)
                appendInfo("mode switched to ${if (next == RunMode.AUTONOMOUS) "Autonomous" else "Chat"}")
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
                val maxTurns = if (_console.value.mode == RunMode.AUTONOMOUS) 400 else 60
                val result = session.run(text, maxTurns = maxTurns)
                runOutcome = result.outcome
                runSummary = result.summary
                runTurns = result.turns
                if (_console.value.mode == RunMode.AUTONOMOUS && result.summary.isNotBlank()) {
                    withContext(Dispatchers.IO) { SessionFiles.saveSummary(toolCtx.runDir, result.summary) }
                    appendInfo("autonomous run finished (${result.outcome}) in ${result.turns} turns")
                    val findingList = withContext(Dispatchers.IO) { store.listFindings() }
                    val findingLines = findingList.joinToString("\n") { "- ${it.severity} ${it.asset} — ${it.title}" }
                    NotifySender.send(
                        _config.value.notify,
                        "borer: ${result.outcome} — ${findingList.size} finding(s)",
                        listOf(result.summary.take(2500), "", "run: ${_console.value.runId}", findingLines).joinToString("\n")
                    )
                }
            } catch (_: CancellationException) {
                runOutcome = "cancelled"
                appendInfo("turn cancelled", isWarn = true)
            } catch (t: Throwable) {
                runOutcome = "error"
                appendInfo("turn failed: ${t.message ?: t::class.simpleName}", isWarn = true)
            } finally {
                withContext(NonCancellable) {
                    val hasMessages = agentSession?.messages?.any { it.role == "user" || it.role == "assistant" } == true
                    if (hasMessages) {
                        persistSessionRecord(runOutcome, runSummary, runTurns)
                    }
                }
                if (activeRunJob !== myJob) return@launch
                activeRunJob = null
                if (stopFlag || runOutcome == "cancelled") {
                    _console.update { it.copy(busy = false, stopRequested = false, queuedPrompts = emptyList()) }
                    syncBackgroundKeepAlive()
                } else {
                    var next: String? = null
                    _console.update { cur ->
                        next = cur.queuedPrompts.firstOrNull()
                        cur.copy(
                            busy = false,
                            stopRequested = false,
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

    fun requestStop() {
        stopFlag = true
        _console.update { it.copy(stopRequested = true) }
        syncBackgroundKeepAlive()
    }

    fun forceCancelTurn() {
        stopFlag = true
        val job = activeRunJob
        activeRunJob = null
        job?.cancel()
        _console.update { it.copy(busy = false, stopRequested = false, queuedPrompts = emptyList()) }
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
            File(runsDir, id).deleteRecursively()
            if (id == _activeRunId.value) {
                withContext(Dispatchers.Main) { startNewSession() }
            }
        }
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
                        "Hunt for vulnerabilities in $target. Map the surface, test parameters, verify every suspected issue with a reproducible command, write a report for each confirmed finding, and finish when nothing is left to try."
                    }

                    var runOutcome: String? = null
                    var runSummary: String? = null
                    var runTurns = 0
                    try {
                        stopFlag = false
                        setRunMode(RunMode.AUTONOMOUS)
                        startNewSession(runId = runId, taskHint = target)
                        sessionInitJob?.join()
                        appendEntry(TranscriptEntry.User(UUID.randomUUID().toString(), prompt))
                        _console.update { it.copy(busy = true, stopRequested = false) }
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
                        withContext(NonCancellable) {
                            if (agentSession?.messages?.any { it.role == "user" || it.role == "assistant" } == true) {
                                persistSessionRecord(runOutcome, runSummary, runTurns)
                            }
                        }
                        _console.update { it.copy(busy = false, stopRequested = false) }
                    }
                }
            } finally {
                _huntRunning.value = false
                _console.update { it.copy(busy = false, stopRequested = false) }
                syncBackgroundKeepAlive()
            }
        }
    }

    fun stopHuntQueue() {
        _huntRunning.value = false
        stopFlag = true
        huntJob?.cancel()
        _console.update { it.copy(busy = false, stopRequested = false) }
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

    // --- Memory & Skills & Settings ---

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
        val next = transform(_settings.value)
        _settings.value = next
        _config.value = _config.value.copy(settings = next)
        savePersistedSettings(next)
        refreshProotDiagnostics()
        syncBackgroundKeepAlive()
    }

    fun resetAllSettingsToDefaults() {
        val def = BorerSettings()
        _settings.value = def
        _config.value = _config.value.copy(settings = def)
        savePersistedSettings(def)
        refreshProotDiagnostics()
        syncBackgroundKeepAlive()
    }

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
                contextSource = first?.contextSource?.name?.lowercase() ?: "fallback"
            )
        }
        syncBackgroundKeepAlive()
        if (!_console.value.busy) {
            scope.launch(Dispatchers.IO) {
                sessionInitJob?.join()
                if (_console.value.busy || !::toolRegistry.isInitialized || !::toolCtx.isInitialized) return@launch
                val currentCfg = _config.value
                val currentFirst = currentCfg.providers.firstOrNull()
                val currentMsgs = agentSession?.messages?.filter { it.role != "system" }.orEmpty()
                val currentSkills = _skills.value
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
                        initialContextTokens = snap.contextTokens
                    )
                )
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
            val title = msgs.firstOrNull { it.role == "user" }?.content?.take(80) ?: existing?.title ?: "(session)"
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
        val brief = memory.brief(hint)
        if (brief.isNotBlank()) parts.add("<memory>\n$brief\n</memory>")
        if (!task.isNullOrBlank()) {
            val hostMatch = Regex("\\b([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\\b", RegexOption.IGNORE_CASE).find(task)
            val host = hostMatch?.groupValues?.getOrNull(1)
            if (host != null) {
                memory.targetHistory(host)?.let { h ->
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

    private fun makeUiHooks(): UiHooks = object : UiHooks {
        override fun onTurn(turn: Int) {
            _console.update { it.copy(turn = turn) }
            syncBackgroundKeepAlive()
        }

        override fun onText(delta: String) {
            if (delta.isEmpty()) return
            _console.update { cur ->
                val list = cur.entries.toMutableList()
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
                    list[list.lastIndex] = last.copy(text = last.text + delta)
                } else {
                    list.add(TranscriptEntry.Assistant(UUID.randomUUID().toString(), delta))
                }
                cur.copy(entries = list)
            }
        }

        override fun onReasoning(delta: String) {
            if (delta.isEmpty()) return
            _console.update { cur ->
                val list = cur.entries.toMutableList()
                val last = list.lastOrNull()
                if (last is TranscriptEntry.Reasoning && !last.collapsed) {
                    list[list.lastIndex] = last.copy(text = last.text + delta)
                } else {
                    list.add(TranscriptEntry.Reasoning(UUID.randomUUID().toString(), delta))
                }
                cur.copy(entries = list)
            }
        }

        override fun onToolStart(call: ToolCallRequest) {
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
            syncBackgroundKeepAlive()
        }

        override fun onUsage(usage: Usage, totals: Usage, contextTokens: Int) {
            _console.update { it.copy(contextTokens = contextTokens, totals = totals) }
        }

        override fun onInfo(text: String) {
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
                        entries.add(TranscriptEntry.User(UUID.randomUUID().toString(), txt))
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
