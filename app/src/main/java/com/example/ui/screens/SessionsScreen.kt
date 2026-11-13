package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
            modifier = Modifier.widthIn(max = 760.dp)
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
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
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
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
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

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isCurrent) {
                        OutlinedButton(
                            onClick = { onResume(session) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("resume_${session.id}")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume session", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                    } else {
                        Text(
                            text = "Open in console",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onFork(session) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("fork_${session.id}")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = "Fork session", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { onToggleArchive(session) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("archive_${session.id}")
                    ) {
                        Icon(
                            imageVector = if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                            contentDescription = if (isArchived) "Unarchive session" else "Archive session",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { pendingDelete = session },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("delete_${session.id}")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete session", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete Session?") },
            text = { Text("Permanently remove \"${target.title}\" and all its saved notes, findings, and artifacts?") },
            confirmButton = {
                Button(
                    onClick = { onDelete(target); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Unattended Hunt Queue", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "One isolated autonomous run per target line, executed sequentially.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetsText,
                        onValueChange = { targetsText = it },
                        label = { Text("Targets (one per line)") },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("hunt_targets_input")
                    )
                    OutlinedTextField(
                        value = templateText,
                        onValueChange = { templateText = it },
                        label = { Text("Task template ({target})") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("hunt_template_input")
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!running) {
                            Button(
                                onClick = { onStart(targetsText, templateText) },
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("start_hunt_queue_button")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Start queue")
                                Spacer(Modifier.width(6.dp))
                                Text("Start Hunt Queue")
                            }
                        } else {
                            Button(
                                onClick = onStop,
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("stop_hunt_queue_button")
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop queue")
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
                SectionHeader(title = "Queue Status", trailing = "${items.size} targets")
            }
        }

        items(items) { item ->
            HairlineCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.target,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        item.message?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Surface(
                        color = when (item.status) {
                            HuntItemStatus.DONE -> MaterialTheme.colorScheme.secondaryContainer
                            HuntItemStatus.RUNNING -> MaterialTheme.colorScheme.surfaceContainerHigh
                            HuntItemStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                            HuntItemStatus.PENDING -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${item.status.name} · ${item.findingsCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (item.status) {
                                HuntItemStatus.DONE -> MaterialTheme.colorScheme.secondary
                                HuntItemStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
