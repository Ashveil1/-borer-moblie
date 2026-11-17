package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AgentProfile
import com.example.data.FileDiffItem
import com.example.data.ShellBackend
import com.example.runtime.BackgroundKeepAliveController
import com.example.ui.ApprovalDecision
import com.example.ui.BorerViewModel
import com.example.ui.ConsoleUiState
import com.example.ui.MentionSuggestion
import com.example.ui.PendingToolApproval
import com.example.ui.RunMode
import com.example.ui.TranscriptEntry
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    vm: BorerViewModel,
    onOpenDrawer: (() -> Unit)?,
    onOpenSessions: () -> Unit,
    onOpenIntel: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.console.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showDiffSheet by remember { mutableStateOf(false) }
    var sessionDiffs by remember { mutableStateOf<List<FileDiffItem>>(emptyList()) }
    var diffsLoading by remember { mutableStateOf(false) }
    var askedNotifPerm by remember { mutableStateOf(false) }
    var mentionSuggestions by remember { mutableStateOf<List<MentionSuggestion>>(emptyList()) }
    val listState = rememberLazyListState()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val exportMdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val md = vm.buildSessionMarkdownExport()
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(md.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    val activeMentionToken = remember(inputText) {
        Regex("(?:^|\\s)@([^\\s@]*)$").find(inputText)?.groupValues?.get(1)
    }
    LaunchedEffect(activeMentionToken) {
        if (activeMentionToken == null) {
            mentionSuggestions = emptyList()
        } else {
            mentionSuggestions = vm.getMentionSuggestions(activeMentionToken)
        }
    }

    LaunchedEffect(showDiffSheet, state.runId, state.changedFilesCount, state.checkpointCount) {
        if (showDiffSheet) {
            diffsLoading = true
            sessionDiffs = withContext(Dispatchers.IO) { vm.getSessionDiffs() }
            diffsLoading = false
        }
    }

    fun ensureNotifPermissionPrompt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.backgroundKeepAlive &&
            !askedNotifPerm &&
            !BackgroundKeepAliveController.hasNotificationPermission(context)
        ) {
            askedNotifPerm = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lastEntry = state.entries.lastOrNull()
    val lastContentSig = when (lastEntry) {
        is TranscriptEntry.Assistant -> lastEntry.text.length
        is TranscriptEntry.Reasoning -> lastEntry.text.length
        is TranscriptEntry.ToolExec -> if (lastEntry.running) -1 else lastEntry.preview.length + 1
        is TranscriptEntry.PlanProposal -> lastEntry.steps.size + if (lastEntry.approved) 1000 else 0
        is TranscriptEntry.Info -> lastEntry.text.length
        is TranscriptEntry.User -> lastEntry.text.length
        null -> 0
    }
    LaunchedEffect(state.entries.size, lastContentSig) {
        if (state.entries.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= state.entries.lastIndex - 2 || state.entries.size <= 3) {
                listState.scrollToItem(state.entries.lastIndex)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        MinimalChatTopBar(
            state = state,
            showDivider = state.entries.isNotEmpty(),
            onOpenDrawer = onOpenDrawer,
            onOpenModelSheet = { showDetailsSheet = true },
            onOpenDiffSheet = { showDiffSheet = true },
            onNewSession = { vm.startNewSession() }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (state.entries.isEmpty()) {
                ChatEmptyState(
                    activeProfile = state.agentProfile,
                    onSelectProfile = vm::setAgentProfile,
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    onPromptSuggestion = { inputText = it },
                    onOpenSessions = onOpenSessions,
                    onOpenIntel = onOpenIntel,
                    onOpenLab = onOpenLab
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                ) {
                    itemsIndexed(state.entries, key = { _, it -> it.id }) { idx, entry ->
                        when (entry) {
                            is TranscriptEntry.User -> UserBubble(entry)
                            is TranscriptEntry.Assistant -> AssistantProse(
                                entry = entry,
                                isStreaming = state.busy && state.isStreaming && idx == state.entries.lastIndex
                            )
                            is TranscriptEntry.Reasoning -> ThoughtRow(
                                entry = entry,
                                onToggle = { vm.toggleReasoningCollapsed(entry.id) }
                            )
                            is TranscriptEntry.ToolExec -> ToolActionRow(entry)
                            is TranscriptEntry.PlanProposal -> PlanProposalCard(
                                entry = entry,
                                busy = state.busy,
                                onApproveAuto = { vm.approvePlan(entry.id, RunMode.AUTONOMOUS) },
                                onApproveChat = { vm.approvePlan(entry.id, RunMode.CHAT) }
                            )
                            is TranscriptEntry.Info -> SubtleInfoLine(entry)
                        }
                    }
                }
            }
        }

        val activeTool = remember(state.entries) {
            state.entries.lastOrNull { it is TranscriptEntry.ToolExec && it.running } as? TranscriptEntry.ToolExec
        }

        state.pendingApproval?.let { approval ->
            ToolApprovalBanner(
                approval = approval,
                onDecision = vm::resolveToolApproval
            )
        }

        FloatingPillComposer(
            inputText = inputText,
            onInputChange = { inputText = it },
            busy = state.busy,
            stopRequested = state.stopRequested,
            turn = state.turn,
            activeTool = activeTool,
            subagentLabel = state.subagentActiveLabel,
            mode = state.mode,
            queuedCount = state.queuedPrompts.size,
            mentionSuggestions = mentionSuggestions,
            onSelectMention = { suggestion ->
                val idx = inputText.lastIndexOf('@')
                inputText = if (idx >= 0) {
                    inputText.substring(0, idx) + suggestion.token + " "
                } else {
                    "$inputText ${suggestion.token} "
                }
                mentionSuggestions = emptyList()
            },
            onSend = {
                if (inputText.isNotBlank()) {
                    if (!inputText.trimStart().startsWith("/")) {
                        ensureNotifPermissionPrompt()
                    }
                    vm.submitPrompt(inputText)
                    inputText = ""
                }
            },
            onSlashCommand = { cmd ->
                inputText = ""
                when (cmd) {
                    "/resume" -> onOpenSessions()
                    "/diff" -> showDiffSheet = true
                    "/models" -> onOpenSettings()
                    "/export" -> exportMdLauncher.launch("borer-${state.runId.ifBlank { "session" }}.md")
                    else -> vm.submitPrompt(cmd)
                }
            },
            onModeToggle = {
                vm.setRunMode(if (state.mode == RunMode.CHAT) RunMode.AUTONOMOUS else RunMode.CHAT)
            },
            onPlanMode = {
                vm.setRunMode(RunMode.PLAN)
            },
            onStop = vm::requestStop,
            onForceStop = vm::forceCancelTurn
        )
    }

    if (showDetailsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailsSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        ) {
            val runtimeLabel = when (settings.shellBackend) {
                ShellBackend.ANDROID_SH -> "/system/bin/sh"
                ShellBackend.TERMUX_PROOT -> "Termux proot-distro (${settings.prootDistro})"
            }
            SessionTelemetrySheet(
                state = state,
                runtimeLabel = runtimeLabel,
                backgroundKeepAlive = settings.backgroundKeepAlive,
                persistentWakeLock = settings.persistentWakeLock,
                onTogglePersistentWakeLock = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !BackgroundKeepAliveController.hasNotificationPermission(context)
                    ) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.setPersistentWakeLock(enabled)
                },
                onProfileChange = vm::setAgentProfile,
                onModeChange = vm::setRunMode,
                onOpenDiffs = {
                    showDetailsSheet = false
                    showDiffSheet = true
                },
                onCompactNow = {
                    showDetailsSheet = false
                    vm.compactContextNow()
                },
                onExportMarkdown = {
                    showDetailsSheet = false
                    exportMdLauncher.launch("borer-${state.runId.ifBlank { "session" }}.md")
                },
                onOpenIntel = {
                    showDetailsSheet = false
                    onOpenIntel()
                },
                onOpenLab = {
                    showDetailsSheet = false
                    onOpenLab()
                },
                onOpenSettings = {
                    showDetailsSheet = false
                    onOpenSettings()
                }
            )
        }
    }

    if (showDiffSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDiffSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        ) {
            WorkspaceDiffSheet(
                diffs = sessionDiffs,
                loading = diffsLoading,
                canUndo = !state.busy && state.checkpointCount > 0,
                checkpointTurn = state.checkpointCount,
                onUndo = { vm.undoLastTurn() }
            )
        }
    }
}

@Composable
private fun MinimalChatTopBar(
    state: ConsoleUiState,
    showDivider: Boolean,
    onOpenDrawer: (() -> Unit)?,
    onOpenModelSheet: () -> Unit,
    onOpenDiffSheet: () -> Unit,
    onNewSession: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
        ) {
            if (onOpenDrawer != null) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_drawer_button")
                ) {
                    ModernMenuIcon(
                        contentDescription = "Open menu",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }

            // Center clickable model/session pill + optional diff badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenModelSheet)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("model_selector_pill")
                ) {
                    if (state.busy) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        text = if (state.providerConfigured) state.activeModel else "Borer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (state.agentProfile != AgentProfile.GENERAL) {
                        Spacer(Modifier.width(7.dp))
                        CleanStatusBadge(text = state.agentProfile.label, emphasizedBorder = true)
                    }
                    when (state.mode) {
                        RunMode.AUTONOMOUS -> {
                            Spacer(Modifier.width(7.dp))
                            CleanStatusBadge(text = "Auto", inverted = true)
                        }
                        RunMode.PLAN -> {
                            Spacer(Modifier.width(7.dp))
                            CleanStatusBadge(text = "Plan", emphasizedBorder = true)
                        }
                        RunMode.CHAT -> Unit
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Session controls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (state.changedFilesCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    CleanStatusBadge(
                        text = "±${state.changedFilesCount}",
                        emphasizedBorder = true,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenDiffSheet)
                            .testTag("top_bar_diff_chip")
                    )
                }
            }

            IconButton(
                onClick = onNewSession,
                enabled = !state.busy,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("new_session_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "New chat",
                    tint = if (!state.busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyState(
    activeProfile: AgentProfile,
    onSelectProfile: (AgentProfile) -> Unit,
    modifier: Modifier = Modifier,
    onPromptSuggestion: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenIntel: () -> Unit,
    onOpenLab: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .testTag("empty_prompt_glyph")
        ) {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgentProfile.entries.forEach { profile ->
                CleanFilterPill(
                    label = profile.label,
                    selected = profile == activeProfile,
                    onClick = { onSelectProfile(profile) },
                    modifier = Modifier.testTag("profile_chip_${profile.wire}")
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        val suggestions: List<Pair<String, () -> Unit>> = when (activeProfile) {
            AgentProfile.GENERAL -> listOf(
                "Explain, write, or brainstorm an idea" to {
                    onPromptSuggestion("Help me brainstorm and structure: ")
                },
                "Search the web and summarize cleanly" to {
                    onPromptSuggestion("Search the web and give me a concise summary about: ")
                },
                "Build, edit, or run code in workspace" to {
                    onPromptSuggestion("Help me build and verify in workspace: ")
                },
                "Browse saved sessions & batch tasks" to onOpenSessions
            )
            AgentProfile.CODING -> listOf(
                "Plan changes in the workspace" to {
                    onPromptSuggestion("/plan Inspect the workspace and propose an implementation plan for: ")
                },
                "Debug, patch files, and run tests" to {
                    onPromptSuggestion("Inspect the codebase, apply a clean fix, and verify with shell commands: ")
                },
                "Open Workspace, Memory & Skills" to onOpenLab,
                "Resume a previous session" to onOpenSessions
            )
            AgentProfile.RESEARCH -> listOf(
                "Deep research across multiple web sources" to {
                    onPromptSuggestion("Search the web, read primary sources with web_fetch, and write a structured summary on: ")
                },
                "Fetch & extract a webpage or API" to {
                    onPromptSuggestion("Fetch and summarize the key points from URL: ")
                },
                "Review Notes & saved Reports" to onOpenIntel,
                "Resume a previous session" to onOpenSessions
            )
            AgentProfile.SECURITY -> listOf(
                "Plan an authorized assessment" to {
                    onPromptSuggestion("Help me plan an authorized security test for my app: ")
                },
                "Resume a previous session" to onOpenSessions,
                "Open Workspace & Skills" to onOpenLab,
                "Review Notes & Reports" to onOpenIntel
            )
        }
        suggestions.forEach { (label, action) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .clickable(onClick = action)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun UserBubble(entry: TranscriptEntry.User) {
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp
    )
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outline, bubbleShape)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            SelectionContainer {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun AssistantProse(
    entry: TranscriptEntry.Assistant,
    isStreaming: Boolean
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(18.dp)
                .padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarkdownProse(
                text = entry.text,
                selectable = !isStreaming,
                modifier = Modifier.fillMaxWidth()
            )
            if (!isStreaming && entry.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                CopyActionChip(
                    label = "borer reply",
                    textToCopy = entry.text
                )
            }
        }
    }
}

@Composable
internal fun ThoughtRow(entry: TranscriptEntry.Reasoning, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Thought · ${entry.text.length} chars",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (entry.collapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Toggle thought",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(
            visible = !entry.collapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolActionRow(entry: TranscriptEntry.ToolExec) {
    var expanded by remember { mutableStateOf(false) }
    val argSummary = remember(entry.name, entry.arguments) {
        ToolSummaryFormatter.summarize(entry.name, entry.arguments)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = if (expanded) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            when {
                entry.running -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                entry.ok -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Succeeded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                else -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (argSummary.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = argSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (!entry.running) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${entry.ms}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Inspect tool output",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ARGUMENTS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val textToCopy = remember(entry.arguments, entry.preview) {
                        buildString {
                            append(entry.arguments)
                            if (entry.preview.isNotBlank()) {
                                append("\n\n")
                                append(entry.preview)
                            }
                        }
                    }
                    CopyActionChip(
                        label = "tool trace",
                        textToCopy = textToCopy
                    )
                }
                SelectionContainer {
                    Text(
                        text = entry.arguments,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (entry.preview.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SelectionContainer {
                        Text(
                            text = entry.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubtleInfoLine(entry: TranscriptEntry.Info) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.isWarn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (entry.isWarn) {
                        Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun PlanProposalCard(
    entry: TranscriptEntry.PlanProposal,
    busy: Boolean,
    onApproveAuto: () -> Unit,
    onApproveChat: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("plan_proposal_card_${entry.id}")
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CleanStatusBadge(
                text = if (entry.approved) "PLAN APPROVED" else "PLAN PROPOSAL",
                inverted = entry.approved,
                emphasizedBorder = !entry.approved
            )
            Text(
                text = "${entry.steps.size} steps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (entry.summary.isNotBlank()) {
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.steps.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.steps.forEachIndexed { idx, step ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        if (!entry.approved && !busy) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CleanPrimaryButton(
                    onClick = onApproveAuto,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("approve_plan_auto")
                ) {
                    Text("Approve · Auto", maxLines = 1)
                }
                CleanOutlinedButton(
                    onClick = onApproveChat,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("approve_plan_chat")
                ) {
                    Text("Approve · Ask", maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolApprovalBanner(
    approval: PendingToolApproval,
    onDecision: (ApprovalDecision) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("tool_approval_banner")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = "Approval required",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Approve ${approval.toolName}?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            CleanStatusBadge(text = "SUPERVISED", emphasizedBorder = true)
        }
        Text(
            text = "${approval.riskReason} · ${approval.summary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (approval.argumentsPreview.isNotBlank()) {
            Text(
                text = approval.argumentsPreview,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CleanPrimaryButton(
                onClick = { onDecision(ApprovalDecision.AllowOnce) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("approval_allow_once")
            ) { Text("Allow Once") }

            CleanOutlinedButton(
                onClick = { onDecision(ApprovalDecision.AllowToolSession) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("approval_allow_tool")
            ) { Text("Always ${approval.toolName}") }

            CleanOutlinedButton(
                onClick = { onDecision(ApprovalDecision.AllowAllSession) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("approval_allow_all")
            ) { Text("Allow All") }

            CleanOutlinedButton(
                onClick = { onDecision(ApprovalDecision.Deny()) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("approval_deny")
            ) { Text("Deny") }
        }
    }
}

private val slashCommandItems = listOf(
    "/plan" to "Switch to Plan (read-only) mode",
    "/mode" to "Toggle Chat / Autonomous mode",
    "/persona" to "Cycle General / Code / Research / Security",
    "/compact" to "Compact context window now",
    "/diff" to "Inspect workspace file diffs",
    "/undo" to "Revert last turn file edits",
    "/export" to "Export session to Markdown",
    "/status" to "Runtime & checkpoint status",
    "/usage" to "Context & token usage",
    "/resume" to "Browse saved sessions",
    "/models" to "Configure providers & /models",
    "/mcp" to "Show or refresh connected MCP servers",
    "/help" to "Available commands",
    "/clear" to "Clear current thread"
)

@Composable
private fun FloatingPillComposer(
    inputText: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    stopRequested: Boolean,
    turn: Int,
    activeTool: TranscriptEntry.ToolExec?,
    subagentLabel: String?,
    mode: RunMode,
    queuedCount: Int,
    mentionSuggestions: List<MentionSuggestion>,
    onSelectMention: (MentionSuggestion) -> Unit,
    onSend: () -> Unit,
    onSlashCommand: (String) -> Unit,
    onModeToggle: () -> Unit,
    onPlanMode: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val composerBorder by animateColorAsState(
        targetValue = if (inputFocused) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline,
        animationSpec = tween(150),
        label = "composer_border"
    )

    val matchingSlashCommands = remember(inputText) {
        if (inputText.startsWith("/") && !inputText.contains(' ') && !inputText.contains('\n')) {
            slashCommandItems.filter { (cmd, _) -> cmd.startsWith(inputText, ignoreCase = true) }
        } else {
            emptyList()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        AnimatedVisibility(
            visible = matchingSlashCommands.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                matchingSlashCommands.forEachIndexed { index, (cmd, desc) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSlashCommand(cmd) }
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                            .testTag("autocomplete_${cmd.removePrefix("/")}")
                    ) {
                        Text(
                            text = cmd,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = matchingSlashCommands.isEmpty() && mentionSuggestions.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                mentionSuggestions.forEachIndexed { index, suggestion ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMention(suggestion) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = suggestion.token,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = suggestion.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (busy || queuedCount > 0 || !subagentLabel.isNullOrBlank()) {
            val activeToolSummary = activeTool?.let { tool ->
                val summary = ToolSummaryFormatter.summarize(tool.name, tool.arguments)
                if (summary.isNotBlank()) "${tool.name} $summary" else tool.name
            }
            val statusLabel = when {
                stopRequested -> "Stopping after turn · Tap to force stop"
                !subagentLabel.isNullOrBlank() -> subagentLabel
                activeToolSummary != null -> {
                    if (turn > 0) "Turn $turn · Running $activeToolSummary" else "Running $activeToolSummary"
                }
                turn > 0 -> "Turn $turn · Thinking..."
                else -> "Thinking..."
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .then(if (stopRequested) Modifier.clickable(onClick = onForceStop) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                if (busy || !subagentLabel.isNullOrBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stopRequested) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (queuedCount > 0) {
                    CleanStatusBadge(text = "$queuedCount queued")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, composerBorder, RoundedCornerShape(28.dp))
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("composer_commands_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Commands and mode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (mode == RunMode.AUTONOMOUS) "Switch to Chat mode"
                                else "Switch to Autonomous mode"
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onModeToggle()
                        },
                        modifier = Modifier.testTag("mode_toggle_chip")
                    )
                    DropdownMenuItem(
                        text = { Text("Switch to Plan mode (read-only)") },
                        onClick = {
                            menuExpanded = false
                            onPlanMode()
                        },
                        modifier = Modifier.testTag("plan_mode_chip")
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    slashCommandItems.forEach { (cmd, desc) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = cmd,
                                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily)
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onSlashCommand(cmd)
                            },
                            modifier = Modifier.testTag("slash_${cmd.removePrefix("/")}")
                        )
                    }
                }
            }

            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 10.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (mode == RunMode.PLAN) "Describe task to plan (@file, /)" else "Ask anything (@file, /)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { inputFocused = it.isFocused }
                        .testTag("console_prompt_input")
                )
            }

            Spacer(Modifier.width(4.dp))

            if (busy && inputText.isBlank()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { if (stopRequested) onForceStop() else onStop() }
                        .testTag("composer_stop_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                val canSend = inputText.isNotBlank()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend, onClick = onSend)
                        .testTag("console_send_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .border(
                                width = 1.dp,
                                color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTelemetrySheet(
    state: ConsoleUiState,
    runtimeLabel: String,
    backgroundKeepAlive: Boolean,
    persistentWakeLock: Boolean,
    onTogglePersistentWakeLock: (Boolean) -> Unit,
    onProfileChange: (AgentProfile) -> Unit,
    onModeChange: (RunMode) -> Unit,
    onOpenDiffs: () -> Unit,
    onCompactNow: () -> Unit,
    onExportMarkdown: () -> Unit,
    onOpenIntel: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctxFraction = if (state.contextWindow > 0) {
        (state.contextTokens.toFloat() / state.contextWindow.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Agent & Run Controls", style = MaterialTheme.typography.titleLarge)
            CleanStatusBadge(
                text = if (state.providerConfigured) "READY" else "NO PROVIDER",
                inverted = state.providerConfigured
            )
        }

        CleanSegmentedControl(
            options = AgentProfile.entries.map { p ->
                SegmentTab(p.label, "telemetry_profile_${p.wire}")
            },
            selectedIndex = AgentProfile.entries.indexOf(state.agentProfile).coerceAtLeast(0),
            onSelect = { idx ->
                AgentProfile.entries.getOrNull(idx)?.let(onProfileChange)
            }
        )

        CleanSegmentedControl(
            options = listOf(
                SegmentTab("Plan", "telemetry_mode_plan"),
                SegmentTab("Chat", "telemetry_mode_chat"),
                SegmentTab("Autonomous", "telemetry_mode_auto")
            ),
            selectedIndex = when (state.mode) {
                RunMode.PLAN -> 0
                RunMode.CHAT -> 1
                RunMode.AUTONOMOUS -> 2
            },
            onSelect = { idx ->
                onModeChange(
                    when (idx) {
                        0 -> RunMode.PLAN
                        1 -> RunMode.CHAT
                        else -> RunMode.AUTONOMOUS
                    }
                )
            }
        )

        GroupedListCard {
            KeyValueStatRow(label = "Model", value = state.activeModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Context",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.contextTokens} / ${state.contextWindow} (${state.contextSource})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                LinearProgressIndicator(
                    progress = { ctxFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "Tokens",
                value = "in=${state.totals.promptTokens} (cached=${state.totals.cachedTokens}) · out=${state.totals.completionTokens}"
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "Checkpoints",
                value = "${state.changedFilesCount} files changed · turn ${state.checkpointCount}"
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "AGENTS.md",
                value = if (state.agentsMdLoaded) "Loaded" else "Not configured"
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "Audit trail",
                value = "${state.requestsAllowed} req · ${state.hostsReached.size} hosts"
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "Shell runtime",
                value = runtimeLabel
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Background",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CleanStatusBadge(
                    text = when {
                        persistentWakeLock -> "WakeLock active (8h+)"
                        backgroundKeepAlive -> "Run guard active"
                        else -> "Off"
                    },
                    inverted = persistentWakeLock
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            KeyValueStatRow(
                label = "Memory",
                value = "${state.learningStats.experience} exp · ${state.learningStats.techniques} tech"
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupedSwitchRow(
                title = "Persistent WakeLock (8h+)",
                subtitle = "Keep CPU & Wi-Fi awake in background like Termux",
                checked = persistentWakeLock,
                onCheckedChange = onTogglePersistentWakeLock,
                testTag = "telemetry_wakelock_switch"
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CleanOutlinedButton(
                onClick = onOpenDiffs,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).testTag("telemetry_diffs_button")
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Diffs (${state.changedFilesCount})", maxLines = 1)
            }
            CleanOutlinedButton(
                onClick = onCompactNow,
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).testTag("telemetry_compact_button")
            ) {
                Text("Compact", maxLines = 1)
            }
            CleanOutlinedButton(
                onClick = onExportMarkdown,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).testTag("telemetry_export_button")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export", maxLines = 1)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CleanOutlinedButton(
                onClick = onOpenIntel,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Bookmarks, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Notes", maxLines = 1)
            }
            CleanOutlinedButton(
                onClick = onOpenLab,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Workspace", maxLines = 1)
            }
            CleanOutlinedButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).testTag("telemetry_settings_button")
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Settings", maxLines = 1)
            }
        }
    }
}

@Composable
private fun WorkspaceDiffSheet(
    diffs: List<FileDiffItem>,
    loading: Boolean,
    canUndo: Boolean,
    checkpointTurn: Int,
    onUndo: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 560.dp)
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Workspace Diffs", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (checkpointTurn > 0) "${diffs.size} modified file(s) · latest turn $checkpointTurn" else "No workspace edits tracked yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CleanOutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("diff_sheet_undo_button")
            ) {
                Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Undo Turn")
            }
        }

        when {
            loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            diffs.isEmpty() -> {
                HairlineCard {
                    Text(
                        text = "Workspace files match session baseline. Edits made with write, edit, or apply_patch appear here automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(diffs, key = { it.absolutePath }) { diff ->
                        HairlineCard {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = diff.relativePath,
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                CleanStatusBadge(
                                    text = "+${diff.addedLines} / -${diff.removedLines}",
                                    inverted = diff.addedLines > 0 && diff.removedLines == 0
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                diff.diffText.lines().forEach { line ->
                                    val color = when {
                                        line.startsWith("+") && !line.startsWith("+++") -> MaterialTheme.colorScheme.primary
                                        line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                                        line.startsWith("@@") -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                    }
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        color = color
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
