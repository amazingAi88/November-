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
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.SpeakerEntity
import com.example.media.WavUtils
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.ObsidianBorder
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
                        FilterChip(
                            selected = activeFilter == key,
                            onClick = { activeFilter = key },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SaffronContainer,
                                selectedLabelColor = SaffronGold
                            )
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
                                onEdit = { editingSegment = segment },
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
            onDismiss = { editingSegment = null },
            onSaveAndRegenerate = { newHindi, newSpeaker, newEmotion, newSpeed ->
                editingSegment = null
                viewModel.updateSegment(seg, newHindi, newSpeaker, newEmotion, newSpeed)
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
                            .height(44.dp)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit")
                    }

                    OutlinedButton(
                        onClick = onRegenerate,
                        enabled = !isRegenerating && segment.hindiText.isNotBlank(),
                        modifier = Modifier.height(44.dp)
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
    onDismiss: () -> Unit,
    onSaveAndRegenerate: (hindiText: String, speakerLabel: String, emotion: String, speed: Float) -> Unit
) {
    var hindiText by remember(segment.id) { mutableStateOf(segment.hindiText) }
    var selectedSpeaker by remember(segment.id) { mutableStateOf(segment.speakerLabel) }
    var selectedEmotion by remember(segment.id) { mutableStateOf(segment.emotion) }
    var speed by remember(segment.id) { mutableFloatStateOf(segment.speechSpeed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Hindi Dialogue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "EN: ${segment.englishText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = hindiText,
                    onValueChange = { hindiText = it },
                    label = { Text("Hindi Dialogue (Devanagari)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                if (speakers.isNotEmpty()) {
                    Text("Speaker", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        speakers.forEach { sp ->
                            FilterChip(
                                selected = selectedSpeaker == sp.speakerLabel,
                                onClick = { selectedSpeaker = sp.speakerLabel },
                                label = { Text(sp.displayName) }
                            )
                        }
                    }
                }
                Text("Emotion", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("neutral", "happy", "sad", "angry", "excited", "serious", "whisper").forEach { emo ->
                        FilterChip(
                            selected = selectedEmotion == emo,
                            onClick = { selectedEmotion = emo },
                            label = { Text(emo) }
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
                onClick = { onSaveAndRegenerate(hindiText, selectedSpeaker, selectedEmotion, speed) },
                enabled = hindiText.isNotBlank()
            ) {
                Text("Save & Regenerate Voice")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
