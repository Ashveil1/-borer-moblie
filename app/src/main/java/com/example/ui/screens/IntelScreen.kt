package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var selectedFinding by remember { mutableStateOf<FindingEntity?>(null) }
    var selectedFindingReport by remember { mutableStateOf<Pair<FindingEntity, String>?>(null) }
    var selectedArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }

    LaunchedEffect(selectedFinding) {
        val f = selectedFinding
        if (f == null) {
            selectedFindingReport = null
        } else {
            val text = withContext(Dispatchers.IO) {
                f.reportPath?.let { p -> runCatching { File(p).readText() }.getOrNull() }
                    ?: "# ${f.title}\n\nSeverity: ${f.severity}\nAsset: ${f.asset}\nCVSS: ${f.cvss ?: "n/a"}"
            }
            selectedFindingReport = f to text
        }
    }

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
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    modifier = Modifier.testTag("intel_add_fab")
                ) {
                    Icon(
                        Icons.Outlined.Add,
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
                .imePadding()
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
                modifier = Modifier.widthIn(max = BorerTokens.ScreenMaxWidth)
            )

            when (tabIdx) {
                0 -> FindingsPane(
                    findings = findings,
                    onOpenReport = { f -> selectedFinding = f },
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
            onDismissRequest = { selectedFinding = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            title = {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = finding.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    CopyActionChip(label = "finding report", textToCopy = markdown)
                }
            },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownProse(text = markdown)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFinding = null }) { Text("Close") }
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
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        items(findings, key = { it.id }) { f ->
            val sevLower = f.severity.lowercase()
            val isHigh = sevLower == "critical" || sevLower == "high"
            val isMed = sevLower == "medium"
            HairlineCard(
                highlighted = isHigh,
                onClick = { onOpenReport(f) },
                modifier = Modifier.testTag("finding_card_${f.id}")
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CleanStatusBadge(
                        text = "${f.severity.uppercase()}${f.cvss?.let { " · CVSS ${"%.1f".format(it)}" } ?: ""}",
                        inverted = isHigh,
                        emphasizedBorder = isMed
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = f.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open finding report",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
    val doneTodos = remember(todos) { todos.count { it.first } }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Run Todo Checklist", style = MaterialTheme.typography.titleMedium)
                            if (todos.isNotEmpty()) {
                                CleanStatusBadge(text = "$doneTodos/${todos.size}")
                            }
                        }
                        if (todos.isNotEmpty()) {
                            TextButton(onClick = onClearTodos) { Text("Clear") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CleanOutlinedTextField(
                            value = newTodoText,
                            onValueChange = { newTodoText = it },
                            placeholder = "Add hunting task...",
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("todo_input")
                        )
                        Spacer(Modifier.width(8.dp))
                        CleanPrimaryButton(
                            onClick = {
                                if (newTodoText.isNotBlank()) {
                                    onAddTodo(newTodoText)
                                    newTodoText = ""
                                }
                            },
                            enabled = newTodoText.isNotBlank(),
                            modifier = Modifier.testTag("todo_add_button")
                        ) {
                            Text("Add")
                        }
                    }
                    if (todos.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    todos.forEachIndexed { idx, (done, text) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onToggleTodo(idx) }
                                .padding(vertical = 7.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = "Toggle todo",
                                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                                ),
                                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        item {
            CleanOutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search notes") },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                placeholder = "Search notes...",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("notes_search_input")
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                CleanFilterPill(
                    label = "all",
                    selected = selectedKind == null,
                    onClick = { selectedKind = null }
                )
                NoteKind.entries.filter { it != NoteKind.TODO }.forEach { kind ->
                    CleanFilterPill(
                        label = kind.wire,
                        selected = selectedKind == kind,
                        onClick = { selectedKind = if (selectedKind == kind) null else kind }
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
                        CleanStatusBadge(text = "#${note.id} · ${note.kind}")
                        IconButton(
                            onClick = { onDeleteNote(note.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete note",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
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
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        item {
            GroupedListCard {
                KeyValueStatRow(
                    label = "Mode",
                    value = "Open · $requestsAllowed req logged",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                if (hostsReached.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            "Hosts Reached",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hostsReached.entries.joinToString(" · ") { "${it.key} (${it.value})" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Box(modifier = Modifier.padding(12.dp)) {
                    CleanSegmentedControl(
                        options = listOf(
                            SegmentTab("http.jsonl", "audit_tab_http", count = httpLogs.size),
                            SegmentTab("events.jsonl", "audit_tab_events", count = events.size)
                        ),
                        selectedIndex = if (!showEvents) 0 else 1,
                        onSelect = { showEvents = it == 1 }
                    )
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
                    val is2xx = req.status in 200..299
                    val isErr = req.status >= 400 || req.status == 0
                    HairlineCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CleanStatusBadge(
                                text = if (req.status > 0) "${req.status}" else "ERR",
                                inverted = is2xx,
                                emphasizedBorder = isErr
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${req.method} ${req.url}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${req.bytes}b · ${req.ms}ms",
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
                        Text(
                            text = "#${ev.seq} ${ev.kind}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
        modifier = Modifier
            .widthIn(max = BorerTokens.ScreenMaxWidth)
            .fillMaxSize()
    ) {
        items(artifacts, key = { it.id }) { art ->
            HairlineCard(onClick = { onOpen(art) }) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CleanStatusBadge(text = art.id)
                    Text(
                        text = "${art.bytes}b · ${art.lines} lines · ${art.kind}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = art.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = { Text("New Finding + CVSS 3.1") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                CleanOutlinedTextField(value = title, onValueChange = { title = it }, label = "Title", singleLine = true, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(value = asset, onValueChange = { asset = it }, label = "Asset / Endpoint", singleLine = true, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(
                    value = vector,
                    onValueChange = { vector = it },
                    label = "CVSS 3.1 Vector",
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                CleanStatusBadge(
                    text = when {
                        preview != null -> "CVSS ${"%.1f".format(preview.base)} (${preview.severity.uppercase()})"
                        vector.isBlank() -> "Optional CVSS 3.1 vector"
                        else -> "Invalid CVSS 3.1 vector"
                    },
                    inverted = preview != null
                )
                CleanOutlinedTextField(value = summary, onValueChange = { summary = it }, label = "Summary", minLines = 2, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(value = impact, onValueChange = { impact = it }, label = "Impact", minLines = 2, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(value = remediation, onValueChange = { remediation = it }, label = "Remediation", minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            CleanPrimaryButton(
                onClick = { onSave(title, asset, vector, summary, impact, remediation) },
                enabled = title.isNotBlank() && (vector.isBlank() || preview != null)
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = { Text("New Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    NoteKind.entries.filter { it != NoteKind.TODO }.forEach { k ->
                        CleanFilterPill(label = k.wire, selected = kind == k, onClick = { kind = k })
                    }
                }
                CleanOutlinedTextField(value = key, onValueChange = { key = it }, label = "Key (e.g. /api/users?id)", singleLine = true, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(value = body, onValueChange = { body = it }, label = "Body", minLines = 3, modifier = Modifier.fillMaxWidth())
                CleanOutlinedTextField(value = tags, onValueChange = { tags = it }, label = "Tags (comma separated)", singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            CleanPrimaryButton(
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
    var content by remember(artifact.id) { mutableStateOf("Loading...") }

    LaunchedEffect(artifact.id, pattern) {
        content = withContext(Dispatchers.IO) {
            if (pattern.isBlank()) readContent().take(12_000)
            else grepContent(pattern).joinToString("\n\n").ifBlank { "No matches for /$pattern/" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        title = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Recall ${artifact.id}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                CopyActionChip(label = "artifact content", textToCopy = content)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanOutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = "Regex grep (optional)",
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                SelectionContainer {
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
