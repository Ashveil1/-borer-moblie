package com.example.provider

import com.example.data.ProviderProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class ModelCatalogEntry(val id: String, val context: Int?)

class OpenAICompatClient(
    val profile: ProviderProfile,
    private val maxAttempts: Int = 4,
    private val onRetry: ((attempt: Int, delayMs: Long, reason: String) -> Unit)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizedBaseUrl: String = profile.baseUrl.trim()
        .trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/responses")
        .trimEnd('/')

    private val client: OkHttpClient = sharedHttpClient

    private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull

    suspend fun catalog(): List<ModelCatalogEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$normalizedBaseUrl/models")
            .header("Authorization", "Bearer ${profile.apiKey}")
            .apply { profile.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        val call = client.newCall(req)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) {
                    throw ProviderException("/models failed (${res.code})", res.code, false)
                }
                val text = res.body?.string().orEmpty()
                val root = json.parseToJsonElement(text)
                val data = when (root) {
                    is JsonArray -> root
                    is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val id = item["id"].asStr() ?: return@mapNotNull null
                    val topProvCtx = (item["top_provider"] as? JsonObject)?.get("context_length").asInt()
                    val ctx = item["context_length"].asInt()
                        ?: topProvCtx
                        ?: item["max_model_len"].asInt()
                        ?: item["max_context_length"].asInt()
                        ?: item["input_token_limit"].asInt()
                    ModelCatalogEntry(id = id, context = ctx)
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    fun stream(req: CompletionRequest): Flow<ProviderEvent> = flow {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                streamOnce(req).collect { emit(it) }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                val retryable = when (t) {
                    is ProviderException -> t.retryable
                    is IOException -> true
                    else -> false
                }
                if (!retryable || attempt >= maxAttempts) throw t
                val delayMs = min(30_000.0, 750.0 * 2.0.pow(attempt - 1)).toLong() + Random.nextLong(0, 400)
                onRetry?.invoke(attempt, delayMs, t.message ?: t::class.simpleName.orEmpty())
                delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("stream failed")
    }.flowOn(Dispatchers.IO)

    private fun streamOnce(req: CompletionRequest): Flow<ProviderEvent> = flow {
        val isResponses = profile.api == com.example.data.ApiKind.RESPONSES
        val endpoint = if (isResponses) "$normalizedBaseUrl/responses" else "$normalizedBaseUrl/chat/completions"
        val payload = if (isResponses) buildResponsesRequestBody(req) else buildRequestBody(req)

        val httpReq = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${profile.apiKey}")
            .header("Accept", "text/event-stream")
            .apply { profile.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(httpReq)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) {
                    val errBody = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                    throw ProviderException(
                        "${if (isResponses) "responses" else "chat/completions"} failed (${res.code}) ${errBody.take(400)}",
                        status = res.code,
                        retryable = res.code in RETRYABLE_STATUS,
                        responseBody = errBody.take(4000)
                    )
                }
                val body = res.body ?: throw ProviderException("empty response body", res.code, true)
                val contentType = res.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("application/json") && !contentType.contains("text/event-stream")) {
                    val rawJson = body.string()
                    emitNonStreamJsonEvents(rawJson, isResponses) { ev -> emit(ev) }
                    return@flow
                }

                val source = body.source()

                val toolCalls = linkedMapOf<Int, MutableToolCall>()
                val toolIdToIdx = linkedMapOf<String, Int>()
                var nextChatIdx = 0
                val responseItemIdx = linkedMapOf<String, Int>()
                var nextRespIdx = 0
                var emittedTools = false
                var sawAnySseData = false
                val nonSseFallback = StringBuilder()
                var pendingFinishReason: String? = null

                while (!source.exhausted() && coroutineContext.isActive) {
                    val rawLine = source.readUtf8Line() ?: break
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) {
                        if (!sawAnySseData && nonSseFallback.length < 512 * 1024) {
                            nonSseFallback.append(line).append('\n')
                        }
                        continue
                    }
                    sawAnySseData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        if (!emittedTools) {
                            for ((index, tc) in toolCalls) {
                                emittedTools = true
                                emit(ProviderEvent.ToolCall(index, tc.id, tc.name, tc.arguments.toString()))
                            }
                        }
                        emit(ProviderEvent.Done(pendingFinishReason ?: "stop"))
                        return@flow
                    }

                    val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                    val errEl = chunk["error"]?.takeIf { it !is JsonNull }
                    if (errEl != null) {
                        val errMsg = (errEl as? JsonObject)?.get("message").asStr() ?: errEl.asStr() ?: errEl.toString()
                        throw ProviderException("provider error: $errMsg", null, false, errEl.toString())
                    }

                    if (isResponses) {
                        val evType = chunk["type"].asStr().orEmpty()
                        when (evType) {
                            "response.output_text.delta" -> {
                                chunk["delta"].asStr()?.takeIf { it.isNotEmpty() }?.let {
                                    emit(ProviderEvent.Text(it))
                                }
                            }
                            "response.reasoning_summary_text.delta" -> {
                                chunk["delta"].asStr()?.takeIf { it.isNotEmpty() }?.let {
                                    emit(ProviderEvent.Reasoning(it))
                                }
                            }
                            "response.output_item.added" -> {
                                val item = chunk["item"] as? JsonObject
                                if (item?.get("type").asStr() == "function_call") {
                                    val itemId = item?.get("id").asStr() ?: "fc_$nextRespIdx"
                                    val callId = item?.get("call_id").asStr() ?: itemId
                                    val name = item?.get("name").asStr().orEmpty()
                                    val idx = responseItemIdx.getOrPut(itemId) { nextRespIdx++ }
                                    val acc = toolCalls.getOrPut(idx) { MutableToolCall(id = callId, name = name) }
                                    if (callId.isNotEmpty()) acc.id = callId
                                    if (name.isNotEmpty()) acc.name = name
                                    emit(ProviderEvent.ToolCall(idx, acc.id, acc.name, ""))
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                val itemId = chunk["item_id"].asStr().orEmpty()
                                val idx = responseItemIdx[itemId] ?: 0
                                val deltaArgs = chunk["delta"].asStr().orEmpty()
                                if (deltaArgs.isNotEmpty()) {
                                    toolCalls.getOrPut(idx) { MutableToolCall() }.arguments.append(deltaArgs)
                                }
                            }
                            "response.output_item.done" -> {
                                val item = chunk["item"] as? JsonObject
                                if (item?.get("type").asStr() == "function_call") {
                                    val itemId = item?.get("id").asStr() ?: "fc_$nextRespIdx"
                                    val callId = item?.get("call_id").asStr() ?: itemId
                                    val name = item?.get("name").asStr().orEmpty()
                                    val args = item?.get("arguments").asStr().orEmpty()
                                    val idx = responseItemIdx.getOrPut(itemId) { nextRespIdx++ }
                                    val acc = toolCalls.getOrPut(idx) { MutableToolCall(id = callId, name = name) }
                                    if (callId.isNotEmpty()) acc.id = callId
                                    if (name.isNotEmpty()) acc.name = name
                                    if (acc.arguments.isEmpty() && args.isNotEmpty()) {
                                        acc.arguments.append(args)
                                    }
                                }
                            }
                            "response.failed", "error" -> {
                                val errObj = (chunk["response"] as? JsonObject)?.get("error") as? JsonObject
                                    ?: chunk["error"] as? JsonObject
                                val msg = errObj?.get("message").asStr()
                                    ?: chunk["message"].asStr()
                                    ?: "responses stream failed"
                                throw ProviderException(msg, null, false, chunk.toString().take(2000))
                            }
                            "response.completed" -> {
                                val respObj = chunk["response"] as? JsonObject
                                (respObj?.get("usage") as? JsonObject)?.let { u ->
                                    val inputTok = u["input_tokens"].asInt() ?: 0
                                    val outputTok = u["output_tokens"].asInt() ?: 0
                                    val cached = (u["input_tokens_details"] as? JsonObject)
                                        ?.get("cached_tokens").asInt() ?: 0
                                    val total = u["total_tokens"].asInt() ?: (inputTok + outputTok)
                                    emit(ProviderEvent.UsageUpdate(Usage(inputTok, outputTok, cached, total)))
                                }
                                if (!emittedTools) {
                                    for ((index, tc) in toolCalls) {
                                        emittedTools = true
                                        emit(ProviderEvent.ToolCall(index, tc.id, tc.name, tc.arguments.toString()))
                                    }
                                }
                                emit(ProviderEvent.Done("stop"))
                                return@flow
                            }
                        }
                        continue
                    }

                    (chunk["usage"] as? JsonObject)?.let { u ->
                        val prompt = u["prompt_tokens"].asInt() ?: 0
                        val completion = u["completion_tokens"].asInt() ?: 0
                        val cached = (u["prompt_tokens_details"] as? JsonObject)
                            ?.get("cached_tokens").asInt() ?: 0
                        val total = u["total_tokens"].asInt() ?: (prompt + completion)
                        emit(ProviderEvent.UsageUpdate(Usage(prompt, completion, cached, total)))
                    }

                    val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                    val delta = choice["delta"] as? JsonObject ?: JsonObject(emptyMap())

                    delta["content"].asStr()?.takeIf { it.isNotEmpty() }?.let {
                        emit(ProviderEvent.Text(it))
                    }
                    val reasoning = delta["reasoning_content"].asStr()
                        ?: delta["reasoning"].asStr()
                    if (!reasoning.isNullOrEmpty()) {
                        emit(ProviderEvent.Reasoning(reasoning))
                    }

                    (delta["tool_calls"] as? JsonArray)?.forEachIndexed { arrayPos, tcEl ->
                        val tcObj = tcEl as? JsonObject ?: return@forEachIndexed
                        val id = tcObj["id"].asStr()
                        val explicitIdx = tcObj["index"].asInt()
                        val index = when {
                            explicitIdx != null -> {
                                if (explicitIdx >= nextChatIdx) nextChatIdx = explicitIdx + 1
                                explicitIdx
                            }
                            !id.isNullOrEmpty() -> toolIdToIdx.getOrPut(id) { nextChatIdx++ }
                            else -> arrayPos.coerceAtLeast(nextChatIdx - 1).coerceAtLeast(0)
                        }
                        if (!id.isNullOrEmpty()) toolIdToIdx[id] = index
                        val fnObj = tcObj["function"] as? JsonObject
                        val name = fnObj?.get("name").asStr()
                        val args = fnObj?.get("arguments").asStr().orEmpty()

                        val acc = toolCalls.getOrPut(index) { MutableToolCall() }
                        if (!id.isNullOrEmpty()) acc.id = id
                        if (!name.isNullOrEmpty()) acc.name = name
                        if (args.isNotEmpty()) acc.arguments.append(args)

                        if (!id.isNullOrEmpty()) {
                            emit(ProviderEvent.ToolCall(index, acc.id, acc.name, ""))
                        }
                    }

                    val finishReason = choice["finish_reason"].asStr()
                    if (!finishReason.isNullOrEmpty()) {
                        pendingFinishReason = finishReason
                        if (!emittedTools && toolCalls.isNotEmpty()) {
                            for ((index, tc) in toolCalls) {
                                emittedTools = true
                                emit(ProviderEvent.ToolCall(index, tc.id, tc.name, tc.arguments.toString()))
                            }
                        }
                    }
                }
                if (!sawAnySseData && nonSseFallback.isNotEmpty()) {
                    val trimmed = nonSseFallback.toString().trim()
                    if (trimmed.startsWith("{")) {
                        emitNonStreamJsonEvents(trimmed, isResponses) { ev -> emit(ev) }
                        return@flow
                    }
                }
                if (!emittedTools && toolCalls.isNotEmpty()) {
                    for ((index, tc) in toolCalls) {
                        emittedTools = true
                        emit(ProviderEvent.ToolCall(index, tc.id, tc.name, tc.arguments.toString()))
                    }
                }
                emit(ProviderEvent.Done(pendingFinishReason))
            }
        } finally {
            cancelHandle?.dispose()
            call.cancel()
        }
    }

    private suspend fun emitNonStreamJsonEvents(
        rawJson: String,
        isResponses: Boolean,
        emitEvent: suspend (ProviderEvent) -> Unit
    ) {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: run {
            emitEvent(ProviderEvent.Done("stop"))
            return
        }
        val errEl = root["error"]?.takeIf { it !is JsonNull }
        if (errEl != null) {
            val errMsg = (errEl as? JsonObject)?.get("message").asStr() ?: errEl.asStr() ?: errEl.toString()
            throw ProviderException("provider error: $errMsg", null, false, errEl.toString())
        }

        (root["usage"] as? JsonObject)?.let { u ->
            val prompt = u["prompt_tokens"].asInt() ?: u["input_tokens"].asInt() ?: 0
            val completion = u["completion_tokens"].asInt() ?: u["output_tokens"].asInt() ?: 0
            val cached = (u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens").asInt()
                ?: (u["input_tokens_details"] as? JsonObject)?.get("cached_tokens").asInt()
                ?: 0
            val total = u["total_tokens"].asInt() ?: (prompt + completion)
            emitEvent(ProviderEvent.UsageUpdate(Usage(prompt, completion, cached, total)))
        }

        if (isResponses) {
            var tcIdx = 0
            (root["output"] as? JsonArray)?.forEach { el ->
                val item = el as? JsonObject ?: return@forEach
                when (item["type"].asStr()) {
                    "message" -> {
                        (item["content"] as? JsonArray)?.forEach { cEl ->
                            val cObj = cEl as? JsonObject ?: return@forEach
                            val txt = cObj["text"].asStr() ?: cObj["content"].asStr()
                            if (!txt.isNullOrEmpty()) emitEvent(ProviderEvent.Text(txt))
                        }
                    }
                    "reasoning" -> {
                        (item["summary"] as? JsonArray)?.forEach { sEl ->
                            val txt = (sEl as? JsonObject)?.get("text").asStr()
                            if (!txt.isNullOrEmpty()) emitEvent(ProviderEvent.Reasoning(txt))
                        }
                    }
                    "function_call" -> {
                        val callId = item["call_id"].asStr() ?: item["id"].asStr() ?: "fc_$tcIdx"
                        val name = item["name"].asStr().orEmpty()
                        val args = item["arguments"].asStr().orEmpty()
                        emitEvent(ProviderEvent.ToolCall(tcIdx++, callId, name, args))
                    }
                }
            }
            if ((root["output"] as? JsonArray).isNullOrEmpty()) {
                root["output_text"].asStr()?.takeIf { it.isNotEmpty() }?.let {
                    emitEvent(ProviderEvent.Text(it))
                }
            }
            emitEvent(ProviderEvent.Done("stop"))
            return
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val reasoning = message?.get("reasoning_content").asStr() ?: message?.get("reasoning").asStr()
        if (!reasoning.isNullOrEmpty()) emitEvent(ProviderEvent.Reasoning(reasoning))
        val content = message?.get("content").asStr()
        if (!content.isNullOrEmpty()) emitEvent(ProviderEvent.Text(content))

        (message?.get("tool_calls") as? JsonArray)?.forEachIndexed { idx, tcEl ->
            val tcObj = tcEl as? JsonObject ?: return@forEachIndexed
            val id = tcObj["id"].asStr() ?: "call_$idx"
            val fn = tcObj["function"] as? JsonObject
            val name = fn?.get("name").asStr().orEmpty()
            val args = fn?.get("arguments").asStr().orEmpty()
            emitEvent(ProviderEvent.ToolCall(idx, id, name, args))
        }
        emitEvent(ProviderEvent.Done(choice?.get("finish_reason").asStr() ?: "stop"))
    }

    private fun isReasoningModel(model: String): Boolean {
        val m = model.lowercase()
        return m.startsWith("gpt-5") || m.contains("/gpt-5") ||
            Regex("(^|/)o[134](-|$)").containsMatchIn(m)
    }

    private fun buildRequestBody(req: CompletionRequest): JsonObject = buildJsonObject {
        val reasoningOnly = isReasoningModel(profile.model)
        put("model", profile.model)
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
        if (reasoningOnly) {
            put("max_completion_tokens", req.maxOutputTokens)
        } else {
            put("max_tokens", req.maxOutputTokens)
            req.temperature?.let { put("temperature", it) }
            req.topP?.let { put("top_p", it) }
        }
        req.reasoningEffort?.let { put("reasoning_effort", it) }

        put("messages", buildJsonArray {
            for (m in req.messages) {
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content.orEmpty())
                    if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                        put("tool_calls", buildJsonArray {
                            for (tc in m.toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments.ifBlank { "{}" })
                                    })
                                })
                            }
                        })
                        if (!m.reasoning.isNullOrEmpty() && profile.model.contains("deepseek", ignoreCase = true)) {
                            put("reasoning_content", m.reasoning)
                        }
                    }
                    if (m.role == "tool" && !m.toolCallId.isNullOrEmpty()) {
                        put("tool_call_id", m.toolCallId)
                    }
                })
            }
        })

        if (req.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in req.tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    })
                }
            })
            put("tool_choice", "auto")
            if (profile.parallelToolCalls && req.parallelToolCalls) {
                put("parallel_tool_calls", true)
            }
        }
    }

    private fun buildResponsesRequestBody(req: CompletionRequest): JsonObject = buildJsonObject {
        put("model", profile.model)
        put("stream", true)
        put("max_output_tokens", req.maxOutputTokens)
        req.reasoningEffort?.let { effort ->
            put("reasoning", buildJsonObject { put("effort", effort) })
        }
        val sys = req.messages.firstOrNull { it.role == "system" }?.content
        if (!sys.isNullOrBlank()) put("instructions", sys)

        put("input", buildJsonArray {
            for (m in req.messages) {
                when (m.role) {
                    "system" -> Unit
                    "user" -> add(buildJsonObject {
                        put("role", "user")
                        put("content", m.content.orEmpty())
                    })
                    "assistant" -> {
                        if (!m.content.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", m.content)
                            })
                        }
                        m.toolCalls?.forEach { tc ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", tc.id)
                                put("name", tc.name)
                                put("arguments", tc.arguments.ifBlank { "{}" })
                            })
                        }
                    }
                    "tool" -> add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", m.toolCallId.orEmpty())
                        put("output", m.content.orEmpty())
                    })
                }
            }
        })

        if (req.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in req.tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    })
                }
            })
            put("tool_choice", "auto")
            if (profile.parallelToolCalls && req.parallelToolCalls) {
                put("parallel_tool_calls", true)
            }
        }
    }

    private class MutableToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    companion object {
        private val RETRYABLE_STATUS = setOf(408, 409, 425, 429, 500, 502, 503, 504, 529)
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
