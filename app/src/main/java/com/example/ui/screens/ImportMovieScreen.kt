package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.SubtitleLanguage
import com.example.media.MediaInspector
import com.example.media.VideoExporter
import com.example.media.WavUtils
import com.example.ui.components.ExoVideoPreviewPlayer
import com.example.ui.components.StepHeaderRow
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioTopBar
import com.example.ui.components.studioFilterChipBorder
import com.example.ui.components.studioFilterChipColors
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SaffronGold
import com.example.ui.viewmodel.ImportUiState
import com.example.ui.viewmodel.ImportViewModel

@Composable
fun ImportMovieScreen(
    onBack: () -> Unit,
    onProjectCreated: (Long) -> Unit,
    viewModel: ImportViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableBytes by viewModel.availableStorageBytes.collectAsStateWithLifecycle()
    val mediaAccelSettings by viewModel.mediaAccelSettings.collectAsStateWithLifecycle()
    val subtitleError by viewModel.subtitleError.collectAsStateWithLifecycle()

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectPickedVideo(uri)
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSubtitleUri(uri)
        }
    }

    Scaffold(
        containerColor = ObsidianNight,
        topBar = {
            StudioTopBar(
                title = "Import Movie",
                subtitle = "Android Storage Picker (Zero-Permission SAF)",
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StepHeaderRow(stepNumber = 1, stepName = "Movie Selection")
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Choose a Legally Obtained Video File",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Supports MP4, MKV, WebM, MOV, AVI, 3GP, M4V, MPEG-TS, FLV & OGV with an English audio track. Start with a 2-minute sample clip before running a full movie.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    videoPickerLauncher.launch(MediaInspector.SUPPORTED_VIDEO_MIME_TYPES)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("pick_video_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SaffronGold,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Browse Device Video Files")
                            }
                        }
                    }
                }

                item {
                    StudioDisclosureCard(
                        title = "Available Device Storage: ${WavUtils.formatFileSize(availableBytes)}",
                        body = "Temporary extracted audio chunks require ~10–15 MB per minute during processing and can be deleted right after MP4 export.",
                        icon = Icons.Outlined.SdStorage,
                        isWarning = availableBytes in 1L..500_000_000L
                    )
                }

                when (val state = uiState) {
                    ImportUiState.Idle -> {
                        item {
                            StudioDisclosureCard(
                                title = "No Video Selected",
                                body = "Tap 'Browse Device Video Files' above to inspect duration, resolution, audio channels, and storage requirements.",
                                icon = Icons.Outlined.VideoFile
                            )
                        }
                    }

                    ImportUiState.Inspecting -> {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("inspecting_video_card"),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(color = SaffronGold)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text("Inspecting Media Tracks...", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "Reading video resolution, codec, duration, and audio channels.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = SaffronGold
                                    )
                                }
                            }
                        }
                    }

                    is ImportUiState.Error -> {
                        item {
                            StudioDisclosureCard(
                                title = "Unable to Import Video",
                                body = state.message,
                                isWarning = true
                            )
                        }
                    }

                    is ImportUiState.Ready -> {
                        val movie = state.movie
                        val chunkCount = ((movie.durationMs + (state.chunkDurationSec * 1000L) - 1) /
                            (state.chunkDurationSec * 1000L)).coerceAtLeast(1)

                        item {
                            ExoVideoPreviewPlayer(
                                mediaUri = movie.uri,
                                mediaAccelSettings = mediaAccelSettings
                            )
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.5f)),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = movie.fileName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = SaffronGold
                                    )
                                    MetadataRow("Container", movie.containerFormat)
                                    MetadataRow("Duration", WavUtils.formatTimecode(movie.durationMs, includeMillis = true))
                                    MetadataRow("Resolution", "${movie.width} × ${movie.height}")
                                    MetadataRow("File Size", WavUtils.formatFileSize(movie.fileSizeBytes))
                                    MetadataRow(
                                        "Video Track",
                                        if (VideoExporter.isVideoMimeMp4MuxCompatible(movie.videoMime)) {
                                            "${movie.videoMime} (Fast Lossless MP4 Mux)"
                                        } else {
                                            "${movie.videoMime} (Auto-Convert to MP4)"
                                        }
                                    )
                                    MetadataRow(
                                        "Audio Track",
                                        "${movie.audioMime} • ${if (movie.audioChannels >= 2) "Stereo (${movie.audioChannels}ch)" else "Mono"} • ${movie.audioSampleRate} Hz"
                                    )
                                }
                            }
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(1.dp, ObsidianBorder),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Chunk Duration for AI Processing",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Splits the movie into $chunkCount manageable segment(s) so low-RAM Android Go phones can process 1–2 hour films smoothly with pause & resume.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(120, 180, 240, 300).forEach { sec ->
                                            val selected = state.chunkDurationSec == sec
                                            FilterChip(
                                                selected = selected,
                                                onClick = { viewModel.updateChunkDuration(sec) },
                                                label = { Text("${sec / 60} min") },
                                                colors = studioFilterChipColors(),
                                                border = studioFilterChipBorder(selected = selected),
                                                modifier = Modifier.testTag("chunk_chip_${sec}s")
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("import_subtitle_card"),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(
                                    1.dp,
                                    if (state.subtitleCueCount > 0) SaffronGold.copy(alpha = 0.65f) else ObsidianBorder
                                ),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.Subtitles, contentDescription = null, tint = SaffronGold)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "Optional .SRT / .VTT Subtitles",
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                Text(
                                                    text = if (state.subtitleCueCount > 0) {
                                                        "${state.subtitleFileName} • ${state.subtitleCueCount} dialogue lines ready"
                                                    } else {
                                                        "Gives 100% exact English text & skips slow speech recognition (2x faster)"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (state.subtitleCueCount > 0) SaffronGold else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (state.subtitleCueCount > 0) {
                                            IconButton(
                                                onClick = { viewModel.clearAttachedSubtitle() },
                                                modifier = Modifier.testTag("clear_import_subtitle_button")
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove subtitle file")
                                            }
                                        }
                                    }
                                    if (state.subtitleCueCount > 0) {
                                        Text(
                                            text = "Subtitle Language",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            SubtitleLanguage.all.forEach { lang ->
                                                val isSel = state.subtitleLanguage == lang
                                                FilterChip(
                                                    selected = isSel,
                                                    onClick = { viewModel.updateSubtitleLanguage(lang) },
                                                    label = { Text(SubtitleLanguage.displayLabel(lang)) },
                                                    colors = studioFilterChipColors(),
                                                    border = studioFilterChipBorder(selected = isSel),
                                                    modifier = Modifier.testTag("import_sub_lang_$lang")
                                                )
                                            }
                                        }
                                    }
                                    if (!subtitleError.isNullOrBlank()) {
                                        Text(
                                            text = subtitleError!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            subtitlePickerLauncher.launch(
                                                arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*")
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("pick_subtitle_import_button")
                                    ) {
                                        Icon(Icons.Filled.Subtitles, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            if (state.subtitleCueCount > 0) "Replace .SRT / .VTT File" else "Attach .SRT / .VTT Subtitle File"
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        videoPickerLauncher.launch(MediaInspector.SUPPORTED_VIDEO_MIME_TYPES)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                ) {
                                    Text("Change File")
                                }
                                Button(
                                    onClick = { viewModel.createProject(onProjectCreated) },
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .height(50.dp)
                                        .testTag("continue_dubbing_settings_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SaffronGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("Dubbing Settings")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
