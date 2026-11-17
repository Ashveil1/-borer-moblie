package com.example.mcp

import android.content.Context
import com.example.data.BorerSettings
import com.example.data.McpServerConfig
import com.example.data.McpTransport
import com.example.tools.Tool
import com.example.tools.ToolContext
import com.example.tools.ToolResult
import com.example.tools.schemaObject
import com.example.tools.schemaProp
import com.example.tools.spill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class McpManager(
    private val context: Context,
    private val workdir: File
) {
    private val opMutex = Mutex()
    private val activeTransports = ConcurrentHashMap<String, McpTransportSession>()
    private val statesMap = ConcurrentHashMap<String, McpServerState>()

    private val _states = MutableStateFlow<List<McpServerState>>(emptyList())
    val states: StateFlow<List<McpServerState>> = _states.asStateFlow()

    var onToolsChanged: (() -> Unit)? = null

    suspend fun syncServers(servers: List<McpServerConfig>, settings: BorerSettings) = opMutex.withLock {
        val targetIds = servers.map { it.id }.toSet()
        for (existingId in activeTransports.keys.toList()) {
            val cfg = servers.firstOrNull { it.id == existingId }
            if (cfg == null || !cfg.enabled) {
                activeTransports.remove(existingId)?.close()
            }
        }
        for (existingId in statesMap.keys.toList()) {
            if (existingId !in targetIds) statesMap.remove(existingId)
        }

        for (cfg in servers) {
            val existingState = statesMap[cfg.id]
            if (!cfg.enabled) {
                activeTransports.remove(cfg.id)?.close()
                statesMap[cfg.id] = McpServerState(
                    config = cfg,
                    status = McpConnectionStatus.DISCONNECTED
                )
                continue
            }
            val needsReconnect = existingState == null ||
                existingState.config != cfg ||
                existingState.status != McpConnectionStatus.CONNECTED ||
                !activeTransports.containsKey(cfg.id)

            if (needsReconnect) {
                connectInternal(cfg, settings)
            }
        }
        publishOrdered(servers)
        onToolsChanged?.invoke()
    }

    suspend fun connectOrReconnect(
        config: McpServerConfig,
        settings: BorerSettings,
        allConfigs: List<McpServerConfig>
    ): McpServerState = opMutex.withLock {
        val st = connectInternal(config, settings)
        publishOrdered(allConfigs)
        onToolsChanged?.invoke()
        st
    }

    suspend fun disconnect(serverId: String, allConfigs: List<McpServerConfig>) = opMutex.withLock {
        activeTransports.remove(serverId)?.close()
        val prev = statesMap[serverId]
        if (prev != null) {
            statesMap[serverId] = prev.copy(
                status = McpConnectionStatus.DISCONNECTED,
                errorMessage = null,
                tools = emptyList(),
                resources = emptyList(),
                resourceTemplates = emptyList(),
                prompts = emptyList()
            )
        }
        publishOrdered(allConfigs)
        onToolsChanged?.invoke()
    }

    private suspend fun connectInternal(config: McpServerConfig, settings: BorerSettings): McpServerState {
        activeTransports.remove(config.id)?.close()
        statesMap[config.id] = McpServerState(
            config = config,
            status = McpConnectionStatus.CONNECTING
        )
        publishCurrent()

        val transport = createMcpTransport(context, config, settings, workdir)
        val timeout = config.timeoutMs.coerceIn(3_000L, 120_000L)
        return try {
            transport.connect(timeout)
            val initParams = buildJsonObject {
                put("protocolVersion", McpProtocol.LATEST_VERSION)
                put("capabilities", buildJsonObject {
                    put("roots", buildJsonObject { put("listChanged", false) })
                })
                put("clientInfo", buildJsonObject {
                    put("name", "borer")
                    put("version", "0.1.0")
                })
            }
            val initResult = transport.request("initialize", initParams, timeout)
            runCatching { transport.notify("notifications/initialized", null) }

            val protocolVersion = initResult["protocolVersion"]?.jsonPrimitive?.contentOrNull
            val serverInfo = initResult["serverInfo"] as? JsonObject
            val serverName = serverInfo?.get("name")?.jsonPrimitive?.contentOrNull ?: config.name
            val serverVersion = serverInfo?.get("version")?.jsonPrimitive?.contentOrNull
            val instructions = initResult["instructions"]?.jsonPrimitive?.contentOrNull
            val capabilities = initResult["capabilities"] as? JsonObject

            val tools = discoverTools(transport, config.id, serverName, timeout)
            val resources = if (capabilities == null || capabilities.containsKey("resources")) {
                runCatching { discoverResources(transport, config.id, timeout) }.getOrDefault(emptyList())
            } else emptyList()
            val templates = if (capabilities == null || capabilities.containsKey("resources")) {
                runCatching { discoverResourceTemplates(transport, config.id, timeout) }.getOrDefault(emptyList())
            } else emptyList()
            val prompts = if (capabilities == null || capabilities.containsKey("prompts")) {
                runCatching { discoverPrompts(transport, config.id, timeout) }.getOrDefault(emptyList())
            } else emptyList()

            activeTransports[config.id] = transport
            val connected = McpServerState(
                config = config,
                status = McpConnectionStatus.CONNECTED,
                protocolVersion = protocolVersion,
                serverName = serverName,
                serverVersion = serverVersion,
                instructions = instructions,
                errorMessage = null,
                tools = tools,
                resources = resources,
                resourceTemplates = templates,
                prompts = prompts,
                stderrTail = transport.stderrTail(),
                lastConnectedAt = Instant.now().toString()
            )
            statesMap[config.id] = connected
            connected
        } catch (e: CancellationException) {
            transport.close()
            throw e
        } catch (t: Throwable) {
            val tail = transport.stderrTail()
            transport.close()
            val failed = McpServerState(
                config = config,
                status = McpConnectionStatus.ERROR,
                errorMessage = t.message ?: t::class.simpleName ?: "MCP connection failed",
                stderrTail = tail
            )
            statesMap[config.id] = failed
            failed
        }
    }

    private suspend fun discoverTools(
        transport: McpTransportSession,
        serverId: String,
        serverName: String,
        timeoutMs: Long
    ): List<McpToolInfo> {
        val out = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        repeat(10) {
            val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
            val res = transport.request("tools/list", params, timeoutMs)
            val arr = res["tools"] as? JsonArray ?: return@repeat
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val schema = (obj["inputSchema"] as? JsonObject) ?: schemaObject(emptyMap())
                out.add(
                    McpToolInfo(
                        serverId = serverId,
                        serverName = serverName,
                        name = name,
                        description = desc,
                        inputSchema = schema
                    )
                )
            }
            cursor = res["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (cursor == null) return out
        }
        return out
    }

    private suspend fun discoverResources(
        transport: McpTransportSession,
        serverId: String,
        timeoutMs: Long
    ): List<McpResourceInfo> {
        val out = mutableListOf<McpResourceInfo>()
        var cursor: String? = null
        repeat(10) {
            val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
            val res = transport.request("resources/list", params, timeoutMs)
            val arr = res["resources"] as? JsonArray ?: return@repeat
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: uri
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull
                out.add(McpResourceInfo(serverId, uri, name, desc, mime))
            }
            cursor = res["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (cursor == null) return out
        }
        return out
    }

    private suspend fun discoverResourceTemplates(
        transport: McpTransportSession,
        serverId: String,
        timeoutMs: Long
    ): List<McpResourceTemplateInfo> {
        val res = transport.request("resources/templates/list", null, timeoutMs)
        val arr = res["resourceTemplates"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val uriTemplate = obj["uriTemplate"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: uriTemplate
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull
            McpResourceTemplateInfo(serverId, uriTemplate, name, desc, mime)
        }
    }

    private suspend fun discoverPrompts(
        transport: McpTransportSession,
        serverId: String,
        timeoutMs: Long
    ): List<McpPromptInfo> {
        val out = mutableListOf<McpPromptInfo>()
        var cursor: String? = null
        repeat(10) {
            val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
            val res = transport.request("prompts/list", params, timeoutMs)
            val arr = res["prompts"] as? JsonArray ?: return@repeat
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = (obj["arguments"] as? JsonArray)?.mapNotNull { a ->
                    val ao = a as? JsonObject ?: return@mapNotNull null
                    val an = ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    McpPromptArgument(
                        name = an,
                        description = ao["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        required = ao["required"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }.orEmpty()
                out.add(McpPromptInfo(serverId, name, desc, args))
            }
            cursor = res["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (cursor == null) return out
        }
        return out
    }

    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: JsonObject,
        ctx: ToolContext? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val state = statesMap[serverId]
        val transport = activeTransports[serverId]
            ?: return@withContext ToolResult("error: MCP server '$serverId' is not connected", isError = true)
        val timeout = state?.config?.timeoutMs?.coerceIn(3_000L, 120_000L) ?: 30_000L
        try {
            val params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
            val result = transport.request("tools/call", params, timeout)
            val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val formatted = formatToolResultContent(result)
            if (ctx != null) {
                val (spilled, artId) = spill(ctx, formatted, "mcp", "$serverId/$toolName")
                ToolResult(content = spilled, isError = isError, artifactId = artId)
            } else {
                ToolResult(content = formatted, isError = isError)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ToolResult("error calling MCP '$serverId/$toolName': ${t.message}", isError = true)
        }
    }

    suspend fun readResource(serverId: String, uri: String, ctx: ToolContext? = null): ToolResult =
        withContext(Dispatchers.IO) {
            val transport = activeTransports[serverId]
                ?: return@withContext ToolResult("error: MCP server '$serverId' is not connected", isError = true)
            val timeout = statesMap[serverId]?.config?.timeoutMs?.coerceIn(3_000L, 120_000L) ?: 30_000L
            try {
                val res = transport.request("resources/read", buildJsonObject { put("uri", uri) }, timeout)
                val contents = res["contents"] as? JsonArray
                val text = contents?.joinToString("\n\n") { elem ->
                    val obj = elem as? JsonObject ?: return@joinToString elem.toString()
                    val rUri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: uri
                    val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull?.let { " ($it)" }.orEmpty()
                    val body = obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: obj["blob"]?.jsonPrimitive?.contentOrNull?.let { "[base64 blob ${it.length} chars]" }
                        ?: obj.toString()
                    "--- $rUri$mime ---\n$body"
                }.orEmpty().ifBlank { res.toString() }
                if (ctx != null) {
                    val (spilled, artId) = spill(ctx, text, "mcp_resource", "$serverId:$uri")
                    ToolResult(spilled, artifactId = artId)
                } else {
                    ToolResult(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ToolResult("error reading MCP resource '$uri' on '$serverId': ${t.message}", isError = true)
            }
        }

    suspend fun getPrompt(serverId: String, name: String, arguments: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val transport = activeTransports[serverId]
                ?: return@withContext ToolResult("error: MCP server '$serverId' is not connected", isError = true)
            val timeout = statesMap[serverId]?.config?.timeoutMs?.coerceIn(3_000L, 120_000L) ?: 30_000L
            try {
                val params = buildJsonObject {
                    put("name", name)
                    if (arguments.isNotEmpty()) {
                        put("arguments", buildJsonObject {
                            arguments.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }
                val res = transport.request("prompts/get", params, timeout)
                val desc = res["description"]?.jsonPrimitive?.contentOrNull
                val msgs = (res["messages"] as? JsonArray)?.joinToString("\n\n") { elem ->
                    val obj = elem as? JsonObject ?: return@joinToString elem.toString()
                    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                    val content = obj["content"]
                    val text = when (content) {
                        is JsonObject -> formatContentItem(content)
                        is JsonArray -> content.joinToString("\n") { c ->
                            (c as? JsonObject)?.let(::formatContentItem) ?: c.toString()
                        }
                        else -> content?.toString().orEmpty()
                    }
                    "[$role]\n$text"
                }.orEmpty()
                val combined = listOfNotNull(desc?.takeIf { it.isNotBlank() }, msgs.takeIf { it.isNotBlank() })
                    .joinToString("\n\n")
                    .ifBlank { res.toString() }
                ToolResult(combined)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ToolResult("error getting MCP prompt '$name' on '$serverId': ${t.message}", isError = true)
            }
        }

    fun findWebSearchTarget(): Pair<McpServerState, McpToolInfo>? {
        val connected = _states.value.filter { it.status == McpConnectionStatus.CONNECTED && it.tools.isNotEmpty() }
        val preferred = connected.firstOrNull {
            it.config.useForWebSearch || it.config.id.equals("whsearch", ignoreCase = true)
        } ?: return null
        val tool = pickSearchTool(preferred.tools) ?: return null
        return preferred to tool
    }

    fun buildDynamicTools(): List<Tool> {
        val states = _states.value.filter { it.status == McpConnectionStatus.CONNECTED }
        if (states.isEmpty()) return emptyList()
        val seenNames = mutableSetOf<String>()
        val result = mutableListOf<Tool>()

        for (server in states) {
            val safeServer = sanitizeToken(server.config.id)
            for (t in server.tools) {
                val safeTool = sanitizeToken(t.name)
                var regName = "mcp__${safeServer}__${safeTool}"
                if (!seenNames.add(regName)) {
                    var idx = 2
                    while (!seenNames.add("${regName}_$idx")) idx++
                    regName = "${regName}_$idx"
                }
                val toolDesc = buildString {
                    append("[MCP: ${server.serverName ?: server.config.name}] ")
                    append(t.description.ifBlank { "MCP tool ${t.name}" })
                }
                result.add(
                    object : Tool {
                        override val name: String = regName
                        override val description: String = toolDesc
                        override val parameters: JsonObject = ensureObjectSchema(t.inputSchema)
                        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
                            callTool(server.config.id, t.name, args, ctx)
                    }
                )
            }
        }
        return result
    }

    fun buildBuiltinMetaTools(): List<Tool> {
        val resourceTool = object : Tool {
            override val name = "mcp_resource"
            override val description =
                "Inspect or read MCP server resources and URI templates. Actions: list | templates | read."
            override val parameters: JsonObject = schemaObject(
                properties = mapOf(
                    "action" to schemaProp("string", "list | templates | read"),
                    "server" to schemaProp("string", "MCP server id"),
                    "uri" to schemaProp("string", "Resource URI to read (when action=read)")
                ),
                required = listOf("action")
            )

            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
                val serverFilter = args["server"]?.jsonPrimitive?.contentOrNull
                val connected = _states.value.filter {
                    it.status == McpConnectionStatus.CONNECTED &&
                        (serverFilter.isNullOrBlank() || it.config.id == serverFilter)
                }
                if (connected.isEmpty()) {
                    return ToolResult("No connected MCP servers${serverFilter?.let { " matching '$it'" }.orEmpty()}.")
                }
                return when (action) {
                    "list" -> {
                        val lines = connected.flatMap { s ->
                            s.resources.map { r -> "${s.config.id} | ${r.uri} | ${r.name}${r.mimeType?.let { " ($it)" }.orEmpty()}" }
                        }
                        ToolResult(if (lines.isEmpty()) "No MCP resources exposed." else lines.joinToString("\n"))
                    }
                    "templates" -> {
                        val lines = connected.flatMap { s ->
                            s.resourceTemplates.map { t -> "${s.config.id} | ${t.uriTemplate} | ${t.name}" }
                        }
                        ToolResult(if (lines.isEmpty()) "No MCP resource templates exposed." else lines.joinToString("\n"))
                    }
                    "read" -> {
                        val uri = args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (uri.isBlank()) return ToolResult("error: uri is required for action=read", isError = true)
                        val targetServer = serverFilter
                            ?: connected.firstOrNull { s -> s.resources.any { it.uri == uri } }?.config?.id
                            ?: connected.first().config.id
                        readResource(targetServer, uri, ctx)
                    }
                    else -> ToolResult("unknown action '$action'", isError = true)
                }
            }
        }

        val promptTool = object : Tool {
            override val name = "mcp_prompt"
            override val description =
                "List or expand MCP server prompt templates. Actions: list | get."
            override val parameters: JsonObject = schemaObject(
                properties = mapOf(
                    "action" to schemaProp("string", "list | get"),
                    "server" to schemaProp("string", "MCP server id"),
                    "name" to schemaProp("string", "Prompt name (when action=get)"),
                    "arguments" to schemaProp("object", "Key-value prompt arguments")
                ),
                required = listOf("action")
            )

            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
                val serverFilter = args["server"]?.jsonPrimitive?.contentOrNull
                val connected = _states.value.filter {
                    it.status == McpConnectionStatus.CONNECTED &&
                        (serverFilter.isNullOrBlank() || it.config.id == serverFilter)
                }
                if (connected.isEmpty()) {
                    return ToolResult("No connected MCP servers${serverFilter?.let { " matching '$it'" }.orEmpty()}.")
                }
                return when (action) {
                    "list" -> {
                        val lines = connected.flatMap { s ->
                            s.prompts.map { p ->
                                val reqArgs = p.arguments.joinToString(", ") { a ->
                                    if (a.required) "${a.name}*" else a.name
                                }
                                "${s.config.id} | ${p.name}($reqArgs) — ${p.description}"
                            }
                        }
                        ToolResult(if (lines.isEmpty()) "No MCP prompts exposed." else lines.joinToString("\n"))
                    }
                    "get" -> {
                        val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isBlank()) return ToolResult("error: name is required for action=get", isError = true)
                        val targetServer = serverFilter
                            ?: connected.firstOrNull { s -> s.prompts.any { it.name == name } }?.config?.id
                            ?: connected.first().config.id
                        val argMap = (args["arguments"] as? JsonObject)?.entries
                            ?.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
                            .orEmpty()
                        getPrompt(targetServer, name, argMap)
                    }
                    else -> ToolResult("unknown action '$action'", isError = true)
                }
            }
        }

        return listOf(resourceTool, promptTool)
    }

    fun buildSystemPromptSection(): String {
        val connected = _states.value.filter { it.status == McpConnectionStatus.CONNECTED }
        if (connected.isEmpty()) return ""
        return buildString {
            appendLine("<mcp-servers>")
            appendLine("Connected MCP (Model Context Protocol) servers:")
            for (s in connected) {
                val searchTag = if (s.config.useForWebSearch) " [web_search provider]" else ""
                appendLine("- ${s.serverName ?: s.config.name} (id=${s.config.id}, transport=${s.config.transport.wire}$searchTag): ${s.tools.size} tools, ${s.resources.size} resources, ${s.prompts.size} prompts")
                s.instructions?.takeIf { it.isNotBlank() }?.let { instr ->
                    appendLine("  Instructions: ${instr.take(500)}")
                }
            }
            appendLine("Call dynamic MCP tools directly via `mcp__<server>__<tool>`, or inspect resources/prompts via `mcp_resource` and `mcp_prompt`.")
            append("</mcp-servers>")
        }.trim()
    }

    fun closeAll() {
        activeTransports.values.forEach { runCatching { it.close() } }
        activeTransports.clear()
    }

    private fun publishOrdered(configs: List<McpServerConfig>) {
        val ordered = configs.map { cfg ->
            statesMap[cfg.id] ?: McpServerState(config = cfg, status = McpConnectionStatus.DISCONNECTED)
        }
        _states.value = ordered
    }

    private fun publishCurrent() {
        _states.value = statesMap.values.toList()
    }

    companion object {
        private val parseJson = Json { ignoreUnknownKeys = true; isLenient = true }

        fun sanitizeToken(raw: String): String =
            raw.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "item" }

        private fun ensureObjectSchema(schema: JsonObject): JsonObject {
            if (schema["type"]?.jsonPrimitive?.contentOrNull == "object" && schema.containsKey("properties")) {
                return schema
            }
            return buildJsonObject {
                put("type", "object")
                put("properties", (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap()))
                (schema["required"] as? JsonArray)?.let { put("required", it) }
            }
        }

        private fun pickSearchTool(tools: List<McpToolInfo>): McpToolInfo? {
            val priority = listOf("web_search", "whsearch", "search", "websearch", "query")
            for (name in priority) {
                tools.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            }
            return tools.firstOrNull { it.name.contains("search", ignoreCase = true) }
                ?: tools.firstOrNull()
        }

        fun mapSearchArgs(tool: McpToolInfo, query: String, limit: Int): JsonObject {
            val props = (tool.inputSchema["properties"] as? JsonObject)?.keys ?: emptySet()
            val queryKey = listOf("query", "q", "keywords", "keyword", "search_query", "term", "text")
                .firstOrNull { it in props } ?: "query"
            val limitKey = listOf("limit", "max_results", "num_results", "count", "top_k", "n")
                .firstOrNull { it in props }
            return buildJsonObject {
                put(queryKey, query)
                if (limitKey != null) put(limitKey, limit)
            }
        }

        private fun formatToolResultContent(result: JsonObject): String {
            val blocks = (result["content"] as? JsonArray)?.mapNotNull { elem ->
                when (elem) {
                    is JsonObject -> formatContentItem(elem)
                    is JsonPrimitive -> elem.contentOrNull
                    else -> elem.toString()
                }
            }.orEmpty().filter { it.isNotBlank() }

            val structured = result["structuredContent"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            return when {
                blocks.isNotEmpty() && structured != null -> "${blocks.joinToString("\n\n")}\n\n[structured]\n$structured"
                blocks.isNotEmpty() -> blocks.joinToString("\n\n")
                structured != null -> structured
                else -> result.toString()
            }
        }

        private fun formatContentItem(obj: JsonObject): String {
            return when (obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "image" -> "[image ${obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/*"} (${obj["data"]?.jsonPrimitive?.contentOrNull?.length ?: 0} b64 chars)]"
                "audio" -> "[audio ${obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "audio/*"} (${obj["data"]?.jsonPrimitive?.contentOrNull?.length ?: 0} b64 chars)]"
                "resource_link" -> {
                    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: uri
                    "[resource link: $name -> $uri]"
                }
                "resource" -> {
                    val resObj = obj["resource"] as? JsonObject
                    val uri = resObj?.get("uri")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val text = resObj?.get("text")?.jsonPrimitive?.contentOrNull
                    if (text != null) "--- $uri ---\n$text" else resObj?.toString() ?: obj.toString()
                }
                else -> obj["text"]?.jsonPrimitive?.contentOrNull ?: obj.toString()
            }
        }

        fun parseMcpServersJson(raw: String): Result<List<McpServerConfig>> = runCatching {
            val root = parseJson.parseToJsonElement(raw.trim()).jsonObject
            val serversObj = (root["mcpServers"] as? JsonObject)
                ?: (root["servers"] as? JsonObject)
                ?: root
            require(serversObj.isNotEmpty()) { "No MCP server entries found in JSON." }
            serversObj.entries.map { (key, value) ->
                val obj = value.jsonObject
                val id = sanitizeToken(key)
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["serverUrl"]?.jsonPrimitive?.contentOrNull
                    ?: obj["httpUrl"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = (obj["args"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                val env = (obj["env"] as? JsonObject)
                    ?.entries?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull.orEmpty()) }
                    .orEmpty()
                val headers = (obj["headers"] as? JsonObject)
                    ?.entries?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull.orEmpty()) }
                    .orEmpty()
                val explicitType = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?: obj["transport"]?.jsonPrimitive?.contentOrNull
                val transport = when (explicitType?.lowercase()) {
                    "stdio" -> McpTransport.STDIO
                    "sse" -> McpTransport.SSE
                    "http", "streamable-http", "streamable_http" -> McpTransport.STREAMABLE_HTTP
                    else -> if (url.isNotBlank()) McpTransport.AUTO else McpTransport.STDIO
                }
                McpServerConfig(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: key,
                    enabled = !(obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false),
                    transport = transport,
                    url = url,
                    command = command,
                    args = args,
                    env = env,
                    headers = headers,
                    repoUrl = obj["repoUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    useForWebSearch = obj["useForWebSearch"]?.jsonPrimitive?.booleanOrNull
                        ?: id.equals("whsearch", ignoreCase = true)
                )
            }
        }

        fun exportMcpServersJson(servers: List<McpServerConfig>): String {
            val root = buildJsonObject {
                put("mcpServers", buildJsonObject {
                    for (s in servers) {
                        put(s.id, buildJsonObject {
                            put("name", s.name)
                            put("transport", s.transport.wire)
                            put("disabled", !s.enabled)
                            if (s.url.isNotBlank()) put("url", s.url)
                            if (s.command.isNotBlank()) put("command", s.command)
                            if (s.args.isNotEmpty()) {
                                put("args", buildJsonArray { s.args.forEach { add(JsonPrimitive(it)) } })
                            }
                            if (s.env.isNotEmpty()) {
                                put("env", buildJsonObject { s.env.forEach { (k, v) -> put(k, v) } })
                            }
                            if (s.headers.isNotEmpty()) {
                                put("headers", buildJsonObject { s.headers.forEach { (k, v) -> put(k, v) } })
                            }
                            if (s.repoUrl.isNotBlank()) put("repoUrl", s.repoUrl)
                            if (s.useForWebSearch) put("useForWebSearch", true)
                        })
                    }
                })
            }
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
        }

        fun buildGitHubInstallScript(repoUrl: String, id: String): String {
            val safeId = sanitizeToken(id)
            val safeRepo = repoUrl.trim()
            return """
                set -e
                BASE_DIR="${'$'}{HOME:-${'$'}BORER_WORKDIR}"
                MCP_ROOT="${'$'}BASE_DIR/mcp-servers/$safeId"
                mkdir -p "${'$'}BASE_DIR/mcp-servers"
                if [ -d "${'$'}MCP_ROOT/.git" ]; then
                  git -C "${'$'}MCP_ROOT" pull --ff-only || true
                else
                  rm -rf "${'$'}MCP_ROOT"
                  git clone --depth 1 "$safeRepo" "${'$'}MCP_ROOT"
                fi
                cd "${'$'}MCP_ROOT"
                CMD=""
                ARGS=""
                if [ -f "pyproject.toml" ] || [ -f "requirements.txt" ] || ls *.py >/dev/null 2>&1; then
                  if command -v uv >/dev/null 2>&1; then
                    uv venv .venv || true
                    if [ -f "pyproject.toml" ]; then uv pip install --python .venv/bin/python -e . || true; fi
                    if [ -f "requirements.txt" ]; then uv pip install --python .venv/bin/python -r requirements.txt || true; fi
                  elif command -v python3 >/dev/null 2>&1; then
                    python3 -m venv .venv || true
                    if [ -f "requirements.txt" ]; then .venv/bin/pip install -r requirements.txt || pip3 install -r requirements.txt || true; fi
                    if [ -f "pyproject.toml" ]; then .venv/bin/pip install -e . || pip3 install -e . || true; fi
                  fi
                  PY_BIN="${'$'}MCP_ROOT/.venv/bin/python"
                  if [ ! -x "${'$'}PY_BIN" ]; then PY_BIN="${'$'}(command -v python3 || echo python3)"; fi
                  if [ -x "${'$'}MCP_ROOT/.venv/bin/$safeId" ]; then
                    CMD="${'$'}MCP_ROOT/.venv/bin/$safeId"
                  else
                    for f in server.py main.py app.py mcp_server.py src/server.py src/main.py src/$safeId/server.py $safeId.py; do
                      if [ -f "${'$'}MCP_ROOT/${'$'}f" ]; then
                        CMD="${'$'}PY_BIN"
                        ARGS="${'$'}MCP_ROOT/${'$'}f"
                        break
                      fi
                    done
                  fi
                elif [ -f "package.json" ]; then
                  npm install --no-audit --no-fund
                  if grep -q '"build"' package.json; then npm run build || true; fi
                  NODE_BIN="${'$'}(command -v node || echo node)"
                  for f in dist/index.js build/index.js src/index.js index.js server.js; do
                    if [ -f "${'$'}MCP_ROOT/${'$'}f" ]; then
                      CMD="${'$'}NODE_BIN"
                      ARGS="${'$'}MCP_ROOT/${'$'}f"
                      break
                    fi
                  done
                elif [ -f "go.mod" ]; then
                  go build -o "${'$'}MCP_ROOT/$safeId-mcp" .
                  CMD="${'$'}MCP_ROOT/$safeId-mcp"
                fi
                echo "[BORER_MCP_CMD]${'$'}CMD"
                echo "[BORER_MCP_ARGS]${'$'}ARGS"
                echo "[BORER_MCP_DIR]${'$'}MCP_ROOT"
            """.trimIndent()
        }
    }
}
