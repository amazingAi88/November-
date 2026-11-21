package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ProjectStatus
import com.example.media.WavUtils
import com.example.ui.components.StepHeaderRow
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioSwitch
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.TerracottaCoral
import com.example.ui.viewmodel.PreviewPrepState
import com.example.ui.viewmodel.ProjectStudioViewModel
import kotlin.math.roundToInt

@Composable
fun AudioSettingsScreen(
    viewModel: ProjectStudioViewModel,
    onBack: () -> Unit,
    onGoToProcessing: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val prepState by viewModel.previewPrepState.collectAsStateWithLifecycle()
    val chunks by viewModel.chunks.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startOrResumeDubbing()
        onGoToProcessing()
    }

    fun launchDubbingWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startOrResumeDubbing()
        onGoToProcessing()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAudioPreview() }
    }

    Scaffold(
        containerColor = ObsidianNight,
        topBar = {
            StudioTopBar(
                title = "Audio & BGM Mixing",
                subtitle = project?.title,
                onBack = onBack
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
            val currentProject = project ?: return@Box
            val isStereo = currentProject.audioChannels >= 2

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StepHeaderRow(stepNumber = 3, stepName = "Audio & BGM")
                }

                // Separation Switches
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Preserve Background Music & SFX", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Retain movie soundtrack, explosions, and stereo ambience.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StudioSwitch(
                                    checked = currentProject.preserveBgm,
                                    onCheckedChange = { viewModel.updateAudioSettings(preserveBgm = it) },
                                    modifier = Modifier.testTag("preserve_bgm_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Suppress Original English Dialogue", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Apply Mid-Side vocal cancellation & speech-band ducking during spoken lines.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StudioSwitch(
                                    checked = currentProject.removeEnglishDialogue,
                                    onCheckedChange = { viewModel.updateAudioSettings(removeEnglishDialogue = it) },
                                    modifier = Modifier.testTag("remove_dialogue_switch")
                                )
                            }
                        }
                    }
                }

                // Honest Separation Disclosure Warning
                item {
                    StudioDisclosureCard(
                        title = if (isStereo) {
                            "Stereo Mid-Side Separation Active"
                        } else {
                            "Mono Audio Track — Limited Separation"
                        },
                        body = if (isStereo) {
                            stringResource(R.string.separation_warning_stereo)
                        } else {
                            stringResource(R.string.separation_warning_mono)
                        },
                        isWarning = true
                    )
                }

                // 3-Channel Volume Mixer
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("3-Channel Dubbing Mixer", style = MaterialTheme.typography.titleMedium)

                            MixerSlider(
                                label = "Hindi Dialogue Volume",
                                value = currentProject.hindiVolume,
                                maxValue = 2.0f,
                                accent = SaffronGold,
                                onValueChange = { viewModel.updateAudioSettings(hindiVolume = it) }
                            )

                            MixerSlider(
                                label = "Background Music & SFX Volume",
                                value = currentProject.bgmVolume,
                                maxValue = 2.0f,
                                accent = ElectricCyan,
                                onValueChange = { viewModel.updateAudioSettings(bgmVolume = it) }
                            )

                            MixerSlider(
                                label = "Original Audio Reference Volume",
                                value = currentProject.originalVolume,
                                maxValue = 1.5f,
                                accent = TerracottaCoral,
                                onValueChange = { viewModel.updateAudioSettings(originalVolume = it) }
                            )

                            if (chunks.any { it.origWavPath != null }) {
                                OutlinedButton(
                                    onClick = { viewModel.applyMixChangesToProcessedChunks() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Re-Mix Processed Chunks With These Levels")
                                }
                            }
                        }
                    }
                }

                // Real A/B Audio Preview
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = SaffronGold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Real Audio A/B Preview", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Audition the first 15s (or chunk 1 if already processed) comparing raw original movie audio vs separated BGM/Hindi mix.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (prepState is PreviewPrepState.Preparing) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SaffronGold)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Extracting & mixing real audio preview...", style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (prepState is PreviewPrepState.Error) {
                                Text(
                                    text = (prepState as PreviewPrepState.Error).message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (playbackState.isPlaying) {
                                val fraction = if (playbackState.durationMs > 0L) {
                                    (playbackState.positionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
                                } else 0f
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = SaffronGold,
                                    trackColor = ObsidianSurfaceVariant
                                )
                                Text(
                                    "${WavUtils.formatTimecode(playbackState.positionMs)} / ${WavUtils.formatTimecode(playbackState.durationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SaffronGold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val origPlaying = playbackState.activeTag == "orig_preview" && playbackState.isPlaying
                                val finalPlaying = playbackState.activeTag == "final_preview" && playbackState.isPlaying

                                OutlinedButton(
                                    onClick = { viewModel.previewOriginalAudio() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("preview_original_audio_button")
                                ) {
                                    Icon(
                                        if (origPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (origPlaying) "Stop Orig" else "Original")
                                }

                                Button(
                                    onClick = { viewModel.previewProcessedAudio() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("preview_final_audio_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ElectricCyan,
                                        contentColor = MaterialTheme.colorScheme.onTertiary
                                    )
                                ) {
                                    Icon(
                                        if (finalPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (finalPlaying) "Stop Mix" else "Final Mix")
                                }
                            }
                        }
                    }
                }

                item {
                    val alreadyStarted = currentProject.status != ProjectStatus.CONFIGURING
                    Button(
                        onClick = {
                            viewModel.stopAudioPreview()
                            if (!alreadyStarted) {
                                launchDubbingWithNotificationPermission()
                            } else {
                                onGoToProcessing()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_dubbing_pipeline_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SaffronGold,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(if (alreadyStarted) "Open Processing Pipeline" else "Start Hindi Dubbing Pipeline")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun MixerSlider(
    label: String,
    value: Float,
    maxValue: Float,
    accent: androidx.compose.ui.graphics.Color,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..maxValue,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent
            )
        )
    }
}
