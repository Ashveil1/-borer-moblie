package com.example

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgentProfile
import com.example.data.ApiKind
import com.example.data.ArtifactStore
import com.example.data.BorerSettings
import com.example.data.CheckpointManager
import com.example.data.EventLog
import com.example.data.ExecutionMode
import com.example.data.McpServerConfig
import com.example.data.McpTransport
import com.example.data.ProviderProfile
import com.example.data.RunDatabase
import com.example.data.RunStore
import com.example.data.defaultWhsearchMcpServer
import com.example.mcp.McpConnectionStatus
import com.example.mcp.McpManager
import com.example.provider.ChatMessage
import com.example.provider.CompletionRequest
import com.example.provider.FallbackClient
import com.example.provider.OpenAICompatClient
import com.example.provider.ProviderEvent
import com.example.provider.ToolCallRequest
import com.example.scope.EngagementGate
import com.example.session.AgentSession
import com.example.session.SessionDeps
import com.example.session.buildSystemPrompt
import com.example.tools.ToolContext
import com.example.tools.ToolRegistry
import com.example.tools.applyPatchTool
import com.example.tools.fsTools
import com.example.tools.httpTool
import com.example.tools.metaTools
import com.example.tools.taskTool
import com.example.tools.webFetchTool
import com.example.tools.webSearchTool
import com.example.ui.ApprovalDecision
import com.example.ui.BorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BorerHarnessTest {

    private lateinit var tempDir: File
    private lateinit var db: RunDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<BorerApplication>()
        tempDir = createTempDirectory("borer-harness-test").toFile()
        db = Room.inMemoryDatabaseBuilder(context, RunDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun roomDatabase_brokenSchemaVersion_wipesAndRecoversGracefully() {
        runBlocking(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<BorerApplication>()
            val dbFile = context.getDatabasePath("borer_recovery_test.db")
            dbFile.parentFile?.mkdirs()
            if (dbFile.exists()) dbFile.delete()

            // Create a broken schema with an incompatible future version 99 and missing tables
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
                sqlite.execSQL("CREATE TABLE broken_legacy (id INTEGER PRIMARY KEY, junk TEXT)")
                sqlite.version = 99
            }

            val recovered = Room.databaseBuilder(context, RunDatabase::class.java, "borer_recovery_test.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            try {
                assertEquals(null, recovered.sessionDao().getSession("missing"))
                assertTrue(recovered.checkpointDao().listCheckpointsSync("missing").isEmpty())
            } finally {
                recovered.close()
                dbFile.delete()
            }
        }
    }

    @Test
    fun autonomousCompaction_foldsSingleTurnToolGroups_withoutOrphaningToolPairs() {
        runBlocking(Dispatchers.IO) {
            val runDir = File(tempDir, "run-compact").apply { mkdirs() }
            val workDir = File(tempDir, "work-compact").apply { mkdirs() }
            val ctx = makeToolContext(runDir, workDir)
            val settings = BorerSettings(verbatimTurns = 4, compactAt = 0.8)

            val initial = mutableListOf(ChatMessage(role = "user", content = "Scan everything autonomously."))
            repeat(15) { i ->
                val callId = "call_$i"
                initial.add(
                    ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(ToolCallRequest(id = callId, name = "notes", arguments = """{"action":"list"}"""))
                    )
                )
                initial.add(ChatMessage(role = "tool", content = "result_$i", toolCallId = callId))
            }

            val session = AgentSession(
                SessionDeps(
                    profile = null,
                    client = FallbackClient(emptyList()),
                    contextWindow = 32_768,
                    tools = ToolRegistry(metaTools),
                    ctx = ctx,
                    settingsProvider = { settings },
                    log = ctx.log,
                    initialMessages = initial
                )
            )

            val res = session.compactNow(force = true)
            assertTrue("Expected autonomous single-prompt tool loops to compact", res.compacted)
            assertTrue(res.droppedMessages > 0)

            // Verify every remaining assistant(toolCalls) message is immediately followed by matching tool outputs
            val msgs = session.messages
            for (idx in msgs.indices) {
                val m = msgs[idx]
                if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                    val expectedIds = m.toolCalls!!.map { it.id }.toSet()
                    val actualIds = mutableSetOf<String>()
                    var j = idx + 1
                    while (j < msgs.size && msgs[j].role == "tool") {
                        msgs[j].toolCallId?.let { actualIds.add(it) }
                        j++
                    }
                    assertEquals("Tool call/output pairing must remain intact after compaction", expectedIds, actualIds)
                }
                if (m.role == "tool") {
                    val prev = msgs[idx - 1]
                    assertTrue(
                        "Orphaned tool message at $idx",
                        prev.role == "tool" || (prev.role == "assistant" && !prev.toolCalls.isNullOrEmpty())
                    )
                }
            }
        }
    }

    @Test
    fun midTurnCancellation_appendsSyntheticToolOutputsForRemainingCalls() = runBlocking {
        val sse = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"notes","arguments":"{\"action\":\"list\"}"}},{"index":1,"id":"c2","type":"function","function":{"name":"notes","arguments":"{\"action\":\"list\"}"}}]}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        serveResponses(listOf(200 to ("text/event-stream" to sse))) { port ->
            val runDir = File(tempDir, "run-cancel").apply { mkdirs() }
            val workDir = File(tempDir, "work-cancel").apply { mkdirs() }
            var stopRequested = false
            val gate = EngagementGate()
            val log = EventLog(runDir)
            val store = RunStore("run-cancel", db)
            val baseCtx = ToolContext(
                runDir = runDir,
                workdir = workDir,
                gate = gate,
                log = log,
                artifacts = ArtifactStore(runDir),
                store = store,
                db = db,
                settingsProvider = { BorerSettings() },
                isCancelled = { stopRequested },
                notify = {}
            )
            // Intercept first tool completion to simulate operator tapping Stop mid-turn
            val spyTool = object : com.example.tools.Tool {
                override val name = "notes"
                override val description = "notes"
                override val parameters = metaTools.first { it.name == "notes" }.parameters
                override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): com.example.tools.ToolResult {
                    stopRequested = true
                    return com.example.tools.ToolResult("ok-first")
                }
            }
            val profile = localProfile(port)
            val session = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), log),
                    contextWindow = 32_768,
                    tools = ToolRegistry(listOf(spyTool)),
                    ctx = baseCtx,
                    log = log,
                    shouldStop = { stopRequested }
                )
            )
            val res = session.run("Run two tools")
            assertEquals("stopped", res.outcome)
            val toolMsgs = res.messages.filter { it.role == "tool" }
            assertEquals("Both c1 and c2 must have tool outputs", 2, toolMsgs.size)
            assertTrue(toolMsgs[1].content?.contains("cancelled") == true)
        }
    }

    @Test
    fun doomLoopBreaker_triggersSoftNudgeThenStopsOnRepeatedCalls() = runBlocking {
        val repeatToolSse = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c_loop","type":"function","function":{"name":"notes","arguments":"{\"action\":\"list\"}"}}]}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        serveResponses(List(6) { 200 to ("text/event-stream" to repeatToolSse) }) { port ->
            val runDir = File(tempDir, "run-doom").apply { mkdirs() }
            val workDir = File(tempDir, "work-doom").apply { mkdirs() }
            val ctx = makeToolContext(runDir, workDir)
            val settings = BorerSettings(doomLoopThreshold = 2, softStopErrors = 50, noProgressNudge = 50)
            val profile = localProfile(port)
            val session = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), ctx.log),
                    contextWindow = 32_768,
                    tools = ToolRegistry(metaTools),
                    ctx = ctx,
                    settingsProvider = { settings },
                    log = ctx.log
                )
            )
            val res = session.run("Loop forever", maxTurns = 10)
            assertEquals("blocked", res.outcome)
            assertTrue(res.summary.contains("Doom-loop", ignoreCase = true))
        }
    }

    @Test
    fun openAiClient_parsesNonStreamJson_parallelToolCallsWithoutIndex_andModelCatalog() = runBlocking {
        val nonStreamJson = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "JSON reply",
                  "tool_calls": [
                    {"id": "t1", "type": "function", "function": {"name": "read", "arguments": "{\"path\":\"a.txt\"}"}},
                    {"id": "t2", "type": "function", "function": {"name": "ls", "arguments": "{}"}}
                  ]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 20, "completion_tokens": 5}
            }
        """.trimIndent()
        val modelsJson = """
            {
              "data": [
                {"id": "openrouter/model-a", "top_provider": {"context_length": 200000}},
                {"id": "gemini-model-b", "input_token_limit": 1048576}
              ]
            }
        """.trimIndent()

        serveResponses(
            listOf(
                200 to ("application/json" to nonStreamJson),
                200 to ("application/json" to modelsJson)
            )
        ) { port ->
            val client = OpenAICompatClient(localProfile(port))
            val events = mutableListOf<ProviderEvent>()
            client.stream(CompletionRequest(listOf(ChatMessage("user", "hi")), emptyList())).collect {
                events.add(it)
            }
            val text = events.filterIsInstance<ProviderEvent.Text>().joinToString("") { it.delta }
            val calls = events.filterIsInstance<ProviderEvent.ToolCall>()
            assertEquals("JSON reply", text)
            assertEquals(2, calls.size)
            assertEquals("t1", calls[0].id)
            assertEquals("t2", calls[1].id)
            assertEquals(0, calls[0].index)
            assertEquals(1, calls[1].index)

            val catalog = client.catalog()
            assertEquals(2, catalog.size)
            assertEquals(200_000, catalog[0].context)
            assertEquals(1_048_576, catalog[1].context)
        }
    }

    @Test
    fun applyPatch_andCheckpointManager_computeDiffAndUndoRestoresWorkspace() = runBlocking(Dispatchers.IO) {
        val runDir = File(tempDir, "run-patch").apply { mkdirs() }
        val workDir = File(tempDir, "work-patch").apply { mkdirs() }
        val existing = File(workDir, "hello.txt").apply {
            writeText("line1\nline2\nline3\n")
        }
        val cp = CheckpointManager("run-patch", runDir, workDir, db)
        var turn = 1
        val ctx = makeToolContext(
            runDir = runDir,
            workDir = workDir,
            checkpoints = cp,
            currentTurnProvider = { turn }
        )

        val patch = """
            *** Begin Patch
            *** Update File: hello.txt
            @@
             line1
            -line2
            +line2-updated
             line3
            *** Add File: added.txt
            +new file line
            *** End Patch
        """.trimIndent()

        val res = applyPatchTool.run(buildJsonObject { put("patch", patch) }, ctx)
        assertFalse(res.content, res.isError)
        assertEquals("line1\nline2-updated\nline3\n", existing.readText())
        val addedFile = File(workDir, "added.txt")
        assertTrue(addedFile.exists())
        assertEquals("new file line", addedFile.readText())

        val diffs = cp.sessionDiffs()
        assertEquals(2, diffs.size)
        val helloDiff = diffs.first { it.relativePath == "hello.txt" }
        assertEquals(1, helloDiff.addedLines)
        assertEquals(1, helloDiff.removedLines)

        val undo = cp.undoLastTurn()
        assertTrue(undo.message, undo.ok)
        assertEquals("line1\nline2\nline3\n", existing.readText())
        assertFalse("Newly added file should be removed on undo", addedFile.exists())
        assertTrue(cp.sessionDiffs().isEmpty())
    }

    @Test
    fun planMode_filtersTools_injectsAgentsMd_andSupervisedApprovalBlocksDeniedCall() = runBlocking {
        val runDir = File(tempDir, "run-policy").apply { mkdirs() }
        val workDir = File(tempDir, "work-policy").apply { mkdirs() }
        File(workDir, "AGENTS.md").writeText("Never touch production endpoints.")
        val targetFile = File(workDir, "secret.txt")

        // Prompt injection check
        val dummyDeps = SessionDeps(
            profile = null,
            client = FallbackClient(emptyList()),
            contextWindow = 32_768,
            tools = ToolRegistry(fsTools + metaTools),
            ctx = makeToolContext(runDir, workDir),
            executionMode = { ExecutionMode.PLAN },
            log = EventLog(runDir)
        )
        val sysPrompt = buildSystemPrompt(dummyDeps)
        assertTrue(sysPrompt.contains("Never touch production endpoints."))
        assertTrue(sysPrompt.contains("Execution Mode: PLAN"))

        // Plan mode tries to invoke `write` (blocked) then finishes
        val sseTurn1 = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"w1","type":"function","function":{"name":"write","arguments":"{\"path\":\"secret.txt\",\"content\":\"pwned\"}"}}]}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")
        val sseTurn2 = listOf(
            """data: {"choices":[{"delta":{"content":"Stopped safely."}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        serveResponses(
            listOf(
                200 to ("text/event-stream" to sseTurn1),
                200 to ("text/event-stream" to sseTurn2),
                200 to ("text/event-stream" to sseTurn1),
                200 to ("text/event-stream" to sseTurn2)
            )
        ) { port ->
            val profile = localProfile(port)
            val ctx = makeToolContext(runDir, workDir)

            // 1) PLAN mode blocks write
            val planSession = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), ctx.log),
                    contextWindow = 32_768,
                    tools = ToolRegistry(fsTools + metaTools),
                    ctx = ctx,
                    log = ctx.log,
                    executionMode = { ExecutionMode.PLAN }
                )
            )
            val planRes = planSession.run("Try writing file")
            assertFalse("PLAN mode must not execute write tool", targetFile.exists())
            assertTrue(planRes.messages.any { it.role == "tool" && it.content?.contains("blocked in Plan mode") == true })

            // 2) SUPERVISED mode honors Deny decision
            val approvalCalls = AtomicInteger(0)
            val supervisedSession = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), ctx.log),
                    contextWindow = 32_768,
                    tools = ToolRegistry(fsTools + metaTools),
                    ctx = ctx,
                    settingsProvider = { BorerSettings(supervisedApprovals = true) },
                    log = ctx.log,
                    executionMode = { ExecutionMode.SUPERVISED },
                    approvalGate = {
                        approvalCalls.incrementAndGet()
                        ApprovalDecision.Deny("operator refused write")
                    }
                )
            )
            val supRes = supervisedSession.run("Write secret.txt")
            assertEquals(1, approvalCalls.get())
            assertFalse("Denied write must not touch disk", targetFile.exists())
            assertTrue(supRes.messages.any { it.role == "tool" && it.content?.contains("operator refused write") == true })
        }
    }

    @Test
    fun taskTool_blocksNestedSubagent_andDelegatesParentCall() = runBlocking {
        val runDir = File(tempDir, "run-task").apply { mkdirs() }
        val workDir = File(tempDir, "work-task").apply { mkdirs() }
        val nestedCtx = makeToolContext(runDir, workDir, isSubagent = true)
        val nestedRes = taskTool.run(buildJsonObject {
            put("role", "explore")
            put("prompt", "search files")
        }, nestedCtx)
        assertTrue(nestedRes.isError)
        assertTrue(nestedRes.content.contains("nested"))

        val parentCtx = makeToolContext(
            runDir = runDir,
            workDir = workDir,
            isSubagent = false,
            subagentRunner = { role: String, prompt: String, maxTurns: Int ->
                com.example.tools.ToolResult("subagent[$role/$maxTurns]: $prompt")
            }
        )
        val parentRes = taskTool.run(buildJsonObject {
            put("role", "explore")
            put("prompt", "map endpoints")
            put("max_turns", 5)
        }, parentCtx)
        assertFalse(parentRes.isError)
        assertEquals("subagent[explore/5]: map endpoints", parentRes.content)
    }

    @Test
    fun agentProfile_buildsPersonaPrompts_andGeneralAnswersConversationallyInOneTurn() = runBlocking {
        val runDir = File(tempDir, "run-persona").apply { mkdirs() }
        val workDir = File(tempDir, "work-persona").apply { mkdirs() }
        val ctx = makeToolContext(runDir, workDir)

        fun promptFor(p: AgentProfile): String = buildSystemPrompt(
            SessionDeps(
                profile = null,
                client = FallbackClient(emptyList()),
                contextWindow = 32_768,
                tools = ToolRegistry(metaTools + webFetchTool),
                ctx = ctx,
                settingsProvider = { BorerSettings(agentProfile = p) },
                log = ctx.log
            )
        )

        val generalPrompt = promptFor(AgentProfile.GENERAL)
        assertTrue(generalPrompt.contains("general-purpose AI assistant"))
        assertTrue(generalPrompt.contains("well-structured Markdown"))
        assertFalse(generalPrompt.contains("autonomous security testing agent"))

        val codingPrompt = promptFor(AgentProfile.CODING)
        assertTrue(codingPrompt.contains("Engineering workflow:"))

        val researchPrompt = promptFor(AgentProfile.RESEARCH)
        assertTrue(researchPrompt.contains("Research workflow:"))
        assertTrue(researchPrompt.contains("web_search"))

        val securityPrompt = promptFor(AgentProfile.SECURITY)
        assertTrue(securityPrompt.contains("autonomous security testing agent"))
        assertTrue(securityPrompt.contains("plain text only"))

        assertTrue("web_search" in AgentSession.PLAN_ALLOWED_TOOLS)
        assertTrue("web_fetch" in AgentSession.PLAN_ALLOWED_TOOLS)

        val directReplySse = listOf(
            """data: {"choices":[{"delta":{"content":"HTTP 404 means the requested resource was not found."}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        serveResponses(listOf(200 to ("text/event-stream" to directReplySse))) { port ->
            val profile = localProfile(port)
            val session = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), ctx.log),
                    contextWindow = 32_768,
                    tools = ToolRegistry(metaTools),
                    ctx = ctx,
                    settingsProvider = { BorerSettings(agentProfile = AgentProfile.GENERAL) },
                    log = ctx.log,
                    executionMode = { ExecutionMode.SUPERVISED }
                )
            )
            val res = session.run("What does HTTP 404 mean?")
            assertEquals(1, res.turns)
            assertEquals("HTTP 404 means the requested resource was not found.", res.messages.last().content)
        }
    }

    @Test
    fun webFetch_andHttpExtractText_convertHtmlToCleanReadableText() = runBlocking {
        val html = """
            <!doctype html>
            <html>
              <head>
                <title>Guide &amp; Reference</title>
                <style>body { color: red; }</style>
                <script>console.log("ignore-me");</script>
              </head>
              <body>
                <h1>Getting Started</h1>
                <p>Use clean &quot;tools&quot; on Android.</p>
                <ul><li>Fast</li><li>Reliable</li></ul>
              </body>
            </html>
        """.trimIndent()

        serveResponses(
            listOf(
                200 to ("text/html; charset=utf-8" to html),
                200 to ("text/html; charset=utf-8" to html)
            )
        ) { port ->
            val runDir = File(tempDir, "run-webfetch").apply { mkdirs() }
            val workDir = File(tempDir, "work-webfetch").apply { mkdirs() }
            val ctx = makeToolContext(runDir, workDir)

            val fetchRes = webFetchTool.run(
                buildJsonObject { put("url", "http://127.0.0.1:$port/docs") },
                ctx
            )
            assertFalse(fetchRes.content, fetchRes.isError)
            assertTrue(fetchRes.content.contains("# Guide & Reference"))
            assertTrue(fetchRes.content.contains("Getting Started"))
            assertTrue(fetchRes.content.contains("Use clean \"tools\" on Android."))
            assertFalse("Script contents should be stripped", fetchRes.content.contains("ignore-me"))
            assertFalse("Style contents should be stripped", fetchRes.content.contains("color: red"))

            val httpRes = httpTool.run(
                buildJsonObject {
                    put("url", "http://127.0.0.1:$port/docs")
                    put("extract_text", true)
                },
                ctx
            )
            assertFalse(httpRes.content, httpRes.isError)
            assertTrue(httpRes.content.contains("# Guide & Reference"))
            assertFalse(httpRes.content.contains("ignore-me"))
        }
    }

    @Test
    fun viewModel_setAgentProfile_andPersonaSlashCommand_updateState() {
        val app = ApplicationProvider.getApplicationContext<BorerApplication>()
        val vm = BorerViewModel(app)
        try {
            vm.setAgentProfile(AgentProfile.GENERAL)
            assertEquals(AgentProfile.GENERAL, vm.console.value.agentProfile)

            vm.setAgentProfile(AgentProfile.CODING)
            assertEquals(AgentProfile.CODING, vm.console.value.agentProfile)
            assertEquals(AgentProfile.CODING, vm.settings.value.agentProfile)

            vm.submitPrompt("/persona research")
            assertEquals(AgentProfile.RESEARCH, vm.console.value.agentProfile)

            vm.submitPrompt("/persona security")
            assertEquals(AgentProfile.SECURITY, vm.console.value.agentProfile)
        } finally {
            vm.setAgentProfile(AgentProfile.GENERAL)
        }
    }

    @Test
    fun mcpClient_fullPrimitives_andWebSearchRouting_andJsonImportExport() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<BorerApplication>()
        val runDir = File(tempDir, "run-mcp").apply { mkdirs() }
        val workDir = File(tempDir, "work-mcp").apply { mkdirs() }
        val manager = McpManager(app, workDir)

        val defaultWh = defaultWhsearchMcpServer()
        assertEquals("whsearch", defaultWh.id)
        assertTrue(defaultWh.useForWebSearch)
        assertTrue(defaultWh.repoUrl.contains("Ashveil1/whsearch"))

        val recordedCalls = mutableListOf<Pair<String, String>>()
        serveMcpJsonRpc(recordedCalls) { port ->
            val cfg = McpServerConfig(
                id = "whsearch",
                name = "whsearch",
                enabled = true,
                transport = McpTransport.STREAMABLE_HTTP,
                url = "http://127.0.0.1:$port/mcp",
                repoUrl = "https://github.com/Ashveil1/whsearch",
                useForWebSearch = true
            )
            val state = manager.connectOrReconnect(cfg, BorerSettings(), listOf(cfg))
            try {
                assertEquals(state.errorMessage.orEmpty(), McpConnectionStatus.CONNECTED, state.status)
                assertEquals("whsearch-mock", state.serverName)
                assertEquals(1, state.tools.size)
                assertEquals(1, state.resources.size)
                assertEquals(1, state.resourceTemplates.size)
                assertEquals(1, state.prompts.size)

                // Verify prompt & Plan mode allowlist
                val sysSection = manager.buildSystemPromptSection()
                assertTrue(sysSection.contains("<mcp-servers>"))
                assertTrue(sysSection.contains("[web_search provider]"))
                assertTrue(AgentSession.isPlanAllowedTool("mcp_resource"))
                assertTrue(AgentSession.isPlanAllowedTool("mcp_prompt"))
                assertTrue(AgentSession.isPlanAllowedTool("mcp__whsearch__whsearch"))
                assertFalse(AgentSession.isPlanAllowedTool("mcp__dev__write_file"))

                val ctx = makeToolContext(runDir, workDir, mcpManager = manager)

                // 1) web_search routes to whsearch MCP tool and maps 'q' + 'max_results' from inputSchema
                val wsRes = webSearchTool.run(
                    buildJsonObject {
                        put("query", "android mcp protocol")
                        put("limit", 4)
                    },
                    ctx
                )
                assertFalse(wsRes.content, wsRes.isError)
                assertTrue(wsRes.content.contains("[via MCP whsearch/whsearch]"))
                assertTrue(wsRes.content.contains("Hit for android mcp protocol (max=4)"))

                // 2) Dynamic MCP tool execution (mcp__whsearch__whsearch)
                val dynTools = manager.buildDynamicTools()
                assertEquals(1, dynTools.size)
                assertEquals("mcp__whsearch__whsearch", dynTools.first().name)
                val dynRes = dynTools.first().run(buildJsonObject { put("q", "kotlin"); put("max_results", 2) }, ctx)
                assertFalse(dynRes.isError)
                assertTrue(dynRes.content.contains("Hit for kotlin (max=2)"))

                // 3) Built-in mcp_resource & mcp_prompt meta-tools
                val metas = manager.buildBuiltinMetaTools().associateBy { it.name }
                val resRead = metas.getValue("mcp_resource").run(
                    buildJsonObject {
                        put("action", "read")
                        put("uri", "whsearch://status")
                    },
                    ctx
                )
                assertFalse(resRead.isError)
                assertTrue(resRead.content.contains("whsearch-online"))

                val promptGet = metas.getValue("mcp_prompt").run(
                    buildJsonObject {
                        put("action", "get")
                        put("name", "deep_dive")
                        put("arguments", buildJsonObject { put("topic", "SSE") })
                    },
                    ctx
                )
                assertFalse(promptGet.isError)
                assertTrue(promptGet.content.contains("Research SSE thoroughly"))

                // 4) Export / Import JSON roundtrip
                val exported = McpManager.exportMcpServersJson(listOf(cfg))
                val parsed = McpManager.parseMcpServersJson(exported).getOrThrow()
                assertEquals(1, parsed.size)
                assertEquals("whsearch", parsed[0].id)
                assertEquals(McpTransport.STREAMABLE_HTTP, parsed[0].transport)
                assertTrue(parsed[0].useForWebSearch)
                assertTrue(recordedCalls.any { it.first == "initialize" })
                assertTrue(recordedCalls.any { it.first == "tools/call" })
            } finally {
                manager.closeAll()
            }
        }
    }

    private fun localProfile(port: Int) = ProviderProfile(
        id = "local",
        name = "local",
        baseUrl = "http://127.0.0.1:$port/v1",
        model = "test-model",
        apiKey = "sk-test",
        api = ApiKind.CHAT
    )

    private fun makeToolContext(
        runDir: File,
        workDir: File,
        checkpoints: CheckpointManager? = null,
        currentTurnProvider: () -> Int = { 0 },
        isSubagent: Boolean = false,
        mcpManager: McpManager? = null,
        subagentRunner: (suspend (role: String, prompt: String, maxTurns: Int) -> com.example.tools.ToolResult)? = null
    ): ToolContext {
        runDir.mkdirs()
        workDir.mkdirs()
        return ToolContext(
            runDir = runDir,
            workdir = workDir,
            gate = EngagementGate(),
            log = EventLog(runDir),
            artifacts = ArtifactStore(runDir),
            store = RunStore(runDir.name, db),
            db = db,
            settingsProvider = { BorerSettings() },
            isCancelled = { false },
            notify = {},
            checkpoints = checkpoints,
            currentTurnProvider = currentTurnProvider,
            subagentRunner = subagentRunner,
            isSubagent = isSubagent,
            mcpManager = mcpManager
        )
    }

    private suspend fun serveMcpJsonRpc(
        recordedCalls: MutableList<Pair<String, String>>,
        block: suspend (Int) -> Unit
    ) {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val worker = Thread {
                val parser = Json { ignoreUnknownKeys = true }
                while (running.get() && !server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    runCatching {
                        sock.use { s ->
                            val input = s.getInputStream()
                            val headerBuf = StringBuilder()
                            var prev4 = 0
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                headerBuf.append(b.toChar())
                                prev4 = ((prev4 shl 8) or (b and 0xFF))
                                if (prev4 == 0x0D0A0D0A) break
                            }
                            val headerText = headerBuf.toString()
                            val firstLine = headerText.lineSequence().firstOrNull().orEmpty()
                            val contentLen = headerText.lineSequence()
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                            val bodyBytes = ByteArray(contentLen)
                            var read = 0
                            while (read < contentLen) {
                                val n = input.read(bodyBytes, read, contentLen - read)
                                if (n <= 0) break
                                read += n
                            }
                            val bodyStr = String(bodyBytes, 0, read, Charsets.UTF_8)

                            val responseJson = if (firstLine.startsWith("DELETE ")) {
                                "{}"
                            } else {
                                val reqObj = parser.parseToJsonElement(bodyStr).jsonObject
                                val reqMethod = reqObj["method"]?.jsonPrimitive?.content.orEmpty()
                                val reqId = reqObj["id"]
                                val params = reqObj["params"] as? kotlinx.serialization.json.JsonObject
                                synchronized(recordedCalls) {
                                    recordedCalls.add(reqMethod to bodyStr)
                                }

                                if (reqId == null) {
                                    ""
                                } else {
                                    val resultJson = when (reqMethod) {
                                        "initialize" -> """
                                            {
                                              "protocolVersion": "2025-03-26",
                                              "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
                                              "serverInfo": {"name": "whsearch-mock", "version": "1.0.0"}
                                            }
                                        """.trimIndent()
                                        "tools/list" -> """
                                            {
                                              "tools": [{
                                                "name": "whsearch",
                                                "description": "Search web via whsearch MCP",
                                                "inputSchema": {
                                                  "type": "object",
                                                  "properties": {
                                                    "q": {"type": "string", "description": "Search query"},
                                                    "max_results": {"type": "number", "description": "Max hits"}
                                                  },
                                                  "required": ["q"]
                                                }
                                              }]
                                            }
                                        """.trimIndent()
                                        "resources/list" -> """
                                            {
                                              "resources": [{
                                                "uri": "whsearch://status",
                                                "name": "Status",
                                                "description": "Service health",
                                                "mimeType": "text/plain"
                                              }]
                                            }
                                        """.trimIndent()
                                        "resources/templates/list" -> """
                                            {
                                              "resourceTemplates": [{
                                                "uriTemplate": "whsearch://cache/{topic}",
                                                "name": "Cached Topic",
                                                "description": "Cached search page",
                                                "mimeType": "text/plain"
                                              }]
                                            }
                                        """.trimIndent()
                                        "resources/read" -> """
                                            {
                                              "contents": [{
                                                "uri": "whsearch://status",
                                                "mimeType": "text/plain",
                                                "text": "whsearch-online"
                                              }]
                                            }
                                        """.trimIndent()
                                        "prompts/list" -> """
                                            {
                                              "prompts": [{
                                                "name": "deep_dive",
                                                "description": "Deep web research template",
                                                "arguments": [{"name": "topic", "description": "Topic", "required": true}]
                                              }]
                                            }
                                        """.trimIndent()
                                        "prompts/get" -> {
                                            val pArgs = params?.get("arguments") as? kotlinx.serialization.json.JsonObject
                                            val topic = pArgs?.get("topic")?.jsonPrimitive?.content.orEmpty()
                                            """
                                            {
                                              "description": "Deep dive prompt",
                                              "messages": [{"role": "user", "content": {"type": "text", "text": "Research $topic thoroughly"}}]
                                            }
                                            """.trimIndent()
                                        }
                                        "tools/call" -> {
                                            val callArgs = params?.get("arguments") as? kotlinx.serialization.json.JsonObject
                                            val q = callArgs?.get("q")?.jsonPrimitive?.content.orEmpty()
                                            val max = callArgs?.get("max_results")?.jsonPrimitive?.content ?: "0"
                                            """
                                            {
                                              "content": [{"type": "text", "text": "Hit for $q (max=$max)"}],
                                              "isError": false
                                            }
                                            """.trimIndent()
                                        }
                                        else -> "{}"
                                    }
                                    """{"jsonrpc":"2.0","id":$reqId,"result":$resultJson}"""
                                }
                            }

                            val outBytes = responseJson.toByteArray(Charsets.UTF_8)
                            val status = if (responseJson.isEmpty()) "202 Accepted" else "200 OK"
                            val resp = buildString {
                                append("HTTP/1.1 $status\r\n")
                                append("Content-Type: application/json\r\n")
                                append("Mcp-Session-Id: sess-mock-1\r\n")
                                append("Connection: close\r\n")
                                append("Content-Length: ${outBytes.size}\r\n")
                                append("\r\n")
                            }
                            s.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                            if (outBytes.isNotEmpty()) s.getOutputStream().write(outBytes)
                            s.getOutputStream().flush()
                        }
                    }
                }
            }
            worker.start()
            try {
                block(port)
            } finally {
                running.set(false)
                runCatching { server.close() }
                worker.join(3000)
            }
        }
    }

    private suspend fun serveResponses(
        responses: List<Pair<Int, Pair<String, String>>>,
        block: suspend (Int) -> Unit
    ) {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            val worker = Thread {
                for ((code, bodyPair) in responses) {
                    val (contentType, body) = bodyPair
                    runCatching {
                        server.accept().use { sock ->
                            val input = sock.getInputStream()
                            val buf = ByteArray(8192)
                            input.read(buf)
                            val bodyBytes = body.toByteArray(Charsets.UTF_8)
                            val resp = buildString {
                                append("HTTP/1.1 $code OK\r\n")
                                append("Content-Type: $contentType\r\n")
                                append("Connection: close\r\n")
                                append("Content-Length: ${bodyBytes.size}\r\n")
                                append("\r\n")
                            }
                            sock.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().write(bodyBytes)
                            sock.getOutputStream().flush()
                        }
                    }
                }
            }
            worker.start()
            try {
                block(port)
            } finally {
                worker.join(3000)
            }
        }
    }
}
