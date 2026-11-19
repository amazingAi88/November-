package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.OnDeviceModelManager
import com.example.data.local.ChunkEntity
import com.example.data.local.ChunkStatus
import com.example.data.local.EngineMode
import com.example.data.local.ProcessingStage
import com.example.data.local.ProjectStatus
import com.example.data.local.SegmentStatus
import com.example.media.WavUtils
import com.example.ui.components.StatusPill
import com.example.ui.components.StepHeaderRow
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.SaffronContainer
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.ProjectStudioViewModel

@Composable
fun ProcessingScreen(
    viewModel: ProjectStudioViewModel,
    onBack: () -> Unit,
    onOpenAudioSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenExport: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val chunks by viewModel.chunks.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startOrResumeDubbing()
    }

    fun startWithOptionalNotificationPermission() {
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
    }

    LaunchedEffect(project?.status) {
        if (project?.status == ProjectStatus.CONFIGURING) {
            startWithOptionalNotificationPermission()
        }
    }

    Scaffold(
        containerColor = ObsidianNight,
        topBar = {
            StudioTopBar(
                title = "Dubbing Pipeline",
                subtitle = project?.title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = onOpenAudioSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "Audio & Dubbing Settings", tint = SaffronGold)
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
            val currentProject = project ?: return@Box
            val completedChunks = chunks.count { it.status == ChunkStatus.MIXED }
            val pendingChunks = chunks.count { it.status != ChunkStatus.MIXED && it.status != ChunkStatus.FAILED }
            val failedChunks = chunks.count { it.status == ChunkStatus.FAILED }

            val speechSegs = segments.filter { it.audioType == "SPEECH" }
            val completedSegs = speechSegs.count { it.status == SegmentStatus.TTS_DONE }
            val failedSegs = speechSegs.count { it.status == SegmentStatus.FAILED }
            val pendingSegs = (speechSegs.size - completedSegs - failedSegs).coerceAtLeast(0)

            val isRunning = currentProject.status == ProjectStatus.PROCESSING
            val isReady = currentProject.status == ProjectStatus.READY_FOR_REVIEW ||
                currentProject.status == ProjectStatus.COMPLETED

            val activeChunkId = if (isRunning) {
                chunks.firstOrNull {
                    it.status == ChunkStatus.EXTRACTED ||
                        it.status == ChunkStatus.TRANSCRIBED ||
                        it.status == ChunkStatus.TRANSLATED ||
                        it.status == ChunkStatus.TTS_DONE
                }?.id ?: chunks.firstOrNull { it.status == ChunkStatus.PENDING }?.id
            } else null

            val isInitialIndeterminate = isRunning && (
                currentProject.progressPercent <= 1 ||
                    currentProject.currentStage.contains("Preparing", ignoreCase = true) ||
                    currentProject.currentStage.contains("Downloading", ignoreCase = true) ||
                    currentProject.currentStage.contains("Extracting offline", ignoreCase = true) ||
                    currentProject.currentStage.contains("Checking ML Kit", ignoreCase = true)
                )

            val animatedProgress by animateFloatAsState(
                targetValue = (currentProject.progressPercent / 100f).coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 450),
                label = "pipeline_progress"
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StepHeaderRow(stepNumber = 4, stepName = "AI Pipeline")
                }

                // Stage & Progress Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isRunning) SaffronGold.copy(alpha = 0.55f) else ObsidianBorder
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${currentProject.progressPercent}%",
                                        style = MaterialTheme.typography.displaySmall,
                                        color = if (isReady) EmeraldSuccess else SaffronGold
                                    )
                                    if (isRunning) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        ProcessingWaveformAnimation(
                                            modifier = Modifier.testTag("processing_active_animation")
                                        )
                                    }
                                }
                                StatusPill(
                                    label = currentProject.status.replace('_', ' '),
                                    tone = when (currentProject.status) {
                                        ProjectStatus.READY_FOR_REVIEW, ProjectStatus.COMPLETED -> "SUCCESS"
                                        ProjectStatus.PROCESSING -> "ACTIVE"
                                        ProjectStatus.ERROR -> "ERROR"
                                        ProjectStatus.PAUSED -> "WARN"
                                        else -> "NEUTRAL"
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ElectricCyan
                                    )
                                }
                                Text(
                                    text = currentProject.currentStage,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            if (isInitialIndeterminate) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .testTag("processing_indeterminate_progress"),
                                    color = SaffronGold,
                                    trackColor = ObsidianSurfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .testTag("processing_determinate_progress"),
                                    color = if (isReady) EmeraldSuccess else SaffronGold,
                                    trackColor = ObsidianSurfaceVariant
                                )
                            }

                            // Continuous indeterminate activity strip whenever a stage like Transcription is actively running
                            AnimatedVisibility(visible = isRunning && !isInitialIndeterminate) {
                                Column {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .testTag("processing_stage_activity_bar"),
                                        color = ElectricCyan,
                                        trackColor = ObsidianElevated
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            PipelineStageStepper(
                                currentStage = currentProject.currentStage,
                                isRunning = isRunning,
                                isReady = isReady
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            val etaText = when {
                                isReady -> "All chunks processed."
                                currentProject.estimatedRemainingMs != null ->
                                    "Est. remaining: ~${WavUtils.formatTimecode(currentProject.estimatedRemainingMs)}"
                                isRunning -> "Processing chunk — calculating remaining time after first completed chunk..."
                                else -> "Paused — resume anytime from last saved checkpoint."
                            }
                            Text(
                                text = etaText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            StatusPill(
                                label = if (currentProject.engineMode == EngineMode.ON_DEVICE_FREE) {
                                    "ON-DEVICE FREE (₹0)"
                                } else {
                                    "GEMINI CLOUD"
                                },
                                tone = "ACTIVE"
                            )

                            if (!currentProject.lastError.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = currentProject.lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ErrorCrimson
                                )
                                if (currentProject.lastError.contains("Text-to-Speech", ignoreCase = true) ||
                                    currentProject.lastError.contains("TTS", ignoreCase = true)
                                ) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            OnDeviceModelManager.openTtsSettings(context)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Install / Check Android Hindi TTS Voice")
                                    }
                                }
                            }
                        }
                    }
                }

                // Completed vs Pending vs Failed metrics
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricTile(
                            label = "Chunks Done",
                            value = "$completedChunks/${chunks.size.coerceAtLeast(1)}",
                            accent = EmeraldSuccess,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Lines Dubbed",
                            value = "$completedSegs",
                            accent = SaffronGold,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Pending",
                            value = "$pendingSegs",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Failed",
                            value = "${failedSegs + failedChunks}",
                            accent = if (failedSegs + failedChunks > 0) ErrorCrimson else WarningAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Controls Row
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isRunning) {
                                Button(
                                    onClick = { viewModel.pauseDubbing() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("pause_dubbing_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = WarningAmber,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.Pause, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pause")
                                }
                            } else {
                                Button(
                                    onClick = { startWithOptionalNotificationPermission() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("resume_dubbing_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SaffronGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isReady) "Re-check / Finish" else "Start / Resume")
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.cancelDubbing() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("cancel_dubbing_button")
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cancel")
                            }
                        }

                        if (failedSegs > 0 || failedChunks > 0) {
                            OutlinedButton(
                                onClick = { viewModel.retryFailedSegments() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("retry_failed_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = ErrorCrimson)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry Failed Segments ($failedSegs)")
                            }
                        }

                        if (segments.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenReview,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("open_review_button")
                                ) {
                                    Icon(Icons.Filled.EditNote, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Review & Edit")
                                }

                                Button(
                                    onClick = onOpenExport,
                                    enabled = completedChunks > 0 && pendingChunks == 0,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("open_export_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldSuccess,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.MovieCreation, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Export MP4")
                                }
                            }
                        }
                    }
                }

                // Privacy & Quota / Engine reminder
                item {
                    if (currentProject.engineMode == EngineMode.ON_DEVICE_FREE) {
                        StudioDisclosureCard(
                            title = stringResource(R.string.privacy_notice_title),
                            body = stringResource(R.string.privacy_notice_body),
                            isWarning = false
                        )
                    } else {
                        StudioDisclosureCard(
                            title = stringResource(R.string.quota_notice_title),
                            body = stringResource(R.string.quota_notice_body),
                            isWarning = true
                        )
                    }
                }

                // Chunk Progress Timeline
                if (chunks.isNotEmpty()) {
                    item {
                        Text("Chunk Checkpoints", style = MaterialTheme.typography.titleLarge)
                    }
                    items(chunks, key = { it.id }) { chunk ->
                        ChunkRowCard(
                            chunk = chunk,
                            isActivelyProcessing = isRunning && chunk.id == activeChunkId
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingWaveformAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform_pulse")
    val bars = listOf(0, 150, 300, 100).mapIndexed { idx, delayMs ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, delayMillis = delayMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$idx"
        )
    }
    Row(
        modifier = modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEachIndexed { idx, frac ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((8 + (14 * frac.value)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (idx % 2 == 0) SaffronGold else ElectricCyan)
            )
        }
    }
}

@Composable
private fun PipelineStageStepper(
    currentStage: String,
    isRunning: Boolean,
    isReady: Boolean
) {
    val steps = listOf(
        "Extract" to ProcessingStage.EXTRACTING,
        "Transcribe" to ProcessingStage.TRANSCRIBING,
        "Translate" to ProcessingStage.TRANSLATING,
        "Voice" to ProcessingStage.GENERATING_TTS,
        "Mix" to ProcessingStage.MIXING
    )
    val activeIdx = when {
        isReady -> steps.size
        currentStage.contains("Mix", ignoreCase = true) ||
            currentStage.contains("Background Music", ignoreCase = true) -> 4
        currentStage.contains("Generating Hindi Voices", ignoreCase = true) ||
            currentStage.contains("TTS", ignoreCase = true) -> 3
        currentStage.contains("Translating", ignoreCase = true) -> 2
        currentStage.contains("Transcribing", ignoreCase = true) -> 1
        currentStage.contains("Extracting Audio", ignoreCase = true) -> 0
        else -> -1
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        steps.forEachIndexed { idx, (label, _) ->
            val isCompleted = isReady || (activeIdx > idx)
            val isCurrent = isRunning && activeIdx == idx
            val bgColor = when {
                isCompleted -> EmeraldContainer
                isCurrent -> SaffronContainer
                else -> ObsidianElevated
            }
            val fgColor = when {
                isCompleted -> EmeraldSuccess
                isCurrent -> SaffronGold
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                modifier = Modifier.weight(1f),
                color = bgColor,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, fgColor.copy(alpha = if (isCurrent) 0.7f else 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SaffronGold)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    } else if (isCompleted) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = fgColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = ObsidianSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChunkRowCard(
    chunk: ChunkEntity,
    isActivelyProcessing: Boolean = false
) {
    val tone = when (chunk.status) {
        ChunkStatus.MIXED -> "SUCCESS"
        ChunkStatus.FAILED -> "ERROR"
        ChunkStatus.PENDING -> if (isActivelyProcessing) "ACTIVE" else "NEUTRAL"
        else -> "ACTIVE"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActivelyProcessing) SaffronGold.copy(alpha = 0.6f) else ObsidianBorder
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Chunk ${chunk.chunkIndex + 1} • ${WavUtils.formatTimecode(chunk.startMs)} – ${WavUtils.formatTimecode(chunk.endMs)}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (chunk.processingTimeMs > 0L) {
                        Text(
                            text = "Completed in ${chunk.processingTimeMs / 1000}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (isActivelyProcessing) {
                        Text(
                            text = "Currently extracting & transcribing...",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaffronGold
                        )
                    }
                    if (!chunk.errorMessage.isNullOrBlank()) {
                        Text(
                            text = chunk.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorCrimson
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isActivelyProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SaffronGold
                        )
                    }
                    StatusPill(label = chunk.status, tone = tone)
                }
            }
            if (isActivelyProcessing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = SaffronGold,
                    trackColor = ObsidianSurfaceVariant
                )
            }
        }
    }
}
