package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ShellBackend
import com.example.ui.BorerViewModel
import com.example.ui.ConsoleUiState
import com.example.ui.RunMode
import com.example.ui.TranscriptEntry
import kotlinx.coroutines.delay

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
    var inputText by remember { mutableStateOf("") }
    var showDetailsSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val lastTextLen = (state.entries.lastOrNull() as? TranscriptEntry.Assistant)?.text?.length ?: 0
    LaunchedEffect(state.entries.size, lastTextLen) {
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
            onOpenDrawer = onOpenDrawer,
            onOpenModelSheet = { showDetailsSheet = true },
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
                    state = state,
                    onQuickPrompt = { prompt -> inputText = prompt },
                    onOpenSessions = onOpenSessions,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
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
                                isStreaming = state.busy && idx == state.entries.lastIndex
                            )
                            is TranscriptEntry.Reasoning -> ThoughtRow(
                                entry = entry,
                                onToggle = { vm.toggleReasoningCollapsed(entry.id) }
                            )
                            is TranscriptEntry.ToolExec -> ToolActionRow(entry)
                            is TranscriptEntry.Info -> SubtleInfoLine(entry)
                        }
                    }
                }
            }
        }

        FloatingPillComposer(
            inputText = inputText,
            onInputChange = { inputText = it },
            busy = state.busy,
            stopRequested = state.stopRequested,
            turn = state.turn,
            mode = state.mode,
            queuedCount = state.queuedPrompts.size,
            onSend = {
                if (inputText.isNotBlank()) {
                    vm.submitPrompt(inputText)
                    inputText = ""
                }
            },
            onSlashCommand = { cmd ->
                if (cmd == "/resume") onOpenSessions()
                else vm.submitPrompt(cmd)
            },
            onModeToggle = {
                vm.setRunMode(if (state.mode == RunMode.CHAT) RunMode.AUTONOMOUS else RunMode.CHAT)
            },
            onStop = vm::requestStop,
            onForceStop = vm::forceCancelTurn
        )
    }

    if (showDetailsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            val runtimeLabel = when (settings.shellBackend) {
                ShellBackend.ANDROID_SH -> "/system/bin/sh"
                ShellBackend.TERMUX_PROOT -> "Termux proot-distro (${settings.prootDistro})"
                ShellBackend.PROOT_DIRECT -> "Direct proot (${settings.prootDistro})"
            }
            SessionTelemetrySheet(
                state = state,
                runtimeLabel = runtimeLabel,
                onModeChange = vm::setRunMode,
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
}

@Composable
private fun MinimalChatTopBar(
    state: ConsoleUiState,
    onOpenDrawer: (() -> Unit)?,
    onOpenModelSheet: () -> Unit,
    onNewSession: () -> Unit
) {
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
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open menu",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }

        // Center clickable pill — ChatGPT model selector style
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenModelSheet)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("model_selector_pill")
            ) {
                if (state.busy) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
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
                if (state.mode == RunMode.AUTONOMOUS) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Auto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Session controls",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
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
                imageVector = Icons.Outlined.EditNote,
                contentDescription = "New chat",
                tint = if (!state.busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun ChatEmptyState(
    state: ConsoleUiState,
    onQuickPrompt: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_borer_foreground),
                    contentDescription = "Borer emblem",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "What are we testing?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            if (!state.providerConfigured) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .testTag("empty_configure_provider_pill")
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "No provider configured · Tap to setup",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(22.dp))
            } else {
                Spacer(Modifier.height(18.dp))
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StarterRow(
                    icon = Icons.Outlined.Explore,
                    title = "Map attack surface",
                    subtitle = "Probe endpoints and parameters on a target host",
                    onClick = { onQuickPrompt("Map endpoints and parameters on http://127.0.0.1:8080 and record findings in notes.") },
                    testTag = "quick_probe_chip"
                )
                StarterRow(
                    icon = Icons.Outlined.VerifiedUser,
                    title = "Verify IDOR & score CVSS",
                    subtitle = "Test auth boundaries, verify 3x, draft a report",
                    onClick = { onQuickPrompt("Test authentication and IDOR on the target API, verify PoC 3x, and write a CVSS 3.1 report.") },
                    testTag = "quick_idor_chip"
                )
                StarterRow(
                    icon = Icons.Outlined.History,
                    title = "Resume or hunt queue",
                    subtitle = "Continue a previous session or run queued targets",
                    onClick = onOpenSessions,
                    testTag = "quick_resume_chip"
                )
            }
        }
    }
}

@Composable
private fun StarterRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun UserBubble(entry: TranscriptEntry.User) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
private fun AssistantProse(
    entry: TranscriptEntry.Assistant,
    isStreaming: Boolean
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_borer_foreground),
                contentDescription = "Borer",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (!isStreaming && entry.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("borer reply", entry.text))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy response",
                        tint = if (copied) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ThoughtRow(entry: TranscriptEntry.Reasoning, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Text(
                text = "Thought · ${entry.text.length} chars",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (entry.collapsed) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Toggle thought",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = !entry.collapsed) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToolActionRow(entry: TranscriptEntry.ToolExec) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            when {
                entry.running -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.ok -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Succeeded",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
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
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = "Inspect tool output",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
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
                Text(
                    text = entry.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.preview.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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

@Composable
private fun SubtleInfoLine(entry: TranscriptEntry.Info) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.isWarn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (entry.isWarn) Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FloatingPillComposer(
    inputText: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    stopRequested: Boolean,
    turn: Int,
    mode: RunMode,
    queuedCount: Int,
    onSend: () -> Unit,
    onSlashCommand: (String) -> Unit,
    onModeToggle: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (busy || queuedCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (stopRequested) Modifier.clickable(onClick = onForceStop) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.6.dp,
                        color = if (stopRequested) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = if (stopRequested) {
                            "Stopping after turn · Tap to force stop"
                        } else {
                            if (turn > 0) "Turn $turn · Running" else "Thinking..."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stopRequested) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (queuedCount > 0) {
                    Text(
                        text = "$queuedCount queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("composer_commands_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    listOf(
                        "/usage" to "Context & token usage",
                        "/resume" to "Browse saved sessions",
                        "/help" to "Available commands",
                        "/clear" to "Clear current thread"
                    ).forEach { (cmd, desc) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(cmd, style = MaterialTheme.typography.labelLarge)
                                    Text(desc, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (busy) "Queue next instruction..." else "Message Borer...",
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
                        .testTag("console_prompt_input")
                )
            }

            Spacer(Modifier.width(6.dp))

            if (busy && inputText.isBlank()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onStop)
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
    onModeChange: (RunMode) -> Unit,
    onOpenIntel: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctxFraction = if (state.contextWindow > 0) {
        (state.contextTokens.toFloat() / state.contextWindow.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Text("Run Controls", style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.mode == RunMode.CHAT,
                onClick = { onModeChange(RunMode.CHAT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text("Chat")
            }
            SegmentedButton(
                selected = state.mode == RunMode.AUTONOMOUS,
                onClick = { onModeChange(RunMode.AUTONOMOUS) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text("Autonomous")
            }
        }

        GroupedListCard {
            KeyValueStatRow(label = "Model", value = state.activeModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
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
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (ctxFraction > 0.78f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
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
            KeyValueStatRow(
                label = "Memory",
                value = "${state.learningStats.experience} exp · ${state.learningStats.techniques} tech"
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onOpenIntel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Intel", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenLab,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Outlined.Biotech, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Lab", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("telemetry_settings_button")
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Settings", maxLines = 1)
            }
        }
    }
}
