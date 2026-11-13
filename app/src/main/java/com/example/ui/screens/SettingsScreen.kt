package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ApiKind
import com.example.data.ContextSource
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.ShellBackend
import com.example.runtime.UbuntuProotRuntime
import com.example.ui.BorerViewModel
import kotlinx.coroutines.delay

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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
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
            modifier = Modifier.widthIn(max = 760.dp)
        )

        when (tabIdx) {
            0 -> ProvidersSettingsPane(vm)
            1 -> RuntimeSettingsPane(vm)
            2 -> AgentSettingsPane(vm)
            else -> AlertsSettingsPane(vm)
        }
    }
}

@Composable
private fun ProvidersSettingsPane(vm: BorerViewModel) {
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
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
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
                    Button(
                        onClick = {
                            editingIndex = null
                            editingInitial = null
                            editorOpen = true
                        },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("add_provider_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add provider")
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
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                                .clickable {
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
                        ) {
                            Text(
                                text = "+ ${preset.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }

        if (testResult != null || testing) {
            item {
                HairlineCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = if (testing) "Testing provider endpoint..." else testResult.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testResult?.startsWith("OK") == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
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
                HairlineCard(highlighted = idx == 0) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (idx == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Text(
                                text = if (idx == 0) "Primary · ${p.name}" else "Fallback #${idx + 1} · ${p.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (idx == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${p.api.name.lowercase()} · ${formatCtx(p.contextWindow)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = p.model,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${p.baseUrl} · ${maskKey(p.apiKey)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { vm.testProviderConnection(p) },
                            enabled = !testing,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Test provider", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                editingIndex = idx
                                editingInitial = p
                                editorOpen = true
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit provider", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        Spacer(Modifier.weight(1f))
                        if (idx > 0) {
                            IconButton(
                                onClick = { vm.moveProvider(idx, -1) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move priority up")
                            }
                        }
                        if (idx < cfg.providers.lastIndex) {
                            IconButton(
                                onClick = { vm.moveProvider(idx, 1) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move priority down")
                            }
                        }
                        IconButton(
                            onClick = { vm.deleteProvider(idx) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete provider", tint = MaterialTheme.colorScheme.error)
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
            title = { Text("Reset Providers?") },
            text = { Text("This clears custom provider profiles saved on this device and restores Build Secrets defaults.") },
            confirmButton = {
                Button(onClick = { vm.resetProvidersToDefaults(); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }

    if (editorOpen) {
        ProviderEditorDialog(
            initial = editingInitial,
            onDismiss = { editorOpen = false },
            onSave = { profile ->
                vm.upsertProvider(profile, editingIndex)
                editorOpen = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorDialog(
    initial: ProviderProfile?,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (initial == null || initial.apiKey.isBlank()) "Configure Provider" else "Edit Provider") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("Connection", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("provider_name_input")
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (OpenAI-compatible /v1)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("provider_url_input")
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model ID (e.g. gpt-5, deepseek-reasoner)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("provider_model_input")
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("provider_key_input")
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("API & Token Limits", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = apiKind == ApiKind.CHAT,
                        onClick = { apiKind = ApiKind.CHAT },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Chat", maxLines = 1) }
                    SegmentedButton(
                        selected = apiKind == ApiKind.RESPONSES,
                        onClick = { apiKind = ApiKind.RESPONSES },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Responses", maxLines = 1) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ctxWindow,
                        onValueChange = { ctxWindow = it },
                        label = { Text("Context (0=auto)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxOut,
                        onValueChange = { maxOut = it },
                        label = { Text("Max output") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("Sampling & Reasoning", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = temp,
                        onValueChange = { temp = it },
                        label = { Text("Temp (opt)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = topP,
                        onValueChange = { topP = it },
                        label = { Text("Top P (opt)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("" to "Auto", "low" to "low", "medium" to "medium", "high" to "high").forEach { (eff, label) ->
                        FilterChip(
                            selected = reasoningEffort == eff,
                            onClick = { reasoningEffort = eff },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Parallel Tool Calls", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = parallel, onCheckedChange = { parallel = it })
                }

                OutlinedTextField(
                    value = headersRaw,
                    onValueChange = { headersRaw = it },
                    label = { Text("Extra Headers (Key: Value per line)") },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedCtx = ctxWindow.trim().toIntOrNull() ?: 0
                    val parsedHeaders = headersRaw.lines()
                        .mapNotNull { line ->
                            val sep = line.indexOf(':')
                            if (sep <= 0) null
                            else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
                        }.filter { it.first.isNotBlank() }.toMap()

                    onSave(
                        ProviderProfile(
                            id = name.trim().ifBlank { "provider" },
                            name = name.trim().ifBlank { "provider" },
                            baseUrl = baseUrl.trim(),
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
                    )
                },
                enabled = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank(),
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
private fun RuntimeSettingsPane(vm: BorerViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val diag by vm.prootDiagnostics.collectAsStateWithLifecycle()
    val prootTestOut by vm.prootTestOutput.collectAsStateWithLifecycle()
    val prootTesting by vm.prootTesting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var setupNotice by remember { mutableStateOf<String?>(null) }

    val termuxPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.refreshProotDiagnostics()
        setupNotice = if (granted) "Termux RUN_COMMAND permission granted." else "Permission denied. Grant it in Android App Info or use Direct proot."
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            SectionHeader(
                title = "Execution Runtime",
                action = {
                    IconButton(
                        onClick = { vm.refreshProotDiagnostics() },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh proot diagnostics", modifier = Modifier.size(18.dp))
                    }
                }
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = settings.shellBackend == ShellBackend.ANDROID_SH,
                    onClick = { vm.updateSettings { it.copy(shellBackend = ShellBackend.ANDROID_SH) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    modifier = Modifier.testTag("shell_backend_android")
                ) { Text("Android sh", maxLines = 1) }
                SegmentedButton(
                    selected = settings.shellBackend == ShellBackend.TERMUX_PROOT,
                    onClick = {
                        vm.updateSettings { it.copy(shellBackend = ShellBackend.TERMUX_PROOT) }
                        if (diag.termuxInstalled && !diag.termuxPermissionGranted) {
                            termuxPermLauncher.launch(UbuntuProotRuntime.TERMUX_PERMISSION)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    modifier = Modifier.testTag("shell_backend_termux")
                ) { Text("Termux Ubuntu", maxLines = 1) }
                SegmentedButton(
                    selected = settings.shellBackend == ShellBackend.PROOT_DIRECT,
                    onClick = { vm.updateSettings { it.copy(shellBackend = ShellBackend.PROOT_DIRECT) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    modifier = Modifier.testTag("shell_backend_direct")
                ) { Text("Direct proot", maxLines = 1) }
            }
        }

        item {
            SectionHeader(title = "Environment Diagnostics")
            GroupedListCard {
                KeyValueStatRow(label = "Device ABI", value = diag.arch)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                KeyValueStatRow(
                    label = "Termux app",
                    value = if (diag.termuxInstalled) "Installed" else "Not found",
                    valueColor = if (diag.termuxInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                KeyValueStatRow(
                    label = "RUN_COMMAND",
                    value = if (diag.termuxPermissionGranted) "Granted" else "Not granted",
                    valueColor = if (diag.termuxPermissionGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                )
                if (settings.shellBackend == ShellBackend.PROOT_DIRECT) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    KeyValueStatRow(
                        label = "proot binary",
                        value = diag.directProotPath ?: "Not found",
                        valueColor = if (diag.directProotPath != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    KeyValueStatRow(
                        label = "Ubuntu rootfs",
                        value = diag.directRootfsPath ?: "Not found",
                        valueColor = if (diag.directRootfsPath != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        if (settings.shellBackend != ShellBackend.ANDROID_SH) {
            item {
                SectionHeader(title = "Ubuntu (proot-distro) Configuration")
                HairlineCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = settings.prootDistro,
                            onValueChange = { v -> vm.updateSettings { it.copy(prootDistro = v) } },
                            label = { Text("Distro name (default: ubuntu)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (settings.shellBackend == ShellBackend.TERMUX_PROOT) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!diag.termuxPermissionGranted) {
                                    OutlinedButton(
                                        onClick = { termuxPermLauncher.launch(UbuntuProotRuntime.TERMUX_PERMISSION) },
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Grant Permission")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        val err = vm.launchTermuxUbuntuSetup()
                                        setupNotice = err ?: "Opened Termux to install proot-distro ${settings.prootDistro} and enable external apps."
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("Install in Termux")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val cmd = UbuntuProotRuntime.setupScript(settings.prootDistro)
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("proot-distro setup", cmd))
                                        setupNotice = "Copied Termux setup command to clipboard."
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy setup command", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy Cmd")
                                }
                                Button(
                                    onClick = { vm.testProotUbuntu() },
                                    enabled = !prootTesting,
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("test_proot_button")
                                ) {
                                    if (prootTesting) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Test Ubuntu runtime", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Test Ubuntu")
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = settings.prootBinaryPath,
                                onValueChange = { v -> vm.updateSettings { it.copy(prootBinaryPath = v) } },
                                label = { Text("proot binary path (optional override)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = settings.prootRootfsPath,
                                onValueChange = { v -> vm.updateSettings { it.copy(prootRootfsPath = v) } },
                                label = { Text("Ubuntu rootfs dir (optional override)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { vm.testProotUbuntu() },
                                    enabled = !prootTesting,
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("test_proot_button")
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Test direct proot", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (prootTesting) "Testing..." else "Test Direct proot")
                                }
                            }
                        }

                        setupNotice?.let { msg ->
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
private fun AgentSettingsPane(vm: BorerViewModel) {
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
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Appended after core borer instructions for every session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customInstr,
                        onValueChange = { customInstr = it },
                        label = { Text("Optional operator rules") },
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (instrSaved) {
                            Text(
                                text = "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                        }
                        if (customInstr.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    customInstr = ""
                                    vm.updateSettings { it.copy(customInstructions = "") }
                                    instrSaved = true
                                }
                            ) {
                                Text("Clear")
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Button(
                            onClick = {
                                vm.updateSettings { it.copy(customInstructions = customInstr.trim()) }
                                instrSaved = true
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
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
            title = { Text("Reset Harness Settings?") },
            text = { Text("Restore compaction thresholds, evidence budget, shell backend, and custom instructions to defaults?") },
            confirmButton = {
                Button(onClick = { vm.resetAllSettingsToDefaults(); confirmResetAll = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AlertsSettingsPane(vm: BorerViewModel) {
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
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
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

                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { webhookUrl = it },
                        label = { Text("Generic Webhook URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = discordUrl,
                        onValueChange = { discordUrl = it },
                        label = { Text("Discord Webhook URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = telegramToken,
                        onValueChange = { telegramToken = it },
                        label = { Text("Telegram Bot Token") },
                        singleLine = true,
                        visualTransformation = if (showTgToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showTgToken = !showTgToken }) {
                                Icon(
                                    imageVector = if (showTgToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle token visibility"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = telegramChatId,
                        onValueChange = { telegramChatId = it },
                        label = { Text("Telegram Chat ID") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
                        Button(
                            onClick = { saveCurrent() },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Text(if (savedBadge) "Saved" else "Save")
                        }
                        OutlinedButton(
                            onClick = {
                                saveCurrent()
                                vm.testNotification()
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Text("Send Test Alert")
                        }
                    }

                    notifyTestResult?.let { res ->
                        Text(res, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
            title = { Text("Reset Notifications?") },
            text = { Text("Clear custom webhooks and restore Build Secrets notification defaults?") },
            confirmButton = {
                Button(onClick = { vm.resetNotifyToDefaults(); confirmReset = false }) { Text("Reset") }
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
