package com.example.provider

import com.example.data.EventLog
import com.example.data.ProviderProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FallbackClient(
    val profiles: List<ProviderProfile>,
    private val log: EventLog? = null,
    onRetry: ((attempt: Int, delayMs: Long, reason: String) -> Unit)? = null
) {
    private val clients = profiles.map { OpenAICompatClient(it, onRetry = onRetry) }
    private var activeIndex = 0

    val activeProfile: ProviderProfile?
        get() = profiles.getOrNull(activeIndex)

    val contextWindow: Int
        get() = profiles.minOfOrNull { it.contextWindow } ?: 32_768

    fun stream(req: CompletionRequest): Flow<ProviderEvent> = flow {
        if (clients.isEmpty()) {
            throw ProviderException(
                "No LLM provider configured. Set BORER_PROVIDER_1_BASE_URL, BORER_PROVIDER_1_MODEL, and BORER_PROVIDER_1_API_KEY in AI Studio Secrets.",
                retryable = false
            )
        }
        var lastError: Throwable? = null
        for (offset in clients.indices) {
            val idx = (activeIndex + offset) % clients.size
            val client = clients[idx]
            var emitted = false
            try {
                client.stream(req).collect { event ->
                    emitted = true
                    emit(event)
                }
                activeIndex = idx
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                log?.append(
                    "provider.error",
                    mapOf(
                        "profile" to client.profile.id,
                        "model" to client.profile.model,
                        "message" to (t.message ?: t::class.simpleName.orEmpty()),
                        "emitted" to emitted.toString()
                    )
                )
                if (emitted) throw t
            }
        }
        throw lastError ?: IllegalStateException("All providers failed")
    }
}
