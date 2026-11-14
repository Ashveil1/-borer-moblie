package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SessionEntity
import com.example.data.SessionFiles
import com.example.ui.BorerViewModel
import com.example.ui.HuntItemStatus
import com.example.ui.HuntQueueItem
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun SessionsScreen(
    vm: BorerViewModel,
    onOpenDrawer: (() -> Unit)?,
    onResumedOrStarted: () -> Unit
) {
    var tabIdx by remember { mutableIntStateOf(0) }
    val activeSessions by vm.activeSessions.collectAsStateWithLifecycle()
    val archivedSessions by vm.archivedSessions.collectAsStateWithLifecycle()
    val activeRunId by vm.activeRunId.collectAsStateWithLifecycle()
    val huntItems by vm.huntItems.collectAsStateWithLifecycle()
    val huntRunning by vm.huntRunning.collectAsStateWithLifecycle()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        CleanScreenTopBar(
            title = "Sessions & Hunt",
            onOpenDrawer = onOpenDrawer
        )

        CleanSegmentBarRich(
            tabs = listOf(
                SegmentTab("Active", "tab_sessions_active", count = activeSessions.size),
                SegmentTab("Archived", "tab_sessions_archived", count = archivedSessions.size),
                SegmentTab("Hunt", "tab_hunt_queue", count = huntItems.size.takeIf { it > 0 })
            ),
            selectedIndex = tabIdx,
            onSelect = { tabIdx = it },
            modifier = Modifier.widthIn(max = BorerTokens.ScreenMaxWidth)
        )

        when (tabIdx) {
            0 -> SessionListPane(
                sessions = activeSessions,
                activeRunId = activeRunId,
                isArchived = false,
                onResume = { vm.resumeSession(it); onResumedOrStarted() },
                onFork = { vm.forkSession(it); onResumedOrStarted() },
                onToggleArchive = { vm.archiveSession(it.id, true) },
                onDelete = { vm.deleteSession(it.id) }
            )
            1 -> SessionListPane(
                sessions = archivedSessions,
                activeRunId = activeRunId,
                isArchived = true,
                onResume = { vm.resumeSession(it); onResumedOrStarted() },
                onFork = { vm.forkSession(it); onResumedOrStarted() },
                onToggleArchive = { vm.archiveSession(it.id, false) },
                onDelete = { vm.deleteSession(it.id) }
            )
            else -> HuntQueuePane(
                items = huntItems,
                running = huntRunning,
                onStart = vm::startHuntQueue,
                onStop = vm::stopHuntQueue
            )
        }
    }
}

@Composable
private fun SessionListPane(
    sessions: List<SessionEntity>,
    activeRunId: String,
    isArchived: Boolean,
    onResume: (SessionEntity) -> Unit,
    onFork: (SessionEntity) -> Unit,
    onToggleArchive: (SessionEntity) -> Unit,
    onDelete: (SessionEntity) -> Unit
) {
    if (sessions.isEmpty()) {
        CleanEmptyState(
            icon = if (isArchived) Icons.Outlined.Archive else Icons.Outlined.History,
            title = if (isArchived) "No archived sessions" else "No active sessions yet",
            subtitle = if (isArchived) {
                "Archived runs will appear here for later review or forking."
            } else {
                "Start a conversation in Borer Chat to create and save your first run."
            }
        )
        return
    }

    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        items(sessions, key = { it.id }) { session ->
            val isCurrent = session.id == activeRunId
            HairlineCard(
                highlighted = isCurrent,
                modifier = Modifier.testTag("session_card_${session.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isCurrent) {
                        CleanStatusBadge(
                            text = "ACTIVE",
                            inverted = true
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${SessionFiles.ageOf(session.updatedAt)} · ${session.turns}t · ${session.model} · ${session.requestsAllowed} req",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!session.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = session.summary.lines().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isCurrent) {
                        CleanOutlinedButton(
                            onClick = { onResume(session) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("resume_${session.id}")
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume session", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                    } else {
                        CleanStatusBadge(text = "Open in console")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onFork(session) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("fork_${session.id}")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.CallSplit,
                            contentDescription = "Fork session",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { onToggleArchive(session) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("archive_${session.id}")
                    ) {
                        Icon(
                            imageVector = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = if (isArchived) "Unarchive session" else "Archive session",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { pendingDelete = session },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("delete_${session.id}")
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete session",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = { Text("Delete Session?") },
            text = { Text("Permanently remove \"${target.title}\" and all its saved notes, findings, and artifacts?") },
            confirmButton = {
                CleanPrimaryButton(
                    onClick = { onDelete(target); pendingDelete = null }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HuntQueuePane(
    items: List<HuntQueueItem>,
    running: Boolean,
    onStart: (targetsRaw: String, templateRaw: String) -> Unit,
    onStop: () -> Unit
) {
    var targetsText by remember { mutableStateOf("http://127.0.0.1:8080\n") }
    var templateText by remember {
        mutableStateOf("Hunt for vulnerabilities in {target}. Map the surface, test parameters, verify every suspected issue, write a report, and finish.")
    }

    val doneCount = remember(items) { items.count { it.status == HuntItemStatus.DONE } }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        item {
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unattended Hunt Queue", style = MaterialTheme.typography.titleMedium)
                        if (running) {
                            CleanStatusBadge(text = "RUNNING", inverted = true)
                        }
                    }
                    Text(
                        "One isolated autonomous run per target line, executed sequentially.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CleanOutlinedTextField(
                        value = targetsText,
                        onValueChange = { targetsText = it },
                        label = "Targets (one per line)",
                        minLines = 3,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.fillMaxWidth().testTag("hunt_targets_input")
                    )
                    CleanOutlinedTextField(
                        value = templateText,
                        onValueChange = { templateText = it },
                        label = "Task template ({target})",
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("hunt_template_input")
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!running) {
                            CleanPrimaryButton(
                                onClick = { onStart(targetsText, templateText) },
                                modifier = Modifier.testTag("start_hunt_queue_button")
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = "Start queue", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Start Hunt Queue")
                            }
                        } else {
                            CleanOutlinedButton(
                                onClick = onStop,
                                modifier = Modifier.testTag("stop_hunt_queue_button")
                            ) {
                                Icon(Icons.Outlined.Stop, contentDescription = "Stop queue", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop Queue")
                            }
                        }
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Queue Status",
                    trailing = "$doneCount/${items.size} complete"
                )
            }
        }

        items(items) { item ->
            HairlineCard(highlighted = item.status == HuntItemStatus.RUNNING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.target,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        item.message?.let {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    CleanStatusBadge(
                        text = "${item.status.name} · ${item.findingsCount}",
                        inverted = item.status == HuntItemStatus.DONE || item.status == HuntItemStatus.RUNNING,
                        emphasizedBorder = item.status == HuntItemStatus.FAILED
                    )
                }
            }
        }
    }
}
