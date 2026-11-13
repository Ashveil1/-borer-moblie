package com.example.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCallRequest>? = null,
    val toolCallId: String? = null,
    val reasoning: String? = null
)

data class ToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0
) {
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens
    )
}

sealed interface ProviderEvent {
    data class Text(val delta: String) : ProviderEvent
    data class Reasoning(val delta: String) : ProviderEvent
    data class ToolCall(val index: Int, val id: String, val name: String, val arguments: String) : ProviderEvent
    data class UsageUpdate(val usage: Usage) : ProviderEvent
    data class Done(val finishReason: String?) : ProviderEvent
}

data class CompletionRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int = 8192,
    val parallelToolCalls: Boolean = true,
    val reasoningEffort: String? = null
)

class ProviderException(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    val responseBody: String? = null
) : RuntimeException(message)
