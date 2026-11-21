package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.SlangDictionaryManager
import com.example.ai.SlangOverrideRule
import com.example.data.local.DialogueStyle
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SaffronContainer
import com.example.ui.theme.SaffronGold
import com.example.ui.viewmodel.SettingsViewModel
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlangDictionaryScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val config by viewModel.slangConfig.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var filterTab by remember { mutableStateOf("ALL") } // ALL, CONTEXT, ENABLED
    var testLine by remember { mutableStateOf("I want to fuck you tonight, fucker!") }
    var testSceneContext by remember { mutableStateOf("in the bedroom, intimate") }

    var editingRule by remember { mutableStateOf<SlangOverrideRule?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showRawJsonDialog by remember { mutableStateOf(false) }
    var rawJsonDraft by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshSlangDictionary()
    }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message!!)
            viewModel.clearMessage()
        }
    }

    val filteredRules = remember(config.rules, searchQuery, filterTab) {
        val q = searchQuery.trim().lowercase()
        config.rules.filter { rule ->
            val tabOk = when (filterTab) {
                "CONTEXT" -> rule.contextKeywords.isNotEmpty()
                "ENABLED" -> rule.enabled
                else -> true
            }
            val queryOk = q.isEmpty() ||
                rule.sourceTerm.lowercase().contains(q) ||
                rule.hindiReplacement.lowercase().contains(q) ||
                rule.note.lowercase().contains(q) ||
                rule.contextKeywords.any { it.lowercase().contains(q) }
            tabOk && queryOk
        }
    }

    val livePrep = remember(config, testLine, testSceneContext, prefs.defaultDialogueStyle) {
        SlangDictionaryManager.prepareProtectedText(
            config = config,
            englishText = testLine,
            prevContext = testSceneContext,
            nextContext = "",
            emotion = "",
            dialogueStyle = prefs.defaultDialogueStyle
        )
    }

    Scaffold(
        containerColor = ObsidianNight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = "Slang & Context JSON",
                subtitle = SlangDictionaryManager.DICTIONARY_FILE_NAME,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            rawJsonDraft = viewModel.readRawSlangJson()
                            showRawJsonDialog = true
                        },
                        modifier = Modifier.testTag("view_raw_slang_json_button")
                    ) {
                        Icon(
                            Icons.Filled.DataObject,
                            contentDescription = "View or Edit Raw JSON",
                            tint = ElectricCyan
                        )
                    }
                    IconButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.testTag("reset_slang_json_button")
                    ) {
                        Icon(
                            Icons.Filled.Restore,
                            contentDescription = "Reset Default Dictionary",
                            tint = SaffronGold
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingRule = SlangOverrideRule(
                        id = "",
                        sourceTerm = "",
                        hindiReplacement = "",
                        contextKeywords = emptyList(),
                        dialogueStyles = emptyList(),
                        wholeWord = true,
                        enabled = true,
                        note = ""
                    )
                    showRuleDialog = true
                },
                containerColor = SaffronGold,
                contentColor = ObsidianNight,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("add_slang_rule_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Slang Rule")
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Master File & Toggle Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Book, contentDescription = null, tint = SaffronGold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "Internal JSON Override Active",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${config.rules.count { it.enabled }} / ${config.rules.size} rules enabled • Internal filesDir",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = config.enabled,
                                    onCheckedChange = { viewModel.setSlangDictionaryEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                                    modifier = Modifier.testTag("slang_master_toggle")
                                )
                            }
                            Text(
                                text = "Stored in private app storage (${SlangDictionaryManager.DICTIONARY_FILE_NAME}). Overrides censored machine translation words (like ML Kit's 'लानत'/'मूर्ख') and injects mandatory Hindi glossary terms into Local GGUF & Gemini Cloud prompts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Live Context Matcher Simulator Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slang_context_tester_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Test Context & Slang Override", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = testLine,
                                onValueChange = { testLine = it },
                                label = { Text("English Movie Line") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("slang_test_line_input")
                            )
                            OutlinedTextField(
                                value = testSceneContext,
                                onValueChange = { testSceneContext = it },
                                label = { Text("Scene / Previous Line Context (optional)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("slang_test_context_input")
                            )

                            Surface(
                                color = ObsidianElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (livePrep.appliedMatches.isNotEmpty()) EmeraldSuccess.copy(alpha = 0.55f) else ObsidianBorder
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (!config.enabled) {
                                        Text(
                                            "Dictionary override is currently turned OFF.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else if (livePrep.appliedMatches.isEmpty()) {
                                        Text(
                                            "No JSON rules matched this line.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        val previewDemo = if (livePrep.directOverrideHindi != null) {
                                            "Direct Full-Line Override → ${livePrep.directOverrideHindi}"
                                        } else {
                                            SlangDictionaryManager.restoreAndOverride(
                                                machineTranslatedHindi = livePrep.protectedEnglish,
                                                prep = livePrep
                                            )
                                        }
                                        Text(
                                            text = "Matched Overrides (${livePrep.appliedMatches.size}):",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ElectricCyan
                                        )
                                        livePrep.appliedMatches.forEach { m ->
                                            val ctxLabel = if (m.contextMatchCount > 0) {
                                                "Context Match (${m.contextMatchCount} kw)"
                                            } else {
                                                "General Rule"
                                            }
                                            Text(
                                                text = "• \"${m.matchedText}\" → \"${m.rule.hindiReplacement}\" ($ctxLabel)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = EmeraldSuccess
                                            )
                                        }
                                        Text(
                                            text = "Override Output Preview: $previewDemo",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SaffronGold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Search & Filter Bar
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            label = { Text("Search English slang, Hindi, or context...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("slang_search_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ALL" to "All (${config.rules.size})", "CONTEXT" to "Context Rules", "ENABLED" to "Enabled").forEach { (key, label) ->
                                FilterChip(
                                    selected = filterTab == key,
                                    onClick = { filterTab = key },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    )
                                )
                            }
                        }
                    }
                }

                if (filteredRules.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = ObsidianSurface,
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("No matching slang rules", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Tap '+' to add a custom English-to-Hindi mapping or clear search filters.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredRules, key = { it.id }) { rule ->
                        SlangRuleCard(
                            rule = rule,
                            onToggle = { enabled -> viewModel.toggleSlangRule(rule.id, enabled) },
                            onEdit = {
                                editingRule = rule
                                showRuleDialog = true
                            },
                            onDelete = { viewModel.deleteSlangRule(rule.id) }
                        )
                    }
                }
            }
        }
    }

    if (showRuleDialog && editingRule != null) {
        SlangRuleEditorDialog(
            initialRule = editingRule!!,
            onDismiss = { showRuleDialog = false },
            onSave = { updated ->
                viewModel.upsertSlangRule(updated)
                showRuleDialog = false
            }
        )
    }

    if (showRawJsonDialog) {
        AlertDialog(
            onDismissRequest = { showRawJsonDialog = false },
            containerColor = ObsidianSurface,
            title = { Text(SlangDictionaryManager.DICTIONARY_FILE_NAME) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Edit the raw JSON mapping saved inside app internal storage:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = rawJsonDraft,
                        onValueChange = { rawJsonDraft = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 360.dp)
                            .testTag("raw_slang_json_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importRawSlangJson(rawJsonDraft)
                        showRawJsonDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaffronGold, contentColor = ObsidianNight),
                    modifier = Modifier.testTag("save_raw_slang_json_button")
                ) {
                    Text("Save JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawJsonDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = ObsidianSurface,
            title = { Text("Reset JSON Dictionary?") },
            text = {
                Text("This restores ${SlangDictionaryManager.DICTIONARY_FILE_NAME} to its built-in Bollywood/OTT & uncensored slang rules.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetSlangDictionary()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaffronGold, contentColor = ObsidianNight)
                ) {
                    Text("Reset Defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlangRuleCard(
    rule: SlangOverrideRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slang_rule_card_${rule.id}"),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (rule.enabled) ObsidianBorder else ObsidianBorder.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "\"${rule.sourceTerm}\"  →  ${rule.hindiReplacement}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (rule.enabled) SaffronGold else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (rule.note.isNotBlank()) {
                        Text(
                            text = rule.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                    modifier = Modifier.testTag("toggle_slang_${rule.id}")
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (rule.contextKeywords.isNotEmpty()) {
                    StatusPill(
                        label = "CONTEXT: ${rule.contextKeywords.take(4).joinToString(", ")}${if (rule.contextKeywords.size > 4) "…" else ""}",
                        tone = "ACTIVE"
                    )
                } else {
                    StatusPill(label = "GENERAL OVERRIDE", tone = "NEUTRAL")
                }
                if (rule.dialogueStyles.isNotEmpty()) {
                    StatusPill(label = rule.dialogueStyles.first(), tone = "WARN")
                }
                if (rule.wholeWord) {
                    StatusPill(label = "WHOLE WORD", tone = "NEUTRAL")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${rule.sourceTerm}", tint = ElectricCyan)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete ${rule.sourceTerm}", tint = ErrorCrimson)
                }
            }
        }
    }
}

@Composable
private fun SlangRuleEditorDialog(
    initialRule: SlangOverrideRule,
    onDismiss: () -> Unit,
    onSave: (SlangOverrideRule) -> Unit
) {
    var sourceTerm by remember(initialRule) { mutableStateOf(initialRule.sourceTerm) }
    var hindiReplacement by remember(initialRule) { mutableStateOf(initialRule.hindiReplacement) }
    var contextCsv by remember(initialRule) { mutableStateOf(initialRule.contextKeywords.joinToString(", ")) }
    var note by remember(initialRule) { mutableStateOf(initialRule.note) }
    var wholeWord by remember(initialRule) { mutableStateOf(initialRule.wholeWord) }
    var rawUncensoredOnly by remember(initialRule) {
        mutableStateOf(initialRule.dialogueStyles.contains(DialogueStyle.RAW_UNCENSORED))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        title = { Text(if (initialRule.id.isBlank()) "Add Slang JSON Rule" else "Edit Slang Rule") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = sourceTerm,
                    onValueChange = { sourceTerm = it },
                    label = { Text("English Word / Phrase") },
                    placeholder = { Text("e.g., fucker or fuck off") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_source_term_input")
                )
                OutlinedTextField(
                    value = hindiReplacement,
                    onValueChange = { hindiReplacement = it },
                    label = { Text("Hindi Devanagari Replacement") },
                    placeholder = { Text("e.g., चुतिया") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_hindi_replacement_input")
                )
                OutlinedTextField(
                    value = contextCsv,
                    onValueChange = { contextCsv = it },
                    label = { Text("Context Keywords (comma-separated, optional)") },
                    placeholder = { Text("e.g., bed, sex, tonight (leave blank for always)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_context_keywords_input")
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Context Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = wholeWord,
                        onCheckedChange = { wholeWord = it }
                    )
                    Text("Match whole words only", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = rawUncensoredOnly,
                        onCheckedChange = { rawUncensoredOnly = it }
                    )
                    Text("Only apply in Raw Uncensored style", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val keywords = contextCsv.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val styles = if (rawUncensoredOnly) {
                        listOf(DialogueStyle.RAW_UNCENSORED)
                    } else emptyList()
                    onSave(
                        initialRule.copy(
                            id = initialRule.id.ifBlank { "custom_${UUID.randomUUID().toString().take(8)}" },
                            sourceTerm = sourceTerm.trim(),
                            hindiReplacement = hindiReplacement.trim(),
                            contextKeywords = keywords,
                            dialogueStyles = styles,
                            wholeWord = wholeWord,
                            note = note.trim()
                        )
                    )
                },
                enabled = sourceTerm.isNotBlank() && hindiReplacement.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SaffronGold, contentColor = ObsidianNight),
                modifier = Modifier.testTag("dialog_save_slang_rule_button")
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
