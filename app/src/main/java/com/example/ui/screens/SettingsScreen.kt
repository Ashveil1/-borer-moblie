package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.net.URI
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AgentProfile
import com.example.data.ApiKind
import com.example.data.ContextSource
import com.example.data.McpServerConfig
import com.example.data.McpTransport
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.ShellBackend
import com.example.mcp.McpConnectionStatus
import com.example.mcp.McpServerState
import com.example.provider.ModelCatalogEntry
import com.example.runtime.BackgroundKeepAliveController
import com.example.runtime.UbuntuProotRuntime
import com.example.ui.BorerViewModel
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val api: ApiKind
)

private val providerPresets = listOf(
    ProviderPreset("OpenAI", "openai", "https://api.openai.com/v1", "gpt-5", ApiKind.RESPONSES),
    ProviderPreset("OpenRouter", "openrouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4", ApiKind.CHAT),
    ProviderPreset("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek-reasoner", ApiKind.CHAT),
    ProviderPreset("Groq", "groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", ApiKind.CHAT),
    ProviderPreset("Local / Ollama", "ollama", "http://127.0.0.1:11434/v1", "qwen3:14b", ApiKind.CHAT)
)

@Composable
fun SettingsScreen(
    vm: BorerViewModel,
    onOpenDrawer: (() -> Unit)?
) {
    var tabIdx by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showFeedback(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            CleanScreenTopBar(
                title = "Settings",
                onOpenDrawer = onOpenDrawer
            )

            CleanSegmentBar(
                tabs = listOf(
                    "Providers" to "tab_settings_providers",
                    "Runtime" to "tab_settings_runtime",
                    "MCP" to "tab_settings_mcp",
                    "Agent" to "tab_settings_agent",
                    "Alerts" to "tab_settings_alerts"
                ),
                selectedIndex = tabIdx,
                onSelect = { tabIdx = it },
                modifier = Modifier.widthIn(max = BorerTokens.ScreenMaxWidth)
            )

            when (tabIdx) {
                0 -> ProvidersSettingsPane(vm, onFeedback = ::showFeedback)
                1 -> RuntimeSettingsPane(vm, onFeedback = ::showFeedback)
                2 -> McpSettingsPane(vm, onFeedback = ::showFeedback)
                3 -> AgentSettingsPane(vm, onFeedback = ::showFeedback)
                else -> AlertsSettingsPane(vm, onFeedback = ::showFeedback)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            )
        }
    }
}

@Composable
private fun ProvidersSettingsPane(
    vm: BorerViewModel,
    onFeedback: (String) -> Unit
) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val testResult by vm.providerTestResult.collectAsStateWithLifecycle()
    val testing by vm.providerTesting.collectAsStateWithLifecycle()

    var editorOpen by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingInitial by remember { mutableStateOf<ProviderProfile?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        item {
            HairlineCard {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Providers", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Primary first, then automatic fallbacks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CleanPrimaryButton(
                        onClick = {
                            vm.clearProviderTestResult()
                            editingIndex = null
                            editingInitial = null
                            editorOpen = true
                        },
                        modifier = Modifier.testTag("add_provider_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add provider", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    providerPresets.forEach { preset ->
                        CleanFilterPill(
                            label = "+ ${preset.label}",
                            selected = false,
                            onClick = {
                                vm.clearProviderTestResult()
                                editingIndex = null
                                editingInitial = ProviderProfile(
                                    id = preset.name,
                                    name = preset.name,
                                    baseUrl = preset.baseUrl,
                                    model = preset.model,
                                    apiKey = "",
                                    api = preset.api,
                                    contextWindow = 0,
                                    contextSource = ContextSource.FALLBACK
                                )
                                editorOpen = true
                            }
                        )
                    }
                }
            }
        }

        if (testResult != null || testing) {
            item {
                val isOk = testResult?.startsWith("OK") == true
                val borderColor = when {
                    testing -> MaterialTheme.colorScheme.outlineVariant
                    isOk -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        CleanStatusBadge(
                            text = if (isOk) "OK" else "FAIL",
                            inverted = isOk
                        )
                    }
                    Text(
                        text = if (testing) "Testing provider endpoint..." else testResult.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!testing) {
                        IconButton(
                            onClick = { vm.clearProviderTestResult() },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss test result",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (cfg.providers.isEmpty()) {
            item {
                HairlineCard {
                    Text("No providers configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap Add or pick a preset above to configure your Base URL, Model, and API Key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                SectionHeader(title = "Active Chain", trailing = "${cfg.providers.size} configured")
            }
            itemsIndexed(cfg.providers, key = { i, p -> "${i}_${p.id}_${p.model}" }) { idx, p ->
                val isPrimary = idx == 0
                HairlineCard(highlighted = isPrimary) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            CleanStatusBadge(
                                text = if (isPrimary) "PRIMARY" else "#${idx + 1}",
                                inverted = isPrimary
                            )
                            Text(
                                text = p.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isPrimary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        CleanStatusBadge(text = "${p.api.name.lowercase()} · ${formatCtx(p.contextWindow)}")
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = p.model,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${p.baseUrl} · ${maskKey(p.apiKey)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CleanOutlinedButton(
                            onClick = { vm.testProviderConnection(p) },
                            enabled = !testing,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Test provider", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test")
                        }
                        Spacer(Modifier.width(8.dp))
                        CleanOutlinedButton(
                            onClick = {
                                vm.clearProviderTestResult()
                                editingIndex = idx
                                editingInitial = p
                                editorOpen = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit provider", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        Spacer(Modifier.weight(1f))
                        if (idx > 0) {
                            IconButton(
                                onClick = {
                                    vm.moveProvider(idx, -1)
                                    onFeedback("Moved '${p.name}' up")
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move priority up")
                            }
                        }
                        if (idx < cfg.providers.lastIndex) {
                            IconButton(
                                onClick = {
                                    vm.moveProvider(idx, 1)
                                    onFeedback("Moved '${p.name}' down")
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move priority down")
                            }
                        }
                        IconButton(
                            onClick = {
                                vm.deleteProvider(idx)
                                onFeedback("Removed provider '${p.name}'")
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete provider", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset Providers to Defaults")
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = { Text("Reset Providers?") },
            text = { Text("This clears custom provider profiles saved on this device and restores Build Secrets defaults.") },
            confirmButton = {
                CleanPrimaryButton(onClick = {
                    vm.resetProvidersToDefaults()
                    confirmReset = false
                    onFeedback("Restored default providers")
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }

    if (editorOpen) {
        ProviderEditorDialog(
            initial = editingInitial,
            testing = testing,
            testResult = testResult,
            onTest = { draft -> vm.testProviderConnection(draft) },
            onFetchModels = { draft -> vm.fetchProviderModels(draft) },
            onDismiss = {
                vm.clearProviderTestResult()
                editorOpen = false
            },
            onSave = { profile ->
                vm.clearProviderTestResult()
                vm.upsertProvider(profile, editingIndex)
                editorOpen = false
                onFeedback("Saved provider '${profile.name}'")
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorDialog(
    initial: ProviderProfile?,
    testing: Boolean,
    testResult: String?,
    onTest: (ProviderProfile) -> Unit,
    onFetchModels: suspend (ProviderProfile) -> Result<List<ModelCatalogEntry>>,
    onDismiss: () -> Unit,
    onSave: (ProviderProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "primary") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var apiKind by remember { mutableStateOf(initial?.api ?: ApiKind.CHAT) }
    var ctxWindow by remember {
        mutableStateOf(if ((initial?.contextWindow ?: 0) > 0) initial!!.contextWindow.toString() else "")
    }
    var maxOut by remember { mutableStateOf((initial?.maxOutputTokens ?: 8192).toString()) }
    var temp by remember { mutableStateOf(initial?.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(initial?.topP?.toString().orEmpty()) }
    var reasoningEffort by remember { mutableStateOf(initial?.reasoningEffort.orEmpty()) }
    var parallel by remember { mutableStateOf(initial?.parallelToolCalls ?: true) }
    var headersRaw by remember {
        mutableStateOf(
            initial?.extraHeaders?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()
        )
    }
    var showValidation by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<ModelCatalogEntry>>(emptyList()) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var catalogContextMatch by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val scope = rememberCoroutineScope()

    val trimmedUrl = baseUrl.trim()
    val parsedUri = runCatching { URI(trimmedUrl) }.getOrNull()
    val urlSchemeOk = parsedUri?.scheme?.equals("http", ignoreCase = true) == true ||
        parsedUri?.scheme?.equals("https", ignoreCase = true) == true
    val urlError = when {
        trimmedUrl.isBlank() -> "Base URL is required"
        trimmedUrl.any { it.isWhitespace() } -> "URL cannot contain spaces"
        !trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true) -> "Must start with http:// or https://"
        !urlSchemeOk || parsedUri?.host.isNullOrBlank() -> "Enter a valid URL host"
        else -> null
    }
    val modelError = if (model.isBlank()) "Model ID is required" else null
    val keyError = if (apiKey.isBlank()) "API key is required (use 'ollama' for local)" else null
    val formValid = urlError == null && modelError == null && keyError == null
    val canQueryCatalog = urlError == null && keyError == null

    fun buildDraft(): ProviderProfile {
        val parsedCtx = ctxWindow.trim().toIntOrNull() ?: 0
        val parsedHeaders = headersRaw.lines()
            .mapNotNull { line ->
                val sep = line.indexOf(':')
                if (sep <= 0) null
                else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }.filter { it.first.isNotBlank() }.toMap()
        val trimmedModel = model.trim()
        val ctxSource = when {
            parsedCtx > 0 && catalogContextMatch?.first == trimmedModel && catalogContextMatch?.second == parsedCtx -> ContextSource.DETECTED
            parsedCtx > 0 -> ContextSource.CUSTOM
            else -> ContextSource.FALLBACK
        }

        return ProviderProfile(
            id = name.trim().ifBlank { "provider" },
            name = name.trim().ifBlank { "provider" },
            baseUrl = trimmedUrl,
            model = trimmedModel,
            apiKey = apiKey.trim(),
            api = apiKind,
            contextWindow = if (parsedCtx > 0) parsedCtx else 0,
            contextSource = ctxSource,
            maxOutputTokens = (maxOut.trim().toIntOrNull() ?: 8192).coerceIn(256, 131_072),
            temperature = temp.trim().toDoubleOrNull(),
            topP = topP.trim().toDoubleOrNull(),
            reasoningEffort = reasoningEffort.takeIf { it.isNotBlank() },
            parallelToolCalls = parallel,
            extraHeaders = parsedHeaders
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = { Text(if (initial == null || initial.apiKey.isBlank()) "Configure Provider" else "Edit Provider") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionHeader(title = "Connection")
                CleanOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Profile Name",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("provider_name_input")
                )
                CleanOutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL (OpenAI-compatible /v1)",
                    singleLine = true,
                    isError = (showValidation || baseUrl.isNotBlank()) && urlError != null,
                    supportingText = if ((showValidation || baseUrl.isNotBlank()) && urlError != null) urlError else null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth().testTag("provider_url_input")
                )
                CleanOutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model ID (e.g. gpt-5, deepseek-reasoner)",
                    singleLine = true,
                    isError = showValidation && modelError != null,
                    supportingText = if (showValidation && modelError != null) modelError else null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth().testTag("provider_model_input")
                )
                CleanOutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    singleLine = true,
                    isError = showValidation && keyError != null,
                    supportingText = if (showValidation && keyError != null) keyError else null,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("provider_key_input")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CleanOutlinedButton(
                        onClick = {
                            showValidation = true
                            if (formValid) onTest(buildDraft())
                        },
                        enabled = !testing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("provider_dialog_test_button")
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Testing...")
                        } else {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test")
                        }
                    }

                    CleanOutlinedButton(
                        onClick = {
                            showValidation = true
                            if (canQueryCatalog) {
                                fetchingModels = true
                                fetchError = null
                                val draft = buildDraft()
                                scope.launch {
                                    onFetchModels(draft)
                                        .onSuccess { list ->
                                            fetchedModels = list.take(30)
                                            if (list.isEmpty()) fetchError = "No models returned by /models"
                                        }
                                        .onFailure { err ->
                                            fetchError = err.message ?: err::class.simpleName
                                        }
                                    fetchingModels = false
                                }
                            }
                        },
                        enabled = !fetchingModels,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("provider_fetch_models_button")
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Fetching...")
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Fetch /models")
                        }
                    }
                }

                if (fetchError != null) {
                    CleanStatusBadge(text = fetchError!!, emphasizedBorder = true)
                }

                if (fetchedModels.isNotEmpty()) {
                    Text(
                        text = "Detected Models (${fetchedModels.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        fetchedModels.forEach { entry ->
                            val label = if (entry.context != null && entry.context > 0) {
                                "${entry.id} (${formatCtx(entry.context)})"
                            } else {
                                entry.id
                            }
                            CleanFilterPill(
                                label = label,
                                selected = model.trim() == entry.id,
                                onClick = {
                                    model = entry.id
                                    if (entry.context != null && entry.context > 0) {
                                        ctxWindow = entry.context.toString()
                                        catalogContextMatch = entry.id to entry.context
                                    }
                                }
                            )
                        }
                    }
                }

                if (!testing && testResult != null) {
                    val isOk = testResult.startsWith("OK")
                    CleanStatusBadge(
                        text = testResult,
                        inverted = isOk,
                        emphasizedBorder = !isOk
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SectionHeader(title = "API & Token Limits")

                CleanSegmentedControl(
                    options = listOf(
                        SegmentTab("Chat", "provider_api_chat"),
                        SegmentTab("Responses", "provider_api_responses")
                    ),
                    selectedIndex = if (apiKind == ApiKind.CHAT) 0 else 1,
                    onSelect = { idx -> apiKind = if (idx == 0) ApiKind.CHAT else ApiKind.RESPONSES }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CleanOutlinedTextField(
                        value = ctxWindow,
                        onValueChange = { ctxWindow = it },
                        label = "Context (0=auto)",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    CleanOutlinedTextField(
                        value = maxOut,
                        onValueChange = { maxOut = it },
                        label = "Max output",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SectionHeader(title = "Sampling & Reasoning")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CleanOutlinedTextField(
                        value = temp,
                        onValueChange = { temp = it },
                        label = "Temp (opt)",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    CleanOutlinedTextField(
                        value = topP,
                        onValueChange = { topP = it },
                        label = "Top P (opt)",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("" to "Auto", "low" to "low", "medium" to "medium", "high" to "high").forEach { (eff, label) ->
                        CleanFilterPill(
                            label = label,
                            selected = reasoningEffort == eff,
                            onClick = { reasoningEffort = eff }
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Parallel Tool Calls", style = MaterialTheme.typography.bodyMedium)
                    CleanSwitch(checked = parallel, onCheckedChange = { parallel = it })
                }

                CleanOutlinedTextField(
                    value = headersRaw,
                    onValueChange = { headersRaw = it },
                    label = "Extra Headers (Key: Value per line)",
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            CleanPrimaryButton(
                onClick = {
                    showValidation = true
                    if (formValid) onSave(buildDraft())
                },
                enabled = formValid,
                modifier = Modifier.testTag("provider_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeSettingsPane(
    vm: BorerViewModel,
    onFeedback: (String) -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val diag by vm.prootDiagnostics.collectAsStateWithLifecycle()
    val prootTestOut by vm.prootTestOutput.collectAsStateWithLifecycle()
    val prootTesting by vm.prootTesting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var setupNotice by remember { mutableStateOf<String?>(null) }
    var ignoringBatteryOpt by remember {
        mutableStateOf(BackgroundKeepAliveController.isIgnoringBatteryOptimizations(context))
    }
    var notifGranted by remember {
        mutableStateOf(BackgroundKeepAliveController.hasNotificationPermission(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOpt = BackgroundKeepAliveController.isIgnoringBatteryOptimizations(context)
                notifGranted = BackgroundKeepAliveController.hasNotificationPermission(context)
                vm.refreshProotDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted || BackgroundKeepAliveController.hasNotificationPermission(context)
    }

    val termuxPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.refreshProotDiagnostics()
        setupNotice = if (granted) "Termux RUN_COMMAND permission granted." else "Permission denied. Grant it in Android App Info or use Direct proot."
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
            .testTag("runtime_settings_list")
    ) {
        item {
            SectionHeader(title = "Background Execution (8h+ Keep-Alive)")
            GroupedListCard {
                GroupedSwitchRow(
                    title = "Foreground Run Guard",
                    subtitle = "Keep active runs alive in background via persistent notification & WakeLock",
                    checked = settings.backgroundKeepAlive,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.updateSettings { it.copy(backgroundKeepAlive = enabled) }
                    },
                    testTag = "switch_background_keep_alive"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Persistent WakeLock (Termux mode)",
                    subtitle = "Keep CPU & Wi-Fi awake continuously (8–9h+) even between turns",
                    checked = settings.persistentWakeLock,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.setPersistentWakeLock(enabled)
                    },
                    testTag = "switch_persistent_wakelock"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = "Battery Optimization",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CleanStatusBadge(
                        text = if (ignoringBatteryOpt) "UNRESTRICTED" else "OPTIMIZED",
                        inverted = ignoringBatteryOpt,
                        emphasizedBorder = !ignoringBatteryOpt
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CleanOutlinedButton(
                            onClick = { BackgroundKeepAliveController.openBatteryOptimizationSettings(context) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_battery_settings_button")
                        ) {
                            Text(if (ignoringBatteryOpt) "Battery Settings" else "Allow Unrestricted", maxLines = 1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                            CleanOutlinedButton(
                                onClick = { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("grant_notifications_button")
                            ) {
                                Text("Notifications", maxLines = 1)
                            }
                        }
                    }
                    Text(
                        text = "For 8–9h unattended background sessions, set Battery to Unrestricted in Android Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Execution Runtime",
                action = {
                    IconButton(
                        onClick = {
                            vm.refreshProotDiagnostics()
                            onFeedback("Refreshed runtime diagnostics")
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh proot diagnostics", modifier = Modifier.size(18.dp))
                    }
                }
            )
            CleanSegmentedControl(
                options = listOf(
                    SegmentTab("Android sh", "shell_backend_android"),
                    SegmentTab("Termux Ubuntu", "shell_backend_termux"),
                    SegmentTab("Direct proot", "shell_backend_direct")
                ),
                selectedIndex = when (settings.shellBackend) {
                    ShellBackend.ANDROID_SH -> 0
                    ShellBackend.TERMUX_PROOT -> 1
                    ShellBackend.PROOT_DIRECT -> 2
                },
                onSelect = { idx ->
                    when (idx) {
                        0 -> vm.updateSettings { it.copy(shellBackend = ShellBackend.ANDROID_SH) }
                        1 -> {
                            vm.updateSettings { it.copy(shellBackend = ShellBackend.TERMUX_PROOT) }
                            if (diag.termuxInstalled && !diag.termuxPermissionGranted) {
                                termuxPermLauncher.launch(UbuntuProotRuntime.TERMUX_PERMISSION)
                            }
                        }
                        else -> vm.updateSettings { it.copy(shellBackend = ShellBackend.PROOT_DIRECT) }
                    }
                }
            )
        }

        item {
            SectionHeader(title = "Environment Diagnostics")
            GroupedListCard {
                KeyValueStatRow(label = "Device ABI", value = diag.arch)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                KeyValueStatRow(
                    label = "Termux app",
                    value = if (diag.termuxInstalled) "Installed" else "Not found",
                    valueColor = if (diag.termuxInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                KeyValueStatRow(
                    label = "RUN_COMMAND",
                    value = if (diag.termuxPermissionGranted) "Granted" else "Not granted",
                    valueColor = if (diag.termuxPermissionGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.shellBackend == ShellBackend.PROOT_DIRECT) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    KeyValueStatRow(
                        label = "proot binary",
                        value = diag.directProotPath ?: "Not found",
                        valueColor = if (diag.directProotPath != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    KeyValueStatRow(
                        label = "Ubuntu rootfs",
                        value = diag.directRootfsPath ?: "Not found",
                        valueColor = if (diag.directRootfsPath != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (settings.shellBackend != ShellBackend.ANDROID_SH) {
            item {
                SectionHeader(title = "Ubuntu (proot-distro) Configuration")
                HairlineCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CleanOutlinedTextField(
                            value = settings.prootDistro,
                            onValueChange = { v -> vm.updateSettings { it.copy(prootDistro = v) } },
                            label = "Distro name (default: ubuntu)",
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (settings.shellBackend == ShellBackend.TERMUX_PROOT) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!diag.termuxPermissionGranted) {
                                    CleanOutlinedButton(
                                        onClick = { termuxPermLauncher.launch(UbuntuProotRuntime.TERMUX_PERMISSION) }
                                    ) {
                                        Text("Grant Permission")
                                    }
                                }
                                CleanOutlinedButton(
                                    onClick = {
                                        val err = vm.launchTermuxUbuntuSetup()
                                        setupNotice = err ?: "Opened Termux to install proot-distro ${settings.prootDistro} and enable external apps."
                                    }
                                ) {
                                    Text("Install in Termux")
                                }
                                CleanOutlinedButton(
                                    onClick = {
                                        val cmd = UbuntuProotRuntime.setupScript(settings.prootDistro)
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("proot-distro setup", cmd))
                                        setupNotice = "Copied Termux setup command to clipboard."
                                        onFeedback("Copied setup command")
                                    }
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy setup command", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy Cmd")
                                }
                                CleanPrimaryButton(
                                    onClick = { vm.testProotUbuntu() },
                                    enabled = !prootTesting,
                                    modifier = Modifier.testTag("test_proot_button")
                                ) {
                                    if (prootTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Test Ubuntu runtime", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Test Ubuntu")
                                    }
                                }
                            }
                        } else {
                            CleanOutlinedTextField(
                                value = settings.prootBinaryPath,
                                onValueChange = { v -> vm.updateSettings { it.copy(prootBinaryPath = v) } },
                                label = "proot binary path (optional override)",
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                modifier = Modifier.fillMaxWidth()
                            )
                            CleanOutlinedTextField(
                                value = settings.prootRootfsPath,
                                onValueChange = { v -> vm.updateSettings { it.copy(prootRootfsPath = v) } },
                                label = "Ubuntu rootfs dir (optional override)",
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                CleanPrimaryButton(
                                    onClick = { vm.testProotUbuntu() },
                                    enabled = !prootTesting,
                                    modifier = Modifier.testTag("test_proot_button")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Test direct proot", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (prootTesting) "Testing..." else "Test Direct proot")
                                }
                            }
                        }

                        setupNotice?.let { msg ->
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        prootTestOut?.let { out ->
                            SelectionContainer {
                                Text(
                                    text = out,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.background)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Local Storage Paths")
            GroupedListCard {
                KeyValueStatRow(label = "Workspace", value = vm.workspaceDir.absolutePath)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                KeyValueStatRow(label = "Runs & Artifacts", value = vm.runsDir.absolutePath)
            }
        }
    }
}

@Composable
private fun AgentSettingsPane(
    vm: BorerViewModel,
    onFeedback: (String) -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var customInstr by remember(settings.customInstructions) { mutableStateOf(settings.customInstructions) }
    var instrSaved by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var agentsDialogOpen by remember { mutableStateOf(false) }
    var agentsMdText by remember { mutableStateOf("") }
    var agentsMdLoading by remember { mutableStateOf(false) }

    LaunchedEffect(agentsDialogOpen) {
        if (agentsDialogOpen) {
            agentsMdLoading = true
            agentsMdText = withContext(Dispatchers.IO) { vm.loadAgentsMd() }
            agentsMdLoading = false
        }
    }

    LaunchedEffect(instrSaved) {
        if (instrSaved) {
            delay(1500)
            instrSaved = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Agent Persona")
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CleanSegmentedControl(
                        options = AgentProfile.entries.map { p ->
                            SegmentTab(p.label, "settings_profile_${p.wire}")
                        },
                        selectedIndex = AgentProfile.entries.indexOf(settings.agentProfile).coerceAtLeast(0),
                        onSelect = { idx ->
                            AgentProfile.entries.getOrNull(idx)?.let { picked ->
                                vm.setAgentProfile(picked)
                                onFeedback("Switched persona to ${picked.label}")
                            }
                        }
                    )
                    Text(
                        text = settings.agentProfile.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionHeader(title = "Behavior & Approvals")
            GroupedListCard {
                GroupedSwitchRow(
                    title = "Show Thought Traces",
                    subtitle = "Render collapsible model reasoning steps in chat",
                    checked = settings.showReasoning,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(showReasoning = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Supervised Tool Approvals (Chat mode)",
                    subtitle = "Ask permission before running shell, file writes/patches, or network tools",
                    checked = settings.supervisedApprovals,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(supervisedApprovals = v) } },
                    testTag = "switch_supervised_approvals"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Default to Autonomous Mode",
                    subtitle = "Start new sessions with the 400-turn autonomous harness",
                    checked = settings.defaultAutonomous,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(defaultAutonomous = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Load Workspace AGENTS.md",
                    subtitle = "Inject AGENTS.md / BORER.md project conventions into every run",
                    checked = settings.agentsMdEnabled,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(agentsMdEnabled = v) } },
                    testTag = "switch_agents_md"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Workspace AGENTS.md", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Project rules shared across turns and subagents",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CleanOutlinedButton(
                        onClick = { agentsDialogOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("edit_agents_md_button")
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Auto-Compact with Summary",
                    subtitle = "Synthesize structured handoff summary instead of dropping old turns",
                    checked = settings.autoCompactWithSummary,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(autoCompactWithSummary = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Strict Context Enforcement",
                    subtitle = "Fail closed instead of falling back on token ceiling overflow",
                    checked = settings.strictContext,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(strictContext = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Bulk Evidence Logging",
                    subtitle = "Attach extended request/response details on multi-run verify",
                    checked = settings.bulkEvidence,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(bulkEvidence = v) } }
                )
            }
        }

        item {
            SectionHeader(title = "Toolset Permissions")
            GroupedListCard {
                GroupedSwitchRow(
                    title = "Shell & Background Jobs",
                    subtitle = "bash, job (proot Ubuntu / Android sh)",
                    checked = settings.toolsets.shell,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(shell = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Filesystem & Patching",
                    subtitle = "read, write, edit, apply_patch, glob, grep, ls, diff",
                    checked = settings.toolsets.filesystem,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(filesystem = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Web, Network & Verification",
                    subtitle = "web_search, web_fetch, http, net, verify, mutate",
                    checked = settings.toolsets.network,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(network = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Isolated Subagents",
                    subtitle = "task (explore / research / verify child agents)",
                    checked = settings.toolsets.subagent,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(subagent = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "Cross-Session Memory",
                    subtitle = "learn & recall ranked technique scores",
                    checked = settings.toolsets.memory,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(memory = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSwitchRow(
                    title = "MCP Servers & Tools",
                    subtitle = "mcp__* dynamic tools, mcp_resource, mcp_prompt, and MCP web_search",
                    checked = settings.toolsets.mcp,
                    onCheckedChange = { v ->
                        vm.updateSettings { it.copy(toolsets = it.toolsets.copy(mcp = v)) }
                    },
                    testTag = "switch_toolset_mcp"
                )
            }
        }

        item {
            SectionHeader(title = "Compaction & Guardrails")
            GroupedListCard {
                GroupedSliderRow(
                    title = "Compaction threshold",
                    valueText = "${(settings.compactAt * 100).toInt()}%",
                    value = settings.compactAt.toFloat(),
                    valueRange = 0.5f..0.95f,
                    onValueChange = { v -> vm.updateSettings { it.copy(compactAt = v.toDouble()) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Verbatim turns kept",
                    valueText = "${settings.verbatimTurns} turns",
                    value = settings.verbatimTurns.toFloat(),
                    valueRange = 2f..36f,
                    onValueChange = { v -> vm.updateSettings { it.copy(verbatimTurns = v.toInt().coerceIn(2, 36)) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Inline artifact spill budget",
                    valueText = "${settings.inlineBudget / 1024} KB",
                    value = (settings.inlineBudget / 1024).toFloat(),
                    valueRange = 2f..64f,
                    onValueChange = { v -> vm.updateSettings { it.copy(inlineBudget = v.toInt().coerceIn(2, 64) * 1024) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Default shell tool timeout",
                    valueText = "${settings.defaultToolTimeoutMs / 1000}s",
                    value = (settings.defaultToolTimeoutMs / 1000L).toFloat(),
                    valueRange = 30f..300f,
                    onValueChange = { v ->
                        vm.updateSettings { it.copy(defaultToolTimeoutMs = v.toLong().coerceIn(30L, 300L) * 1000L) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Max inline output cap",
                    valueText = "${settings.maxToolOutputBytes / 1024} KB",
                    value = (settings.maxToolOutputBytes / 1024).toFloat(),
                    valueRange = 16f..256f,
                    onValueChange = { v ->
                        vm.updateSettings { it.copy(maxToolOutputBytes = v.toInt().coerceIn(16, 256) * 1024) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Doom-loop breaker",
                    valueText = if (settings.doomLoopThreshold == 0) "Off" else "${settings.doomLoopThreshold} repeats",
                    value = settings.doomLoopThreshold.toFloat(),
                    valueRange = 0f..8f,
                    onValueChange = { v -> vm.updateSettings { it.copy(doomLoopThreshold = v.toInt().coerceIn(0, 8)) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "No-progress nudge",
                    valueText = "${settings.noProgressNudge} turns",
                    value = settings.noProgressNudge.toFloat(),
                    valueRange = 5f..100f,
                    onValueChange = { v -> vm.updateSettings { it.copy(noProgressNudge = v.toInt().coerceIn(5, 100)) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Consecutive error soft-stop",
                    valueText = "${settings.softStopErrors} errors",
                    value = settings.softStopErrors.toFloat(),
                    valueRange = 5f..100f,
                    onValueChange = { v -> vm.updateSettings { it.copy(softStopErrors = v.toInt().coerceIn(5, 100)) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GroupedSliderRow(
                    title = "Default verify runs",
                    valueText = "${settings.evidenceRecords} runs",
                    value = settings.evidenceRecords.toFloat(),
                    valueRange = 1f..10f,
                    onValueChange = { v -> vm.updateSettings { it.copy(evidenceRecords = v.toInt().coerceIn(1, 10)) } }
                )
            }
        }

        item {
            SectionHeader(title = "Extra System Instructions")
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Appended after core borer instructions for every session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CleanOutlinedTextField(
                        value = customInstr,
                        onValueChange = { customInstr = it },
                        label = "Optional operator rules",
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (instrSaved) {
                            CleanStatusBadge(text = "Saved", inverted = true)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (customInstr.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    customInstr = ""
                                    vm.updateSettings { it.copy(customInstructions = "") }
                                    instrSaved = true
                                    onFeedback("Cleared custom instructions")
                                }
                            ) {
                                Text("Clear")
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        CleanPrimaryButton(
                            onClick = {
                                vm.updateSettings { it.copy(customInstructions = customInstr.trim()) }
                                instrSaved = true
                                onFeedback("Saved instructions")
                            }
                        ) {
                            Text("Save Instructions")
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { confirmResetAll = true }) {
                    Text("Reset All Harness & Runtime Settings")
                }
            }
        }
    }

    if (agentsDialogOpen) {
        AlertDialog(
            onDismissRequest = { agentsDialogOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = { Text("Workspace AGENTS.md") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    Text(
                        "Conventions, build commands, and custom guidelines saved in workspace/AGENTS.md.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (agentsMdLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        CleanOutlinedTextField(
                            value = agentsMdText,
                            onValueChange = { agentsMdText = it },
                            label = "AGENTS.md content",
                            minLines = 8,
                            maxLines = 14,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            modifier = Modifier.fillMaxWidth().testTag("agents_md_editor_input")
                        )
                    }
                }
            },
            confirmButton = {
                CleanPrimaryButton(
                    onClick = {
                        vm.saveAgentsMd(agentsMdText)
                        agentsDialogOpen = false
                        onFeedback("Saved AGENTS.md")
                    },
                    enabled = !agentsMdLoading
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { agentsDialogOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = { Text("Reset Harness Settings?") },
            text = { Text("Restore compaction thresholds, evidence budget, shell backend, and custom instructions to defaults?") },
            confirmButton = {
                CleanPrimaryButton(onClick = {
                    vm.resetAllSettingsToDefaults()
                    confirmResetAll = false
                    onFeedback("Restored default settings")
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AlertsSettingsPane(
    vm: BorerViewModel,
    onFeedback: (String) -> Unit
) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val notifyTestResult by vm.notifyTestResult.collectAsStateWithLifecycle()

    var webhookUrl by remember(cfg.notify) { mutableStateOf(cfg.notify.webhookUrl.orEmpty()) }
    var telegramToken by remember(cfg.notify) { mutableStateOf(cfg.notify.telegramToken.orEmpty()) }
    var telegramChatId by remember(cfg.notify) { mutableStateOf(cfg.notify.telegramChatId.orEmpty()) }
    var discordUrl by remember(cfg.notify) { mutableStateOf(cfg.notify.discordWebhookUrl.orEmpty()) }
    var limit by remember(cfg.notify) { mutableIntStateOf(cfg.notify.limit) }
    var showTgToken by remember { mutableStateOf(false) }
    var savedBadge by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(savedBadge) {
        if (savedBadge) {
            delay(1500)
            savedBadge = false
        }
    }

    fun saveCurrent() {
        vm.updateNotifyConfig(
            NotifyConfig(
                webhookUrl = webhookUrl,
                telegramToken = telegramToken,
                telegramChatId = telegramChatId,
                discordWebhookUrl = discordUrl,
                limit = limit
            )
        )
        savedBadge = true
        onFeedback("Saved notification settings")
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Webhook Destinations")
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Autonomous runs and hunt queue summaries dispatch to all configured channels below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CleanOutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { webhookUrl = it },
                        label = "Generic Webhook URL",
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )

                    CleanOutlinedTextField(
                        value = discordUrl,
                        onValueChange = { discordUrl = it },
                        label = "Discord Webhook URL",
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )

                    CleanOutlinedTextField(
                        value = telegramToken,
                        onValueChange = { telegramToken = it },
                        label = "Telegram Bot Token",
                        singleLine = true,
                        visualTransformation = if (showTgToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showTgToken = !showTgToken }) {
                                Icon(
                                    imageVector = if (showTgToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "Toggle token visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    CleanOutlinedTextField(
                        value = telegramChatId,
                        onValueChange = { telegramChatId = it },
                        label = "Telegram Chat ID",
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            SectionHeader(title = "Delivery Cap & Test")
            GroupedListCard {
                GroupedSliderRow(
                    title = "Max notifications per session",
                    valueText = "$limit / run",
                    value = limit.toFloat(),
                    valueRange = 1f..100f,
                    onValueChange = { limit = it.toInt().coerceIn(1, 100) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CleanPrimaryButton(
                            onClick = { saveCurrent() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (savedBadge) "Saved" else "Save")
                        }
                        CleanOutlinedButton(
                            onClick = {
                                saveCurrent()
                                vm.testNotification()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send Test Alert")
                        }
                    }

                    notifyTestResult?.let { res ->
                        Text(res, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset Notifications to Defaults")
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = { Text("Reset Notifications?") },
            text = { Text("Clear custom webhooks and restore Build Secrets notification defaults?") },
            confirmButton = {
                CleanPrimaryButton(onClick = {
                    vm.resetNotifyToDefaults()
                    confirmReset = false
                    onFeedback("Restored default notifications")
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpSettingsPane(
    vm: BorerViewModel,
    onFeedback: (String) -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpStates by vm.mcpStates.collectAsStateWithLifecycle()
    val installingId by vm.mcpInstallingId.collectAsStateWithLifecycle()
    val installLog by vm.mcpInstallLog.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editorOpen by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var importDialogOpen by remember { mutableStateOf(false) }
    var inspectingState by remember { mutableStateOf<McpServerState?>(null) }

    val stateById = remember(mcpStates) { mcpStates.associateBy { it.config.id } }
    val displayStates = remember(settings.mcpServers, stateById) {
        settings.mcpServers.map { cfg ->
            stateById[cfg.id] ?: McpServerState(config = cfg, status = McpConnectionStatus.DISCONNECTED)
        }
    }
    val whsearchState = remember(displayStates) {
        displayStates.firstOrNull { it.config.id.equals("whsearch", ignoreCase = true) }
    }
    val connectedCount = remember(displayStates) {
        displayStates.count { it.status == McpConnectionStatus.CONNECTED }
    }
    val totalToolsCount = remember(displayStates) {
        displayStates.sumOf { it.tools.size }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
            .testTag("mcp_settings_list")
    ) {
        item {
            HairlineCard {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("MCP Servers", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "$connectedCount/${displayStates.size} connected · $totalToolsCount dynamic tools · HTTP, SSE & Stdio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CleanPrimaryButton(
                        onClick = {
                            editingServer = null
                            editorOpen = true
                        },
                        modifier = Modifier.testTag("add_mcp_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add MCP server", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CleanOutlinedButton(
                        onClick = { importDialogOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("import_mcp_json_button")
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Import JSON")
                    }
                    CleanOutlinedButton(
                        onClick = {
                            val json = vm.exportMcpServersJson()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("mcpServers JSON", json))
                            onFeedback("Copied mcpServers JSON")
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Export JSON")
                    }
                    CleanOutlinedButton(
                        onClick = {
                            vm.refreshAllMcpServers()
                            onFeedback("Refreshing enabled MCP servers")
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("refresh_mcp_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Refresh All")
                    }
                }
            }
        }

        // Featured Ashveil1/whsearch web_search MCP card
        item {
            val isInstallingWh = installingId == "whsearch"
            val whConnected = whsearchState?.status == McpConnectionStatus.CONNECTED
            HairlineCard(highlighted = whConnected) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "whsearch · web_search MCP",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    CleanStatusBadge(
                        text = when {
                            isInstallingWh -> "INSTALLING"
                            whConnected -> "ACTIVE"
                            whsearchState?.status == McpConnectionStatus.CONNECTING -> "CONNECTING"
                            whsearchState?.status == McpConnectionStatus.ERROR -> "ERROR"
                            else -> "READY TO SETUP"
                        },
                        inverted = whConnected
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "github.com/Ashveil1/whsearch — When connected, Borer automatically routes web_search calls through whsearch MCP (with built-in fallback if offline).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CleanPrimaryButton(
                        onClick = { vm.installWhsearchPreset() },
                        enabled = installingId == null,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("install_whsearch_button")
                    ) {
                        if (isInstallingWh) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Installing...")
                        } else {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Install from GitHub")
                        }
                    }

                    if (whsearchState != null) {
                        CleanOutlinedButton(
                            onClick = {
                                if (whConnected) vm.disconnectMcpServer(whsearchState.config.id)
                                else vm.connectMcpServer(whsearchState.config.id)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("connect_whsearch_button")
                        ) {
                            Icon(
                                imageVector = if (whConnected) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (whConnected) "Disconnect" else "Connect")
                        }
                    }
                }
            }
        }

        if (!installLog.isNullOrBlank()) {
            item {
                HairlineCard {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("MCP Setup Output", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { vm.clearMcpInstallLog() }) {
                            Text("Dismiss")
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = installLog.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        if (displayStates.isEmpty()) {
            item {
                CleanEmptyState(
                    icon = Icons.Rounded.Hub,
                    title = "No MCP servers configured",
                    subtitle = "Tap Add or Import JSON to connect Streamable HTTP, SSE, or Stdio MCP tools, resources, and prompts."
                )
            }
        } else {
            item {
                SectionHeader(title = "Configured Servers", trailing = "${displayStates.size} total")
            }
            itemsIndexed(displayStates, key = { _, st -> st.config.id }) { _, st ->
                val cfg = st.config
                val isConnected = st.status == McpConnectionStatus.CONNECTED
                val isConnecting = st.status == McpConnectionStatus.CONNECTING
                val isErr = st.status == McpConnectionStatus.ERROR
                val isInstalling = installingId == cfg.id

                HairlineCard(
                    highlighted = isConnected,
                    modifier = Modifier.testTag("mcp_card_${cfg.id}")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = cfg.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                CleanStatusBadge(
                                    text = st.status.name,
                                    inverted = isConnected,
                                    emphasizedBorder = isErr
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = buildString {
                                    append("id=${cfg.id} · ${cfg.transport.label}")
                                    if (cfg.useForWebSearch) append(" · web_search")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CleanSwitch(
                            checked = cfg.enabled,
                            onCheckedChange = { enabled -> vm.toggleMcpServer(cfg.id, enabled) }
                        )
                    }

                    val endpointDesc = when {
                        cfg.url.isNotBlank() -> cfg.url
                        cfg.command.isNotBlank() -> "${cfg.command} ${cfg.args.joinToString(" ")}".trim()
                        cfg.repoUrl.isNotBlank() -> cfg.repoUrl
                        else -> "No endpoint or command set"
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = endpointDesc,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isConnected) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${st.tools.size} tools · ${st.resources.size} resources · ${st.prompts.size} prompts" +
                                (st.protocolVersion?.let { " · MCP $it" }.orEmpty()),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isErr && !st.errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = st.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CleanOutlinedButton(
                            onClick = {
                                if (isConnected) vm.disconnectMcpServer(cfg.id)
                                else vm.connectMcpServer(cfg.id)
                            },
                            enabled = !isConnecting && !isInstalling,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(5.dp))
                                Text("Connecting...")
                            } else {
                                Icon(
                                    imageVector = if (isConnected) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (isConnected) "Disconnect" else "Connect")
                            }
                        }

                        CleanOutlinedButton(
                            onClick = { inspectingState = st },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Inspect (${st.tools.size})")
                        }

                        if (cfg.repoUrl.isNotBlank()) {
                            CleanOutlinedButton(
                                onClick = { vm.installGitHubMcpServer(cfg.id) },
                                enabled = installingId == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isInstalling) "Installing..." else "Install")
                            }
                        }

                        CleanOutlinedButton(
                            onClick = {
                                editingServer = cfg
                                editorOpen = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit MCP server", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }

                        IconButton(
                            onClick = {
                                vm.deleteMcpServer(cfg.id)
                                onFeedback("Removed MCP server '${cfg.name}'")
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete MCP server",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        McpServerEditorDialog(
            initial = editingServer,
            onDismiss = { editorOpen = false },
            onSave = { server ->
                vm.upsertMcpServer(server)
                editorOpen = false
                onFeedback("Saved MCP '${server.name}'")
            }
        )
    }

    if (importDialogOpen) {
        McpImportJsonDialog(
            onDismiss = { importDialogOpen = false },
            onImport = { raw ->
                vm.importMcpServersJson(raw).onSuccess { count ->
                    importDialogOpen = false
                    onFeedback("Imported $count MCP server(s)")
                }
            }
        )
    }

    inspectingState?.let { st ->
        McpInspectDialog(
            state = st,
            onDismiss = { inspectingState = null }
        )
    }
}

@Composable
private fun McpServerEditorDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var id by remember { mutableStateOf(initial?.id ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var transport by remember { mutableStateOf(initial?.transport ?: McpTransport.AUTO) }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var argsRaw by remember { mutableStateOf(initial?.args?.joinToString("\n").orEmpty()) }
    var repoUrl by remember { mutableStateOf(initial?.repoUrl ?: "") }
    var useForWebSearch by remember { mutableStateOf(initial?.useForWebSearch ?: false) }
    var timeoutMs by remember { mutableStateOf((initial?.timeoutMs ?: 30_000L).toString()) }
    var headersRaw by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
    }
    var envRaw by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty())
    }

    val isHttpLike = transport != McpTransport.STDIO
    val valid = (name.isNotBlank() || id.isNotBlank()) &&
        if (transport == McpTransport.STDIO) command.isNotBlank()
        else (url.isNotBlank() || command.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = { Text(if (initial == null) "Add MCP Server" else "Edit MCP Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CleanOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Display Name",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("mcp_name_input")
                )
                CleanOutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = "Server ID (slug, optional)",
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                SectionHeader(title = "Transport")
                CleanSegmentedControl(
                    options = listOf(
                        SegmentTab("Auto", "mcp_tr_auto"),
                        SegmentTab("HTTP", "mcp_tr_http"),
                        SegmentTab("SSE", "mcp_tr_sse"),
                        SegmentTab("Stdio", "mcp_tr_stdio")
                    ),
                    selectedIndex = when (transport) {
                        McpTransport.AUTO -> 0
                        McpTransport.STREAMABLE_HTTP -> 1
                        McpTransport.SSE -> 2
                        McpTransport.STDIO -> 3
                    },
                    onSelect = { idx ->
                        transport = when (idx) {
                            0 -> McpTransport.AUTO
                            1 -> McpTransport.STREAMABLE_HTTP
                            2 -> McpTransport.SSE
                            else -> McpTransport.STDIO
                        }
                    }
                )
                if (isHttpLike) {
                    CleanOutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "Server URL (e.g. https://host/mcp or /sse)",
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth().testTag("mcp_url_input")
                    )
                    CleanOutlinedTextField(
                        value = headersRaw,
                        onValueChange = { headersRaw = it },
                        label = "HTTP Headers (Key: Value per line)",
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (transport == McpTransport.STDIO || transport == McpTransport.AUTO) {
                    CleanOutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = "Stdio Command (e.g. npx, python3, sh)",
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth().testTag("mcp_cmd_input")
                    )
                    CleanOutlinedTextField(
                        value = argsRaw,
                        onValueChange = { argsRaw = it },
                        label = "Args (one argument per line)",
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CleanOutlinedTextField(
                        value = envRaw,
                        onValueChange = { envRaw = it },
                        label = "Environment (KEY=VALUE per line)",
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                CleanOutlinedTextField(
                    value = repoUrl,
                    onValueChange = { repoUrl = it },
                    label = "GitHub Repo URL (optional)",
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                CleanOutlinedTextField(
                    value = timeoutMs,
                    onValueChange = { timeoutMs = it },
                    label = "Timeout (ms)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use as web_search provider", style = MaterialTheme.typography.bodyMedium)
                    CleanSwitch(checked = useForWebSearch, onCheckedChange = { useForWebSearch = it })
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    CleanSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            CleanPrimaryButton(
                onClick = {
                    val headers = headersRaw.lines().mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }.filter { it.first.isNotBlank() }.toMap()
                    val env = envRaw.lines().mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }.filter { it.first.isNotBlank() }.toMap()
                    val parsedArgs = argsRaw.lines().filter { it.isNotEmpty() }
                    val resolvedId = (id.trim().ifBlank { name.trim() })
                    onSave(
                        McpServerConfig(
                            id = resolvedId,
                            name = name.trim().ifBlank { resolvedId },
                            enabled = enabled,
                            transport = transport,
                            url = url.trim(),
                            command = command.trim(),
                            args = parsedArgs,
                            env = env,
                            headers = headers,
                            repoUrl = repoUrl.trim(),
                            useForWebSearch = useForWebSearch,
                            timeoutMs = (timeoutMs.trim().toLongOrNull() ?: 30_000L).coerceIn(3_000L, 120_000L)
                        )
                    )
                },
                enabled = valid,
                modifier = Modifier.testTag("mcp_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun McpImportJsonDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Result<Int>
) {
    var jsonText by remember {
        mutableStateOf(
            """
            {
              "mcpServers": {
                "whsearch": {
                  "command": "whsearch",
                  "args": [],
                  "repoUrl": "https://github.com/Ashveil1/whsearch",
                  "useForWebSearch": true
                }
              }
            }
            """.trimIndent()
        )
    }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = { Text("Import mcpServers JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste standard MCP config JSON (Streamable HTTP, SSE, or Stdio).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CleanOutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it; err = null },
                    label = "mcpServers JSON",
                    minLines = 7,
                    maxLines = 14,
                    isError = err != null,
                    supportingText = err,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth().testTag("mcp_import_json_input")
                )
            }
        },
        confirmButton = {
            CleanPrimaryButton(
                onClick = {
                    onImport(jsonText).onFailure { t ->
                        err = t.message ?: "Invalid JSON"
                    }
                },
                enabled = jsonText.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun McpInspectDialog(
    state: McpServerState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = {
            Text(
                text = state.serverName ?: state.config.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Status: ${state.status.name}" +
                        (state.protocolVersion?.let { " · Protocol $it" }.orEmpty()) +
                        (state.serverVersion?.let { " · v$it" }.orEmpty()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.instructions.isNullOrBlank()) {
                    Text(
                        text = state.instructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                SectionHeader(title = "Tools (${state.tools.size})")
                if (state.tools.isEmpty()) {
                    Text("No tools exposed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.tools.forEach { t ->
                        val props = (t.inputSchema["properties"] as? JsonObject)?.keys?.joinToString(", ").orEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = t.name,
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily)
                            )
                            if (props.isNotBlank()) {
                                Text(
                                    text = "args: $props",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (t.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = t.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (state.resources.isNotEmpty() || state.resourceTemplates.isNotEmpty()) {
                    SectionHeader(title = "Resources (${state.resources.size + state.resourceTemplates.size})")
                    state.resources.forEach { r ->
                        Text(
                            text = "${r.uri} — ${r.name}${r.mimeType?.let { " ($it)" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                    }
                    state.resourceTemplates.forEach { rt ->
                        Text(
                            text = "${rt.uriTemplate} — ${rt.name}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.prompts.isNotEmpty()) {
                    SectionHeader(title = "Prompts (${state.prompts.size})")
                    state.prompts.forEach { p ->
                        val pArgs = p.arguments.joinToString(", ") { a -> if (a.required) "${a.name}*" else a.name }
                        Text(
                            text = "${p.name}($pArgs) — ${p.description}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (state.stderrTail.isNotEmpty()) {
                    SectionHeader(title = "Stderr Tail")
                    SelectionContainer {
                        Text(
                            text = state.stderrTail.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun formatCtx(tokens: Int): String =
    if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"

private fun maskKey(key: String): String {
    val t = key.trim()
    if (t.length <= 8) return "••••"
    return "${t.take(4)}…${t.takeLast(4)}"
}
