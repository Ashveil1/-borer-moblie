package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ArtifactEntity
import com.example.data.EventRecord
import com.example.data.FindingEntity
import com.example.data.HttpLogRecord
import com.example.data.NoteEntity
import com.example.data.NoteKind
import com.example.tools.Cvss31Calculator
import com.example.ui.BorerViewModel
import java.io.File

@Composable
fun IntelScreen(
    vm: BorerViewModel,
    onOpenDrawer: (() -> Unit)?
) {
    var tabIdx by remember { mutableIntStateOf(0) }
    val findings by vm.findings.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val httpLogs by vm.httpLogs.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val artifacts by vm.artifacts.collectAsStateWithLifecycle()
    val consoleState by vm.console.collectAsStateWithLifecycle()

    var showNewFindingDialog by remember { mutableStateOf(false) }
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var selectedFindingReport by remember { mutableStateOf<Pair<FindingEntity, String>?>(null) }
    var selectedArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            if (tabIdx == 0 || tabIdx == 1) {
                FloatingActionButton(
                    onClick = {
                        if (tabIdx == 0) showNewFindingDialog = true
                        else showNewNoteDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("intel_add_fab")
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = if (tabIdx == 0) "Add finding" else "Add note"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CleanScreenTopBar(
                title = "Intel & Reports",
                onOpenDrawer = onOpenDrawer
            )

            CleanSegmentBarRich(
                tabs = listOf(
                    SegmentTab("Findings", "tab_intel_findings", count = findings.size),
                    SegmentTab("Notes", "tab_intel_notes", count = notes.size),
                    SegmentTab("Audit", "tab_intel_audit", count = httpLogs.size),
                    SegmentTab("Artifacts", "tab_intel_artifacts", count = artifacts.size)
                ),
                selectedIndex = tabIdx,
                onSelect = { tabIdx = it },
                modifier = Modifier.widthIn(max = 760.dp)
            )

            when (tabIdx) {
                0 -> FindingsPane(
                    findings = findings,
                    onOpenReport = { f ->
                        val text = f.reportPath?.let { p -> runCatching { File(p).readText() }.getOrNull() }
                            ?: "# ${f.title}\n\nSeverity: ${f.severity}\nAsset: ${f.asset}\nCVSS: ${f.cvss ?: "n/a"}"
                        selectedFindingReport = f to text
                    },
                    onAddFinding = { showNewFindingDialog = true }
                )
                1 -> NotesAndTodoPane(
                    notes = notes,
                    onAddTodo = vm::addTodo,
                    onToggleTodo = vm::toggleTodo,
                    onClearTodos = vm::clearTodos,
                    onDeleteNote = vm::deleteNote
                )
                2 -> AuditTrailPane(
                    hostsReached = consoleState.hostsReached,
                    requestsAllowed = consoleState.requestsAllowed,
                    httpLogs = httpLogs,
                    events = events
                )
                else -> ArtifactsPane(
                    artifacts = artifacts,
                    onOpen = { selectedArtifact = it }
                )
            }
        }
    }

    if (showNewFindingDialog) {
        NewFindingDialog(
            onDismiss = { showNewFindingDialog = false },
            onSave = { title, asset, vector, summary, impact, remediation ->
                vm.createFindingReport(title, asset, vector, summary, impact, remediation)
                showNewFindingDialog = false
            }
        )
    }

    if (showNewNoteDialog) {
        NewNoteDialog(
            onDismiss = { showNewNoteDialog = false },
            onSave = { kind, key, body, tags ->
                vm.saveNote(kind, key, body, tags)
                showNewNoteDialog = false
            }
        )
    }

    selectedFindingReport?.let { (finding, markdown) ->
        AlertDialog(
            onDismissRequest = { selectedFindingReport = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(finding.title) },
            text = {
                Text(
                    text = markdown,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedFindingReport = null }) { Text("Close") }
            }
        )
    }

    selectedArtifact?.let { artifact ->
        ArtifactRecallDialog(
            artifact = artifact,
            readContent = { vm.readArtifact(artifact.id) },
            grepContent = { pattern -> vm.grepArtifact(artifact.id, pattern) },
            onDismiss = { selectedArtifact = null }
        )
    }
}

@Composable
private fun FindingsPane(
    findings: List<FindingEntity>,
    onOpenReport: (FindingEntity) -> Unit,
    onAddFinding: () -> Unit
) {
    if (findings.isEmpty()) {
        CleanEmptyState(
            icon = Icons.Outlined.Description,
            title = "No findings in this run",
            subtitle = "Confirmed issues with CVSS 3.1 scores and Markdown reports appear here.",
            actionLabel = "Add Finding",
            onAction = onAddFinding
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        items(findings, key = { it.id }) { f ->
            val sevColor = when (f.severity.lowercase()) {
                "critical", "high" -> MaterialTheme.colorScheme.error
                "medium" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.secondary
            }
            HairlineCard(
                onClick = { onOpenReport(f) },
                modifier = Modifier.testTag("finding_card_${f.id}")
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = sevColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${f.severity.uppercase()}${f.cvss?.let { " · CVSS ${"%.1f".format(it)}" } ?: ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = sevColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(f.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Text(f.title, style = MaterialTheme.typography.titleMedium)
                if (f.asset.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = f.asset,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesAndTodoPane(
    notes: List<NoteEntity>,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (Int) -> Unit,
    onClearTodos: () -> Unit,
    onDeleteNote: (Long) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf<NoteKind?>(null) }
    var newTodoText by remember { mutableStateOf("") }

    val todoNote = notes.firstOrNull { it.kind == NoteKind.TODO.wire }
    val todos = remember(todoNote?.body) { BorerViewModel.parseTodos(todoNote?.body) }
    val filteredNotes = remember(notes, searchQuery, selectedKind) {
        notes.filter { n ->
            n.kind != NoteKind.TODO.wire &&
                (selectedKind == null || n.kind == selectedKind?.wire) &&
                (searchQuery.isBlank() ||
                    n.key.contains(searchQuery, ignoreCase = true) ||
                    n.body.contains(searchQuery, ignoreCase = true))
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Todo Checklist", style = MaterialTheme.typography.titleMedium)
                        if (todos.isNotEmpty()) {
                            TextButton(onClick = onClearTodos) { Text("Clear") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTodoText,
                            onValueChange = { newTodoText = it },
                            placeholder = { Text("Add hunting task...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("todo_input")
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newTodoText.isNotBlank()) {
                                    onAddTodo(newTodoText)
                                    newTodoText = ""
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("todo_add_button")
                        ) {
                            Text("Add")
                        }
                    }
                    todos.forEachIndexed { idx, (done, text) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onToggleTodo(idx) }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = "Toggle todo",
                                tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search notes") },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                placeholder = { Text("Search notes...") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("notes_search_input")
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = selectedKind == null,
                    onClick = { selectedKind = null },
                    label = { Text("all") }
                )
                NoteKind.entries.filter { it != NoteKind.TODO }.forEach { kind ->
                    FilterChip(
                        selected = selectedKind == kind,
                        onClick = { selectedKind = if (selectedKind == kind) null else kind },
                        label = { Text(kind.wire) }
                    )
                }
            }
        }

        if (filteredNotes.isEmpty()) {
            item {
                HairlineCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.NoteAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedKind != null) "No notes match the current filter."
                            else "No notes saved yet. Tap + to record an endpoint, credential, or lead.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredNotes, key = { it.id }) { note ->
                HairlineCard {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "#${note.id} · ${note.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteNote(note.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete note", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(note.key, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditTrailPane(
    hostsReached: Map<String, Int>,
    requestsAllowed: Int,
    httpLogs: List<HttpLogRecord>,
    events: List<EventRecord>
) {
    var showEvents by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            GroupedListCard {
                KeyValueStatRow(
                    label = "Mode",
                    value = "Open · $requestsAllowed req logged",
                    valueColor = MaterialTheme.colorScheme.secondary
                )
                if (hostsReached.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Hosts Reached", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hostsReached.entries.joinToString(" · ") { "${it.key} (${it.value})" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    FilterChip(selected = !showEvents, onClick = { showEvents = false }, label = { Text("http.jsonl (${httpLogs.size})") })
                    FilterChip(selected = showEvents, onClick = { showEvents = true }, label = { Text("events.jsonl (${events.size})") })
                }
            }
        }

        if (!showEvents) {
            if (httpLogs.isEmpty()) {
                item {
                    HairlineCard {
                        Text(
                            text = "No HTTP traffic logged in http.jsonl yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(httpLogs.reversed()) { req ->
                    val statusColor = when {
                        req.status in 200..299 -> MaterialTheme.colorScheme.secondary
                        req.status in 300..399 -> MaterialTheme.colorScheme.primary
                        req.status >= 400 || req.status == 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    HairlineCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                color = statusColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (req.status > 0) "${req.status}" else "ERR",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${req.method} ${req.url}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${req.bytes}b · ${req.ms}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (events.isEmpty()) {
                item {
                    HairlineCard {
                        Text(
                            text = "No harness events recorded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(events.reversed(), key = { it.seq }) { ev ->
                    HairlineCard {
                        Text("#${ev.seq} ${ev.kind}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (ev.data.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                ev.data.entries.joinToString(" · ") { "${it.key}=${it.value.take(80)}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactsPane(
    artifacts: List<ArtifactEntity>,
    onOpen: (ArtifactEntity) -> Unit
) {
    if (artifacts.isEmpty()) {
        CleanEmptyState(
            icon = Icons.Outlined.FolderOpen,
            title = "No spilled artifacts yet",
            subtitle = "Tool outputs exceeding the inline budget are written to disk and listed here for full recall or grep."
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        items(artifacts, key = { it.id }) { art ->
            HairlineCard(onClick = { onOpen(art) }) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(art.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("${art.bytes}b · ${art.lines} lines · ${art.kind}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(art.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NewFindingDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, asset: String, vector: String, summary: String, impact: String, remediation: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var asset by remember { mutableStateOf("") }
    var vector by remember { mutableStateOf("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N") }
    var summary by remember { mutableStateOf("") }
    var impact by remember { mutableStateOf("") }
    var remediation by remember { mutableStateOf("") }
    val preview = remember(vector) { Cvss31Calculator.score(vector.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New Finding + CVSS 3.1") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = asset, onValueChange = { asset = it }, label = { Text("Asset / Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = vector, onValueChange = { vector = it }, label = { Text("CVSS 3.1 Vector") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    text = preview?.let { "Calculated: ${"%.1f".format(it.base)} (${it.severity.uppercase()})" } ?: "Invalid CVSS 3.1 vector",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preview != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("Summary") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = impact, onValueChange = { impact = it }, label = { Text("Impact") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = remediation, onValueChange = { remediation = it }, label = { Text("Remediation") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, asset, vector, summary, impact, remediation) },
                enabled = title.isNotBlank()
            ) { Text("Save Report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewNoteDialog(
    onDismiss: () -> Unit,
    onSave: (kind: NoteKind, key: String, body: String, tags: List<String>) -> Unit
) {
    var kind by remember { mutableStateOf(NoteKind.FACT) }
    var key by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    NoteKind.entries.filter { it != NoteKind.TODO }.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.wire) })
                    }
                }
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key (e.g. /api/users?id)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tagList = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    onSave(kind, key, body, tagList)
                },
                enabled = key.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ArtifactRecallDialog(
    artifact: ArtifactEntity,
    readContent: () -> String,
    grepContent: (String) -> List<String>,
    onDismiss: () -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    val content = remember(artifact.id, pattern) {
        if (pattern.isBlank()) readContent().take(12_000)
        else grepContent(pattern).joinToString("\n\n").ifBlank { "No matches for /$pattern/" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Recall ${artifact.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Regex grep (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .height(280.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
