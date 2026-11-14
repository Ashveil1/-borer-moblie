package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ApiKind
import com.example.data.ContextSource
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.ShellBackend
import com.example.runtime.BackgroundKeepAliveController
import com.example.runtime.UbuntuProotRuntime
import com.example.ui.BorerViewModel
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                2 -> AgentSettingsPane(vm, onFeedback = ::showFeedback)
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
                            editingIndex = null
                            editingInitial = null
                            editorOpen = true
                        },
                        modifier = Modifier.testTag("add_provider_button")
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add provider", modifier = Modifier.size(18.dp))
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
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Test provider", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test")
                        }
                        Spacer(Modifier.width(8.dp))
                        CleanOutlinedButton(
                            onClick = {
                                editingIndex = idx
                                editingInitial = p
                                editorOpen = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit provider", modifier = Modifier.size(16.dp))
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
                                Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move priority up")
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
                                Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move priority down")
                            }
                        }
                        IconButton(
                            onClick = {
                                vm.deleteProvider(idx)
                                onFeedback("Removed provider '${p.name}'")
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete provider", tint = MaterialTheme.colorScheme.onSurface)
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
            onDismiss = { editorOpen = false },
            onSave = { profile ->
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

    val trimmedUrl = baseUrl.trim()
    val urlValid = trimmedUrl.startsWith("http://", ignoreCase = true) ||
        trimmedUrl.startsWith("https://", ignoreCase = true)
    val urlError = when {
        trimmedUrl.isBlank() -> "Base URL is required"
        !urlValid -> "Must start with http:// or https://"
        trimmedUrl.removePrefix("https://").removePrefix("http://").isBlank() -> "Enter a valid host"
        else -> null
    }
    val modelError = if (model.isBlank()) "Model ID is required" else null
    val keyError = if (apiKey.isBlank()) "API key is required (use 'ollama' for local)" else null
    val formValid = urlError == null && modelError == null && keyError == null

    fun buildDraft(): ProviderProfile {
        val parsedCtx = ctxWindow.trim().toIntOrNull() ?: 0
        val parsedHeaders = headersRaw.lines()
            .mapNotNull { line ->
                val sep = line.indexOf(':')
                if (sep <= 0) null
                else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }.filter { it.first.isNotBlank() }.toMap()

        return ProviderProfile(
            id = name.trim().ifBlank { "provider" },
            name = name.trim().ifBlank { "provider" },
            baseUrl = trimmedUrl,
            model = model.trim(),
            apiKey = apiKey.trim(),
            api = apiKind,
            contextWindow = if (parsedCtx > 0) parsedCtx else 0,
            contextSource = if (parsedCtx > 0) ContextSource.CUSTOM else ContextSource.FALLBACK,
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
                modifier = Modifier.verticalScroll(rememberScrollState())
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
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("provider_key_input")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CleanOutlinedButton(
                        onClick = {
                            showValidation = true
                            if (formValid) onTest(buildDraft())
                        },
                        enabled = !testing,
                        modifier = Modifier.testTag("provider_dialog_test_button")
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Testing...")
                        } else {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test Connection")
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
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh proot diagnostics", modifier = Modifier.size(18.dp))
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
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy setup command", modifier = Modifier.size(16.dp))
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
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Test Ubuntu runtime", modifier = Modifier.size(16.dp))
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
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Test direct proot", modifier = Modifier.size(16.dp))
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
            SectionHeader(title = "Behavior")
            GroupedListCard {
                GroupedSwitchRow(
                    title = "Show Thought Traces",
                    subtitle = "Render collapsible model reasoning steps in chat",
                    checked = settings.showReasoning,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(showReasoning = v) } }
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
                                    imageVector = if (showTgToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
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

private fun formatCtx(tokens: Int): String =
    if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"

private fun maskKey(key: String): String {
    val t = key.trim()
    if (t.length <= 8) return "••••"
    return "${t.take(4)}…${t.takeLast(4)}"
}
