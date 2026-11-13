package com.example.data

import com.example.BuildConfig
import kotlinx.serialization.Serializable

@Serializable
enum class ApiKind {
    CHAT,
    RESPONSES
}

@Serializable
enum class ContextSource {
    ENV,
    DETECTED,
    FALLBACK,
    CUSTOM
}

@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val api: ApiKind = ApiKind.CHAT,
    val contextWindow: Int = 32_768,
    val contextSource: ContextSource = ContextSource.FALLBACK,
    val maxOutputTokens: Int = 8_192,
    val temperature: Double? = null,
    val topP: Double? = null,
    val reasoningEffort: String? = null,
    val parallelToolCalls: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap()
)

@Serializable
enum class ShellBackend(val wire: String, val label: String) {
    ANDROID_SH("android_sh", "Android /system/bin/sh"),
    TERMUX_PROOT("termux_proot", "Termux proot-distro"),
    PROOT_DIRECT("proot_direct", "Direct proot");

    companion object {
        fun fromWire(value: String?): ShellBackend =
            entries.firstOrNull { it.wire == value } ?: ANDROID_SH
    }
}

@Serializable
data class BorerSettings(
    val inlineBudget: Int = 8 * 1024,
    val compactAt: Double = 0.8,
    val verbatimTurns: Int = 12,
    val noProgressNudge: Int = 40,
    val softStopErrors: Int = 20,
    val evidenceRecords: Int = 3,
    val bulkEvidence: Boolean = false,
    val showReasoning: Boolean = true,
    val strictContext: Boolean = false,
    val defaultAutonomous: Boolean = false,
    val customInstructions: String = "",
    val shellBackend: ShellBackend = ShellBackend.ANDROID_SH,
    val prootDistro: String = "ubuntu",
    val prootBinaryPath: String = "",
    val prootRootfsPath: String = ""
)

@Serializable
data class NotifyConfig(
    val webhookUrl: String? = null,
    val telegramToken: String? = null,
    val telegramChatId: String? = null,
    val discordWebhookUrl: String? = null,
    val limit: Int = 20
) {
    val isConfigured: Boolean
        get() = !webhookUrl.isNullOrBlank() ||
            (!telegramToken.isNullOrBlank() && !telegramChatId.isNullOrBlank()) ||
            !discordWebhookUrl.isNullOrBlank()
}

data class AppConfig(
    val providers: List<ProviderProfile>,
    val settings: BorerSettings,
    val notify: NotifyConfig
)

object ConfigLoader {
    private val contextHints: List<Pair<Regex, Int>> = listOf(
        Regex("gpt-5", RegexOption.IGNORE_CASE) to 272_000,
        Regex("gpt-4\\.1", RegexOption.IGNORE_CASE) to 1_047_576,
        Regex("gpt-4o", RegexOption.IGNORE_CASE) to 128_000,
        Regex("o[34](-|$)", RegexOption.IGNORE_CASE) to 200_000,
        Regex("claude.*(sonnet|opus)-4", RegexOption.IGNORE_CASE) to 200_000,
        Regex("claude", RegexOption.IGNORE_CASE) to 200_000,
        Regex("gemini-2\\.5", RegexOption.IGNORE_CASE) to 1_048_576,
        Regex("gemini", RegexOption.IGNORE_CASE) to 1_000_000,
        Regex("qwen3", RegexOption.IGNORE_CASE) to 262_144,
        Regex("qwen", RegexOption.IGNORE_CASE) to 131_072,
        Regex("deepseek", RegexOption.IGNORE_CASE) to 131_072,
        Regex("llama-?4", RegexOption.IGNORE_CASE) to 131_072,
        Regex("llama", RegexOption.IGNORE_CASE) to 131_072,
        Regex("mistral", RegexOption.IGNORE_CASE) to 131_072,
        Regex("kimi", RegexOption.IGNORE_CASE) to 262_144,
        Regex("glm-?4", RegexOption.IGNORE_CASE) to 200_000,
    )

    fun detectContextWindow(model: String): Int? {
        for ((re, size) in contextHints) {
            if (re.containsMatchIn(model)) return size
        }
        return null
    }

    private fun cleanValue(raw: String): String {
        val trimmed = raw.trim()
        val isPlaceholder = trimmed.isBlank() ||
            trimmed.equals("unset", ignoreCase = true) ||
            trimmed.equals("none", ignoreCase = true) ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("sk-replace-me", ignoreCase = true) ||
            trimmed.equals("replace_me", ignoreCase = true) ||
            trimmed.startsWith("YOUR_", ignoreCase = true)
        return if (isPlaceholder) "" else trimmed
    }

    fun load(settings: BorerSettings = BorerSettings()): AppConfig {
        val providers = buildList {
            buildProfile(
                index = 1,
                name = cleanValue(BuildConfig.BORER_PROVIDER_1_NAME),
                baseUrl = cleanValue(BuildConfig.BORER_PROVIDER_1_BASE_URL),
                model = cleanValue(BuildConfig.BORER_PROVIDER_1_MODEL),
                apiKey = cleanValue(BuildConfig.BORER_PROVIDER_1_API_KEY),
                contextStr = cleanValue(BuildConfig.BORER_PROVIDER_1_CONTEXT),
                tempStr = cleanValue(BuildConfig.BORER_PROVIDER_1_TEMPERATURE),
                topPStr = cleanValue(BuildConfig.BORER_PROVIDER_1_TOP_P),
                reasoningEffort = cleanValue(BuildConfig.BORER_PROVIDER_1_REASONING_EFFORT),
                parallelStr = cleanValue(BuildConfig.BORER_PROVIDER_1_PARALLEL)
            )?.let(::add)

            buildProfile(
                index = 2,
                name = cleanValue(BuildConfig.BORER_PROVIDER_2_NAME),
                baseUrl = cleanValue(BuildConfig.BORER_PROVIDER_2_BASE_URL),
                model = cleanValue(BuildConfig.BORER_PROVIDER_2_MODEL),
                apiKey = cleanValue(BuildConfig.BORER_PROVIDER_2_API_KEY),
                contextStr = "",
                tempStr = "",
                topPStr = "",
                reasoningEffort = "",
                parallelStr = ""
            )?.let(::add)
        }

        val notify = NotifyConfig(
            webhookUrl = cleanValue(BuildConfig.BORER_WEBHOOK_URL).takeIf { it.isNotBlank() },
            telegramToken = cleanValue(BuildConfig.BORER_TELEGRAM_TOKEN).takeIf { it.isNotBlank() },
            telegramChatId = cleanValue(BuildConfig.BORER_TELEGRAM_CHAT_ID).takeIf { it.isNotBlank() },
            discordWebhookUrl = cleanValue(BuildConfig.BORER_DISCORD_WEBHOOK).takeIf { it.isNotBlank() }
        )

        return AppConfig(
            providers = providers,
            settings = settings,
            notify = notify
        )
    }

    private fun buildProfile(
        index: Int,
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        contextStr: String,
        tempStr: String,
        topPStr: String,
        reasoningEffort: String,
        parallelStr: String
    ): ProviderProfile? {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) return null

        val declared = contextStr.trim().toIntOrNull() ?: 0
        val detected = detectContextWindow(model)
        val (contextWindow, contextSource) = when {
            declared > 0 -> declared to ContextSource.ENV
            detected != null -> detected to ContextSource.DETECTED
            else -> 32_768 to ContextSource.FALLBACK
        }

        val profileId = name.trim().ifBlank { "profile$index" }
        val parallel = if (parallelStr.isBlank()) true else parallelStr.trim().lowercase() in setOf("1", "true", "yes", "on")

        return ProviderProfile(
            id = profileId,
            name = profileId,
            baseUrl = baseUrl.trim()
                .trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/responses")
                .trimEnd('/'),
            model = model.trim(),
            apiKey = apiKey.trim(),
            contextWindow = contextWindow,
            contextSource = contextSource,
            temperature = tempStr.trim().toDoubleOrNull(),
            topP = topPStr.trim().toDoubleOrNull(),
            reasoningEffort = reasoningEffort.trim().takeIf { it.isNotBlank() },
            parallelToolCalls = parallel
        )
    }
}
