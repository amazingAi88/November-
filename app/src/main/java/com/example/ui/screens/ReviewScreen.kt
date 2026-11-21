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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.SlangDictionaryManager
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.SpeakerEntity
import com.example.media.WavUtils
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioSwitch
import com.example.ui.components.StudioTopBar
import com.example.ui.components.studioCheckboxColors
import com.example.ui.components.studioFilterChipBorder
import com.example.ui.components.studioFilterChipColors
import com.example.ui.components.studioRadioButtonColors
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SaffronContainer
import com.example.ui.theme.SaffronGold
import com.example.ui.viewmodel.ProjectStudioViewModel
import java.util.Locale

@Composable
fun ReviewScreen(
    viewModel: ProjectStudioViewModel,
    onBack: () -> Unit,
    onGoToExport: () -> Unit
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val regeneratingId by viewModel.regeneratingSegmentId.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var activeFilter by remember { mutableStateOf("SPEECH") }
    var editingSegment by remember { mutableStateOf<DialogueSegmentEntity?>(null) }
    var initialSaveToDict by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner!!)
            viewModel.clearBanner()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAudioPreview() }
    }

    val filtered = remember(segments, activeFilter) {
        when (activeFilter) {
            "SPEECH" -> segments.filter { it.audioType == "SPEECH" }
            "NON_SPEECH" -> segments.filter { it.audioType != "SPEECH" }
            "FAILED" -> segments.filter { it.status == SegmentStatus.FAILED }
            else -> segments
        }
    }

    Scaffold(
        containerColor = ObsidianNight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = "Dialogue Review",
                subtitle = project?.title,
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.stopAudioPreview()
                            onGoToExport()
                        },
                        modifier = Modifier.testTag("review_to_export_button")
                    ) {
                        Icon(Icons.Filled.MovieCreation, contentDescription = null, tint = EmeraldSuccess)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", color = EmeraldSuccess)
                    }
                }
            )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "SPEECH" to "Dialogue",
                        "ALL" to "All",
                        "NON_SPEECH" to "BGM/SFX",
                        "FAILED" to "Failed"
                    ).forEach { (key, label) ->
                        val isSelected = activeFilter == key
                        FilterChip(
                            selected = isSelected,
                            onClick = { activeFilter = key },
                            label = { Text(label) },
                            colors = studioFilterChipColors(),
                            border = studioFilterChipBorder(selected = isSelected)
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        StudioDisclosureCard(
                            title = "No Segments in This Filter",
                            body = "Switch filter tabs or run processing to populate English transcripts and Hindi dialogue."
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { it.id }) { segment ->
                            SegmentReviewCard(
                                segment = segment,
                                isPlaying = playbackState.activeTag == "seg_tts_${segment.id}" && playbackState.isPlaying,
                                isRegenerating = regeneratingId == segment.id,
                                onPlayHindi = { viewModel.playSegmentHindi(segment) },
                                onEdit = {
                                    initialSaveToDict = false
                                    editingSegment = segment
                                },
                                onSaveToDictionary = {
                                    initialSaveToDict = true
                                    editingSegment = segment
                                },
                                onRegenerate = { viewModel.regenerateSegmentTts(segment) }
                            )
                        }
                    }
                }
            }
        }
    }

    editingSegment?.let { seg ->
        EditSegmentDialog(
            segment = seg,
            speakers = speakers,
            initialSaveToDictionary = initialSaveToDict,
            onDismiss = {
                editingSegment = null
                initialSaveToDict = false
            },
            onSaveAndRegenerate = { newHindi, newSpeaker, newEmotion, newSpeed, saveToDict, slangSource, slangHindi, wholeWord ->
                editingSegment = null
                initialSaveToDict = false
                viewModel.updateSegment(
                    segment = seg,
                    newHindiText = newHindi,
                    newSpeakerLabel = newSpeaker,
                    newEmotion = newEmotion,
                    newSpeed = newSpeed,
                    saveToSlangDictionary = saveToDict,
                    slangSourceTerm = slangSource,
                    slangHindiReplacement = slangHindi,
                    slangWholeWord = wholeWord
                )
            }
        )
    }
}

@Composable
private fun SegmentReviewCard(
    segment: DialogueSegmentEntity,
    isPlaying: Boolean,
    isRegenerating: Boolean,
    onPlayHindi: () -> Unit,
    onEdit: () -> Unit,
    onSaveToDictionary: () -> Unit,
    onRegenerate: () -> Unit
) {
    val statusTone = when (segment.status) {
        SegmentStatus.TTS_DONE -> "SUCCESS"
        SegmentStatus.FAILED -> "ERROR"
        else -> "ACTIVE"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("segment_card_${segment.id}"),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder),
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
                Text(
                    text = "${WavUtils.formatTimecode(segment.startMs, includeMillis = true)} → ${WavUtils.formatTimecode(segment.endMs, includeMillis = true)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (segment.isOverlapping) {
                        StatusPill(label = "OVERLAP", tone = "WARN")
                    }
                    StatusPill(label = segment.speakerLabel, tone = "ACTIVE")
                    StatusPill(label = segment.status, tone = statusTone)
                }
            }

            if (segment.audioType == "SPEECH") {
                Text(
                    text = "EN: ${segment.englishText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = segment.hindiText.ifBlank { "(Pending Hindi translation)" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Emotion: ${segment.emotion} • Speed: ${String.format(Locale.US, "%.2fx", segment.speechSpeed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (segment.ttsDurationMs > 0L) {
                        Text(
                            text = "TTS ${segment.ttsDurationMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold
                        )
                    }
                }

                if (!segment.errorMessage.isNullOrBlank()) {
                    Text(
                        text = segment.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCrimson
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPlayHindi,
                        enabled = segment.ttsAudioPath != null && !isRegenerating,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SaffronContainer,
                            contentColor = SaffronGold
                        )
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPlaying) "Stop" else "Hindi Audio")
                    }

                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("edit_segment_${segment.id}")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit")
                    }

                    IconButton(
                        onClick = onSaveToDictionary,
                        enabled = segment.englishText.isNotBlank() && segment.hindiText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("save_segment_slang_${segment.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdd,
                            contentDescription = "Save translation to slang dictionary",
                            tint = SaffronGold
                        )
                    }

                    OutlinedButton(
                        onClick = onRegenerate,
                        enabled = !isRegenerating && segment.hindiText.isNotBlank(),
                        modifier = Modifier.height(48.dp)
                    ) {
                        if (isRegenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SaffronGold)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Regenerate voice")
                        }
                    }
                }
            } else {
                Text(
                    text = "[${segment.audioType}] ${segment.englishText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ElectricCyan
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSegmentDialog(
    segment: DialogueSegmentEntity,
    speakers: List<SpeakerEntity>,
    initialSaveToDictionary: Boolean = false,
    onDismiss: () -> Unit,
    onSaveAndRegenerate: (
        hindiText: String,
        speakerLabel: String,
        emotion: String,
        speed: Float,
        saveToDictionary: Boolean,
        slangSourceTerm: String,
        slangHindiReplacement: String,
        slangWholeWord: Boolean
    ) -> Unit
) {
    val defaultEnglishRuleTerm = remember(segment.id, segment.englishText) {
        segment.englishText.trim().trimEnd('.', '!', '?')
    }
    var hindiText by remember(segment.id) { mutableStateOf(segment.hindiText) }
    var selectedSpeaker by remember(segment.id) { mutableStateOf(segment.speakerLabel) }
    var selectedEmotion by remember(segment.id) { mutableStateOf(segment.emotion) }
    var speed by remember(segment.id) { mutableFloatStateOf(segment.speechSpeed) }

    var saveToDictionary by remember(segment.id, initialSaveToDictionary) {
        mutableStateOf(initialSaveToDictionary)
    }
    var dictScope by remember(segment.id) { mutableStateOf("SENTENCE") } // SENTENCE or WORD
    var slangSourceTerm by remember(segment.id) { mutableStateOf(defaultEnglishRuleTerm) }
    var slangHindiTerm by remember(segment.id) { mutableStateOf(segment.hindiText) }
    var hasCustomSlangHindi by remember(segment.id) { mutableStateOf(false) }
    var slangWholeWord by remember(segment.id) { mutableStateOf(true) }

    val needsTtsRegen = hindiText.trim() != segment.hindiText.trim() ||
        selectedSpeaker != segment.speakerLabel ||
        speed != segment.speechSpeed

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        title = { Text("Edit Hindi Dialogue") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "EN: ${segment.englishText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = hindiText,
                    onValueChange = { updated ->
                        hindiText = updated
                        if (!hasCustomSlangHindi && dictScope == "SENTENCE") {
                            slangHindiTerm = updated
                        }
                    },
                    label = { Text("Hindi Dialogue (Devanagari)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_dialog_hindi_input"),
                    minLines = 2
                )

                // Permanent Slang Dictionary Save Card
                Surface(
                    color = ObsidianElevated,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (saveToDictionary) SaffronGold.copy(alpha = 0.7f) else ObsidianBorder
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Save to Slang Dictionary",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (saveToDictionary) SaffronGold else MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Permanently store in ${SlangDictionaryManager.DICTIONARY_FILE_NAME} for future movies",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            StudioSwitch(
                                checked = saveToDictionary,
                                onCheckedChange = { enabled ->
                                    saveToDictionary = enabled
                                    if (enabled && dictScope == "SENTENCE" && !hasCustomSlangHindi) {
                                        slangHindiTerm = hindiText
                                    }
                                },
                                modifier = Modifier.testTag("review_save_to_slang_switch")
                            )
                        }

                        if (saveToDictionary) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = dictScope == "SENTENCE",
                                        onClick = {
                                            dictScope = "SENTENCE"
                                            slangSourceTerm = defaultEnglishRuleTerm
                                            if (!hasCustomSlangHindi) {
                                                slangHindiTerm = hindiText
                                            }
                                        },
                                        colors = studioRadioButtonColors(),
                                        modifier = Modifier.testTag("slang_scope_sentence_radio")
                                    )
                                    Text("Full Sentence", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = dictScope == "WORD",
                                        onClick = {
                                            dictScope = "WORD"
                                        },
                                        colors = studioRadioButtonColors(),
                                        modifier = Modifier.testTag("slang_scope_word_radio")
                                    )
                                    Text("Word / Phrase", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            OutlinedTextField(
                                value = slangSourceTerm,
                                onValueChange = { slangSourceTerm = it },
                                label = {
                                    Text(
                                        if (dictScope == "WORD") "English Word / Phrase to Match"
                                        else "English Sentence to Match"
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("review_slang_source_input")
                            )
                            OutlinedTextField(
                                value = slangHindiTerm,
                                onValueChange = {
                                    slangHindiTerm = it
                                    hasCustomSlangHindi = true
                                },
                                label = { Text("Saved Hindi Replacement") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("review_slang_hindi_input")
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = slangWholeWord,
                                    onCheckedChange = { slangWholeWord = it },
                                    colors = studioCheckboxColors(),
                                    modifier = Modifier.testTag("review_slang_whole_word_checkbox")
                                )
                                Text("Match whole words only", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (speakers.isNotEmpty()) {
                    Text("Speaker", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        speakers.forEach { sp ->
                            val isSel = selectedSpeaker == sp.speakerLabel
                            FilterChip(
                                selected = isSel,
                                onClick = { selectedSpeaker = sp.speakerLabel },
                                label = { Text(sp.displayName) },
                                colors = studioFilterChipColors(),
                                border = studioFilterChipBorder(selected = isSel)
                            )
                        }
                    }
                }
                Text("Emotion", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("neutral", "happy", "sad", "angry", "excited", "serious", "whisper").forEach { emo ->
                        val isSel = selectedEmotion == emo
                        FilterChip(
                            selected = isSel,
                            onClick = { selectedEmotion = emo },
                            label = { Text(emo) },
                            colors = studioFilterChipColors(),
                            border = studioFilterChipBorder(selected = isSel)
                        )
                    }
                }
                Text(
                    text = "Speed: ${String.format(Locale.US, "%.2fx", speed)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.75f..1.35f,
                    colors = SliderDefaults.colors(thumbColor = SaffronGold, activeTrackColor = SaffronGold)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveAndRegenerate(
                        hindiText,
                        selectedSpeaker,
                        selectedEmotion,
                        speed,
                        saveToDictionary,
                        slangSourceTerm,
                        slangHindiTerm,
                        slangWholeWord
                    )
                },
                enabled = hindiText.isNotBlank() &&
                    (!saveToDictionary || (slangSourceTerm.isNotBlank() && slangHindiTerm.isNotBlank())),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaffronGold,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("review_edit_confirm_button")
            ) {
                Text(
                    when {
                        needsTtsRegen -> "Save & Regenerate Voice"
                        saveToDictionary -> "Save to Dictionary"
                        else -> "Save"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
