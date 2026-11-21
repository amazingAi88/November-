package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ExportQuality
import com.example.data.local.MediaAccelMode
import com.example.media.WavUtils
import com.example.ui.components.ExoVideoPreviewPlayer
import com.example.ui.components.StatusPill
import com.example.ui.components.StepHeaderRow
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioSwitch
import com.example.ui.components.StudioTopBar
import com.example.ui.components.studioFilterChipBorder
import com.example.ui.components.studioFilterChipColors
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.SaffronGold
import com.example.ui.viewmodel.ExportUiState
import com.example.ui.viewmodel.ProjectStudioViewModel
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
    viewModel: ProjectStudioViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val tempBytes by viewModel.tempBytes.collectAsStateWithLifecycle()
    val mediaAccelSettings by viewModel.mediaAccelSettings.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val safCreateDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        if (uri != null) {
            viewModel.saveExportToSafUri(uri)
        }
    }

    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner!!)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        containerColor = ObsidianNight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = "Export Dubbed MP4",
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
            val existingExportFile = currentProject.exportPath
                ?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 0L }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StepHeaderRow(stepNumber = 5, stepName = "Export & Share")
                }

                // Video Quality Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Select Output Video Quality", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Original Quality copies the source video stream untouched (fastest, zero visual loss). 1080p/720p/480p re-encode via Media3 Transformer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val labels = mapOf(
                                    ExportQuality.ORIGINAL to "Original (Lossless Video)",
                                    ExportQuality.HD_1080P to "1080p HD",
                                    ExportQuality.HD_720P to "720p HD",
                                    ExportQuality.SD_480P to "480p SD"
                                )
                                ExportQuality.all.forEach { q ->
                                    val isSelected = currentProject.exportQuality == q
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.updateExportQuality(q) },
                                        label = { Text(labels[q] ?: q) },
                                        colors = studioFilterChipColors(),
                                        border = studioFilterChipBorder(selected = isSelected),
                                        modifier = Modifier.testTag("export_quality_$q")
                                    )
                                }
                            }
                        }
                    }
                }

                // Media3 Transformer & ExoPlayer Hardware Acceleration Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_media3_accel_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    Icon(Icons.Filled.Speed, contentDescription = null, tint = ElectricCyan)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Media3 Hardware Acceleration", style = MaterialTheme.typography.titleMedium)
                                }
                                StatusPill(
                                    label = MediaAccelMode.displayLabel(mediaAccelSettings.mode),
                                    tone = if (mediaAccelSettings.mode == MediaAccelMode.HARDWARE_PRIORITY) "ACTIVE" else "NEUTRAL"
                                )
                            }
                            Text(
                                text = mediaAccelSettings.summaryBadge(),
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MediaAccelMode.all.forEach { mode ->
                                    val isSelected = mediaAccelSettings.mode == mode
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.updateMediaAccelMode(mode) },
                                        label = { Text(MediaAccelMode.displayLabel(mode)) },
                                        colors = studioFilterChipColors(),
                                        border = studioFilterChipBorder(selected = isSelected),
                                        modifier = Modifier.testTag("export_accel_mode_$mode")
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Async MediaCodec Queueing", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Prevents dropped frames & preview stutters during heavy dubbing tasks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StudioSwitch(
                                    checked = mediaAccelSettings.asyncCodecQueueing,
                                    onCheckedChange = { viewModel.updateMediaAsyncQueueing(it) },
                                    modifier = Modifier.testTag("export_async_codec_switch")
                                )
                            }
                        }
                    }
                }

                // Export Action & Progress
                item {
                    val isExporting = exportState is ExportUiState.Exporting
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.exportFinalMp4() },
                            enabled = !isExporting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("export_final_mp4_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SaffronGold,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Filled.MovieCreation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isExporting) "Rendering Hindi Dubbed MP4..."
                                else if (existingExportFile != null) "Re-Export Hindi MP4"
                                else "Export Final Hindi MP4"
                            )
                        }

                        if (exportState is ExportUiState.Exporting) {
                            val exp = exportState as ExportUiState.Exporting
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(exp.stage, style = MaterialTheme.typography.titleSmall)
                                        Text("${exp.progress}%", style = MaterialTheme.typography.labelMedium, color = SaffronGold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { exp.progress / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        color = SaffronGold,
                                        trackColor = ObsidianSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (exportState is ExportUiState.Error) {
                            StudioDisclosureCard(
                                title = "Export Failed",
                                body = (exportState as ExportUiState.Error).message,
                                isWarning = true
                            )
                        }
                    }
                }

                // Finished MP4 Playback, File Size, Downloads & Share
                if (existingExportFile != null) {
                    val actualSize = existingExportFile.length()
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.55f)),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Exported Hindi Dubbed MP4", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            existingExportFile.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusPill(
                                        label = WavUtils.formatFileSize(actualSize),
                                        tone = "SUCCESS"
                                    )
                                }

                                ExoVideoPreviewPlayer(
                                    mediaUri = Uri.fromFile(existingExportFile),
                                    mediaAccelSettings = mediaAccelSettings
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.saveExportToDownloads { fileName ->
                                                safCreateDocLauncher.launch(fileName)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("save_to_downloads_button"),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = EmeraldSuccess,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Save Gallery")
                                    }

                                    Button(
                                        onClick = {
                                            val shareIntent = viewModel.buildShareIntent()
                                            if (shareIntent != null) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent.createChooser(shareIntent, "Share Hindi Dubbed MP4")
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("share_mp4_button"),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        )
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share MP4")
                                    }
                                }
                            }
                        }
                    }

                    // Optional Temporary Audio Cleanup
                    if (tempBytes > 0L) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                                border = BorderStroke(1.dp, ObsidianBorder),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Temporary Audio Chunks: ${WavUtils.formatFileSize(tempBytes)}",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            "Your exported MP4 is saved separately. Delete temp WAV chunks to reclaim storage.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.cleanTempFiles() },
                                        modifier = Modifier.testTag("clean_temp_files_button")
                                    ) {
                                        Icon(Icons.Filled.CleaningServices, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Clean")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
