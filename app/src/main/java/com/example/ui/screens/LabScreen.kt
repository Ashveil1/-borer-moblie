package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tools.BorerSkill
import com.example.tools.DiffEngine
import com.example.tools.MutateEngine
import com.example.tools.TransformEngine
import com.example.ui.BorerViewModel

@Composable
fun LabScreen(
    vm: BorerViewModel,
    onOpenDrawer: (() -> Unit)?,
    onOpenSettings: () -> Unit = {}
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
            title = "Security Lab",
            onOpenDrawer = onOpenDrawer,
            trailing = {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("lab_open_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )

        CleanSegmentBar(
            tabs = listOf(
                "Memory" to "tab_lab_memory",
                "Workbench" to "tab_lab_workbench",
                "Skills" to "tab_lab_skills"
            ),
            selectedIndex = tabIdx,
            onSelect = { tabIdx = it },
            modifier = Modifier.widthIn(max = 760.dp)
        )

        when (tabIdx) {
            0 -> MemoryPane(vm)
            1 -> WorkbenchPane()
            else -> SkillsPane(vm)
        }
    }
}

@Composable
private fun MemoryPane(vm: BorerViewModel) {
    val experiences by vm.allExperience.collectAsStateWithLifecycle()
    val techniques by vm.allTechniques.collectAsStateWithLifecycle()
    val targets by vm.allTargets.collectAsStateWithLifecycle()
    var showAddExp by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
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
                        Text("Cross-Session Memory", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${experiences.size} experience · ${techniques.size} techniques · ${targets.size} hosts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Button(
                        onClick = { showAddExp = true },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("add_memory_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add experience")
                        Spacer(Modifier.width(4.dp))
                        Text("Record")
                    }
                }
            }
        }

        if (techniques.isEmpty() && experiences.isEmpty() && targets.isEmpty()) {
            item {
                HairlineCard {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = "No memory",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No learned memory yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Borer records successful techniques, stack experience, and target profiles as sessions run, or tap Record above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (techniques.isNotEmpty()) {
            item { SectionHeader(title = "Ranked Techniques", trailing = "${techniques.size}") }
            items(techniques, key = { "tech_${it.id}" }) { t ->
                HairlineCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(t.situation, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${(t.score * 100).toInt()}% · ${t.success}/${t.attempts}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.steps, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deleteTechnique(t.id) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete technique", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (experiences.isNotEmpty()) {
            item { SectionHeader(title = "Stack Experience", trailing = "${experiences.size}") }
            items(experiences, key = { "exp_${it.id}" }) { e ->
                HairlineCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(e.fingerprint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${(e.score * 100).toInt()}% · ${e.success}/${e.attempts}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(e.approach, style = MaterialTheme.typography.bodyLarge)
                            if (e.outcome.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(e.outcome, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { vm.deleteExperience(e.id) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete experience", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (targets.isNotEmpty()) {
            item { SectionHeader(title = "Target Host Profiles", trailing = "${targets.size}") }
            items(targets, key = { "host_${it.host}" }) { h ->
                HairlineCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${h.host} · ${h.visits} visits", style = MaterialTheme.typography.titleMedium)
                            if (h.profile.isNotBlank()) Text("stack: ${h.profile}", style = MaterialTheme.typography.bodySmall)
                            if (h.findings.isNotBlank()) Text("found: ${h.findings}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            if (h.deadEnds.isNotBlank()) Text("dead ends: ${h.deadEnds}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deleteTargetHistory(h.host) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete target", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAddExp) {
        var fp by remember { mutableStateOf("php+mysql|sqli") }
        var approach by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf("") }
        var worked by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddExp = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Record Experience") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = fp, onValueChange = { fp = it }, label = { Text("Fingerprint (stack|vuln)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = approach, onValueChange = { approach = it }, label = { Text("Approach tried") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = outcome, onValueChange = { outcome = it }, label = { Text("Outcome / signal") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Worked?")
                        Switch(checked = worked, onCheckedChange = { worked = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.addExperience(fp, approach, outcome, worked)
                        showAddExp = false
                    },
                    enabled = fp.isNotBlank() && approach.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddExp = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchPane() {
    val context = LocalContext.current
    var toolMode by remember { mutableIntStateOf(0) }
    var tfOp by remember { mutableStateOf("base64") }
    var tfInput by remember { mutableStateOf("borer security test") }
    var tfAlgo by remember { mutableStateOf("sha256") }
    var tfKey by remember { mutableStateOf("") }
    var tfOffset by remember { mutableStateOf("0") }

    var mutSeed by remember { mutableStateOf("admin' OR 1=1--") }
    var mutRules by remember { mutableStateOf(setOf("url", "case", "terminator")) }
    var mutContexts by remember { mutableStateOf(setOf<String>()) }

    var diffA by remember { mutableStateOf("HTTP/1.1 200 OK\nContent-Type: application/json\n\n{\"ok\":true,\"role\":\"guest\"}") }
    var diffB by remember { mutableStateOf("HTTP/1.1 403 Forbidden\nContent-Type: application/json\n\n{\"ok\":false,\"error\":\"denied\"}") }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = toolMode == 0,
                    onClick = { toolMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    modifier = Modifier.testTag("wb_transform")
                ) { Text("Transform", maxLines = 1) }
                SegmentedButton(
                    selected = toolMode == 1,
                    onClick = { toolMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    modifier = Modifier.testTag("wb_mutate")
                ) { Text("Mutate", maxLines = 1) }
                SegmentedButton(
                    selected = toolMode == 2,
                    onClick = { toolMode = 2 },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    modifier = Modifier.testTag("wb_diff")
                ) { Text("Diff", maxLines = 1) }
            }
        }

        when (toolMode) {
            0 -> item {
                val output = remember(tfOp, tfInput, tfAlgo, tfKey, tfOffset) {
                    TransformEngine.run(tfOp, tfInput, null, tfAlgo, tfKey, tfOffset.toIntOrNull() ?: 0)
                        .fold(onSuccess = { it }, onFailure = { "error: ${it.message}" })
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HairlineCard {
                        Text("Operation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            TransformEngine.supportedOps.forEach { op ->
                                FilterChip(selected = tfOp == op, onClick = { tfOp = op }, label = { Text(op) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = tfInput,
                            onValueChange = { tfInput = it },
                            label = { Text("Input") },
                            minLines = 3,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (tfOp == "hash" || tfOp == "hmac") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = tfAlgo, onValueChange = { tfAlgo = it }, label = { Text("Algorithm (md5|sha1|sha256|sha512)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (tfOp == "hmac") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = tfKey, onValueChange = { tfKey = it }, label = { Text("HMAC Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (tfOp == "case") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = tfOffset, onValueChange = { tfOffset = it }, label = { Text("Caesar Offset") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    HairlineCard {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Output", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(
                                onClick = { copyToClipboard("transform", output) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy output", modifier = Modifier.size(16.dp))
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = output,
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
            1 -> item {
                val (_, variants) = remember(mutSeed, mutRules, mutContexts) {
                    MutateEngine.generate(mutSeed, mutRules.toList(), mutContexts.toList())
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HairlineCard {
                        OutlinedTextField(
                            value = mutSeed,
                            onValueChange = { mutSeed = it },
                            label = { Text("Seed payload") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Rules", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MutateEngine.allRules.forEach { r ->
                                FilterChip(
                                    selected = r in mutRules,
                                    onClick = { mutRules = if (r in mutRules) mutRules - r else mutRules + r },
                                    label = { Text(r) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Wrapper Contexts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MutateEngine.allContexts.forEach { c ->
                                FilterChip(
                                    selected = c in mutContexts,
                                    onClick = { mutContexts = if (c in mutContexts) mutContexts - c else mutContexts + c },
                                    label = { Text(c) }
                                )
                            }
                        }
                    }

                    HairlineCard {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Generated Variants (${variants.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(
                                onClick = { copyToClipboard("variants", variants.joinToString("\n") { it.value }) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy variants", modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                variants.forEach { v ->
                                    Text("${v.index}. (${v.how}) ${v.value}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            else -> item {
                val diffResult = remember(diffA, diffB) { DiffEngine.compare(diffA, diffB) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HairlineCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = diffA, onValueChange = { diffA = it }, label = { Text("Baseline (A)") }, minLines = 4, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = diffB, onValueChange = { diffB = it }, label = { Text("Probe (B)") }, minLines = 4, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HairlineCard {
                        Text("Structural Diff", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(diffResult, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsPane(vm: BorerViewModel) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<BorerSkill?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SectionHeader(
                    title = "Playbooks (.borer/skills)",
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { showCreate = true },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add skill")
                    Spacer(Modifier.width(4.dp))
                    Text("New Skill")
                }
            }
        }
        if (skills.isEmpty()) {
            item {
                HairlineCard {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No skill playbooks yet", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            items(skills, key = { it.name }) { s ->
                HairlineCard(onClick = { selected = s }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(s.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Inspect skill",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    selected?.let { sk ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(sk.name) },
            text = {
                Text(sk.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Create Skill Playbook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (slug)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Markdown instructions") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.saveCustomSkill(name, desc, body); showCreate = false },
                    enabled = name.isNotBlank() && body.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
}
