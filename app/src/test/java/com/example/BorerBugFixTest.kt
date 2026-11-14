package com.example

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ApiKind
import com.example.data.ArtifactEntity
import com.example.data.ArtifactStore
import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.NoteKind
import com.example.data.ProviderProfile
import com.example.data.RunDatabase
import com.example.data.RunStore
import com.example.provider.ChatMessage
import com.example.provider.CompletionRequest
import com.example.provider.FallbackClient
import com.example.provider.OpenAICompatClient
import com.example.provider.ProviderEvent
import com.example.provider.ToolCallRequest
import com.example.provider.Usage
import com.example.runtime.UbuntuProotRuntime
import com.example.scope.EngagementGate
import com.example.session.AgentSession
import com.example.session.SessionDeps
import com.example.session.UiHooks
import com.example.tools.BorerSkill
import com.example.tools.DiffEngine
import com.example.tools.MutateEngine
import com.example.tools.ToolContext
import com.example.tools.ToolRegistry
import com.example.tools.editTool
import com.example.tools.metaTools
import com.example.tools.sessionTool
import com.example.tools.skillTool
import com.example.ui.screens.ToolSummaryFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BorerBugFixTest {

    private lateinit var tempDir: File
    private lateinit var db: RunDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<BorerApplication>()
        tempDir = createTempDirectory("borer-bugfix-test").toFile()
        // Intentionally WITHOUT allowMainThreadQueries() so any Room query on Main fails immediately.
        db = Room.inMemoryDatabaseBuilder(context, RunDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun agentSession_calledFromMainThread_runsToolsAndRoomOnIoWithoutMainThreadCrash() = runBlocking {
        assertSame(
            "Test harness must invoke session.run from Android main thread",
            Looper.getMainLooper().thread,
            Thread.currentThread()
        )

        val turn1Sse = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"notes","arguments":"{\"action\":\"put\",\"kind\":\"fact\",\"key\":\"k1\",\"body\":\"hello\"}"}}]}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")
        val turn2Sse = listOf(
            """data: {"choices":[{"delta":{"content":"All done."}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            val worker = Thread {
                for (sse in listOf(turn1Sse, turn2Sse)) {
                    server.accept().use { sock ->
                        val input = sock.getInputStream()
                        val buf = ByteArray(8192)
                        input.read(buf)
                        val bodyBytes = sse.toByteArray(Charsets.UTF_8)
                        val resp = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/event-stream\r\n")
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
            worker.start()

            val runDir = File(tempDir, "run-1").apply { mkdirs() }
            val workDir = File(tempDir, "work").apply { mkdirs() }
            val gate = EngagementGate()
            val log = EventLog(runDir)
            val artifacts = ArtifactStore(runDir)
            val store = RunStore("run-1", db)
            val settings = BorerSettings(noProgressNudge = 1)
            val ctx = ToolContext(
                runDir = runDir,
                workdir = workDir,
                gate = gate,
                log = log,
                artifacts = artifacts,
                store = store,
                db = db,
                settingsProvider = { settings },
                isCancelled = { false },
                notify = {}
            )
            val profile = ProviderProfile(
                id = "local",
                name = "local",
                baseUrl = "http://127.0.0.1:$port/v1",
                model = "test-model",
                apiKey = "sk-test",
                api = ApiKind.CHAT
            )
            val session = AgentSession(
                SessionDeps(
                    profile = profile,
                    client = FallbackClient(listOf(profile), log = log),
                    contextWindow = 32768,
                    tools = ToolRegistry(metaTools),
                    ctx = ctx,
                    settingsProvider = { settings },
                    log = log,
                    ui = object : UiHooks {
                        override fun onTurn(turn: Int) {}
                        override fun onText(delta: String) {}
                        override fun onReasoning(delta: String) {}
                        override fun onToolStart(call: ToolCallRequest) {}
                        override fun onToolEnd(call: ToolCallRequest, ok: Boolean, ms: Long, preview: String) {}
                        override fun onUsage(usage: Usage, totals: Usage, contextTokens: Int) {}
                        override fun onInfo(text: String) {}
                    }
                )
            )

            val result = session.run("Save a fact and summarize.")
            worker.join(3000)

            assertEquals("All done.", result.messages.lastOrNull { it.role == "assistant" }?.content)
            val saved = withContext(Dispatchers.IO) { store.queryNotes(kind = NoteKind.FACT) }
            assertEquals(1, saved.size)
            assertEquals("k1", saved[0].key)
            assertEquals("hello", saved[0].body)
        }
    }

    @Test
    fun engagementGate_preservesUnbracketedIpv6AndStripsBracketedPort() = runBlocking {
        val gate = EngagementGate()
        val resBracketed = gate.check(host = "[2001:db8::1]:8443")
        assertTrue(resBracketed.ok)
        assertTrue(gate.reached.containsKey("2001:db8::1"))

        val resRaw = gate.check(host = "2001:db8::2")
        assertTrue(resRaw.ok)
        assertTrue(gate.reached.containsKey("2001:db8::2"))
    }

    @Test
    fun editTool_countsNonOverlappingOccurrences() = runBlocking {
        val workDir = File(tempDir, "edit-work").apply { mkdirs() }
        val file = File(workDir, "sample.txt").apply { writeText("aaaa") }
        val ctx = dummyToolContext(File(tempDir, "run-edit"), workDir)
        val args = buildJsonObject {
            put("path", file.absolutePath)
            put("old_text", "aa")
            put("new_text", "bb")
            put("all", true)
        }
        val res = editTool.run(args, ctx)
        assertFalse(res.isError)
        assertTrue(res.content.contains("2 replacements"))
        assertEquals("bbbb", file.readText())
    }

    @Test
    fun skillTool_blocksSiblingDirectoryPrefixTraversal() = runBlocking {
        val baseDir = File(tempDir, "skills").apply { mkdirs() }
        val skillDir = File(baseDir, "skill-a").apply { mkdirs() }
        val siblingDir = File(baseDir, "skill-a-secret").apply { mkdirs() }
        File(siblingDir, "flag.txt").writeText("SECRET")

        val tool = skillTool(
            listOf(BorerSkill(name = "skill-a", description = "test", dir = skillDir, body = "body"))
        )
        val res = tool.run(
            buildJsonObject {
                put("name", "skill-a")
                put("file", "../skill-a-secret/flag.txt")
            },
            dummyToolContext(File(tempDir, "run-skill"), tempDir)
        )
        assertTrue(res.isError)
        assertTrue(res.content.contains("escapes"))
    }

    @Test
    fun sessionTool_sanitizesPathTraversalLabels() = runBlocking {
        val runDir = File(tempDir, "sessions/run-1").apply { mkdirs() }
        val outsideMarker = File(tempDir, "escape")
        assertFalse(outsideMarker.exists())

        val res = sessionTool.run(
            buildJsonObject {
                put("action", "snapshot")
                put("label", "../../escape")
            },
            dummyToolContext(runDir, tempDir)
        )
        assertFalse(res.isError)
        assertFalse(outsideMarker.exists())
        val snapshots = File(runDir, "snapshots").listFiles().orEmpty()
        assertEquals(1, snapshots.size)
        assertFalse(snapshots[0].name.contains(".."))
    }

    @Test
    fun diffAndMutateEngine_handleZeroOrNegativeLimitsGracefully() {
        val diffOut = DiffEngine.compare("line1\nline2", "line1\nline3", maxLines = -5)
        assertTrue(diffOut.contains("truncated at 1 changed lines"))

        val (_, variants) = MutateEngine.generate("test", limit = -10, traversalDepth = -3)
        assertEquals(1, variants.size)
    }

    @Test
    fun toolSummaryFormatter_handlesArraysAndObjectsSafely() {
        val summaryMutate = ToolSummaryFormatter.summarize(
            "mutate",
            """{"seed":"' OR 1=1","rules":["case","url"],"contexts":["sql"]}"""
        )
        assertTrue(summaryMutate.contains("' OR 1=1"))
        assertTrue(summaryMutate.contains("case,url"))

        val summaryUnknown = ToolSummaryFormatter.summarize(
            "custom",
            """{"nested":{"a":1},"list":["x"],"title":"ok"}"""
        )
        assertEquals("ok", summaryUnknown)
    }

    @Test
    fun openAiCompatClient_handlesNullErrorAndTrailingUsageChunk() = runBlocking {
        val sseBody = listOf(
            """data: {"choices":[{"delta":{"content":"Hi"}}],"error":null}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"error":null}""",
            "",
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}""",
            "",
            "data: [DONE]",
            ""
        ).joinToString("\n")

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            val worker = Thread {
                server.accept().use { sock ->
                    val input = sock.getInputStream()
                    val buf = ByteArray(4096)
                    input.read(buf)
                    val bodyBytes = sseBody.toByteArray(Charsets.UTF_8)
                    val resp = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream\r\n")
                        append("Connection: close\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("\r\n")
                    }
                    sock.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().write(bodyBytes)
                    sock.getOutputStream().flush()
                }
            }
            worker.start()

            val client = OpenAICompatClient(
                ProviderProfile(
                    id = "local",
                    name = "local",
                    baseUrl = "http://127.0.0.1:$port/v1",
                    model = "test",
                    apiKey = "sk-test",
                    api = ApiKind.CHAT
                )
            )
            val events = mutableListOf<ProviderEvent>()
            client.stream(
                CompletionRequest(
                    messages = listOf(ChatMessage(role = "user", content = "hello")),
                    tools = emptyList()
                )
            ).collect { events.add(it) }
            worker.join(2000)

            val usage = events.filterIsInstance<ProviderEvent.UsageUpdate>().lastOrNull()?.usage
            assertEquals(10, usage?.promptTokens)
            assertEquals(4, usage?.completionTokens)
            assertEquals(2, usage?.cachedTokens)
            val done = events.filterIsInstance<ProviderEvent.Done>().lastOrNull()
            assertEquals("stop", done?.finishReason)
        }
    }

    @Test
    fun artifactEntity_compositeKeyAllowsSameIdInDifferentRuns() = runBlocking(Dispatchers.IO) {
        db.artifactDao().upsertSync(ArtifactEntity("art-0001", "run-a", "bash", "a", 10, 1, "/tmp/a", "2026-01-01"))
        db.artifactDao().upsertSync(ArtifactEntity("art-0001", "run-b", "bash", "b", 20, 2, "/tmp/b", "2026-01-01"))

        assertEquals("run-a", db.artifactDao().getArtifactSync("art-0001", "run-a")?.runId)
        assertEquals("run-b", db.artifactDao().getArtifactSync("art-0001", "run-b")?.runId)
    }

    @Test
    fun artifactStore_blocksPathTraversalIds() {
        val runDir = File(tempDir, "run-art").apply { mkdirs() }
        File(runDir, "secret.txt").writeText("top-secret")
        val store = ArtifactStore(runDir)
        assertEquals(null, store.read("../secret.txt"))
        assertEquals(null, store.get(".."))
    }

    @Test
    fun shellTimeout_returnsPromptlyWithoutHanging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<BorerApplication>()
        val started = System.currentTimeMillis()
        val res = UbuntuProotRuntime.exec(
            context = context,
            command = "sleep 5",
            cwd = tempDir,
            env = emptyMap(),
            timeoutMs = 300L,
            stdin = null,
            settings = BorerSettings()
        )
        val elapsed = System.currentTimeMillis() - started
        assertTrue("Expected timeout, got: $res", res.timedOut)
        assertTrue("Timeout took too long (${elapsed}ms)", elapsed < 4000L)
    }

    private fun dummyToolContext(runDir: File, workDir: File): ToolContext {
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
            notify = {}
        )
    }
}
