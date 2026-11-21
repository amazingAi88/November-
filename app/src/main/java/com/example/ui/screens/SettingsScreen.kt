package com.example.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ai.LocalLlmModelManager
import com.example.ai.OnDeviceModelManager
import com.example.ai.SlangDictionaryManager
import com.example.data.local.DialogueStyle
import com.example.data.local.EngineMode
import com.example.data.local.ExportQuality
import com.example.data.local.MediaAccelMode
import com.example.media.WavUtils
import com.example.ui.components.StatusPill
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
import com.example.ui.viewmodel.SettingsViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSlangDictionary: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val tempSize by viewModel.tempCacheSizeBytes.collectAsStateWithLifecycle()
    val modelsStatus by viewModel.modelsStatus.collectAsStateWithLifecycle()
    val isManagingModels by viewModel.isManagingModels.collectAsStateWithLifecycle()
    val modelProgressText by viewModel.modelProgressText.collectAsStateWithLifecycle()

    val installedGgufModels by viewModel.installedGgufModels.collectAsStateWithLifecycle()
    val deviceRamGb by viewModel.deviceRamGb.collectAsStateWithLifecycle()
    val isManagingGguf by viewModel.isManagingGguf.collectAsStateWithLifecycle()
    val ggufProgress by viewModel.ggufProgress.collectAsStateWithLifecycle()
    val ggufProgressText by viewModel.ggufProgressText.collectAsStateWithLifecycle()
    val sampleTestOutput by viewModel.sampleTestOutput.collectAsStateWithLifecycle()
    val isTestingSample by viewModel.isTestingSample.collectAsStateWithLifecycle()
    val slangConfig by viewModel.slangConfig.collectAsStateWithLifecycle()
    val hardwareCodecs by viewModel.hardwareCodecs.collectAsStateWithLifecycle()

    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var testDialogueInput by remember { mutableStateOf("What the hell are you doing, man?") }

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomGguf(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshTempSize()
        viewModel.refreshModelsStatus()
        viewModel.refreshGgufModels()
        viewModel.refreshSlangDictionary()
    }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message!!)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = ObsidianNight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = "Studio Settings",
                subtitle = "Defaults, Offline LLM & Storage",
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
                            Text("Default Dubbing Engine", style = MaterialTheme.typography.titleMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = prefs.defaultEngineMode == EngineMode.ON_DEVICE_FREE,
                                    onClick = { viewModel.setEngineMode(EngineMode.ON_DEVICE_FREE) },
                                    label = { Text("On-Device Free (₹0)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    )
                                )
                                FilterChip(
                                    selected = prefs.defaultEngineMode == EngineMode.ON_DEVICE_LLM,
                                    onClick = { viewModel.setEngineMode(EngineMode.ON_DEVICE_LLM) },
                                    label = { Text("Local GGUF LLM (Uncensored)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    ),
                                    modifier = Modifier.testTag("default_engine_local_llm_chip")
                                )
                                FilterChip(
                                    selected = prefs.defaultEngineMode == EngineMode.GEMINI_CLOUD,
                                    onClick = { viewModel.setEngineMode(EngineMode.GEMINI_CLOUD) },
                                    label = { Text("Gemini Cloud AI") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    )
                                )
                            }

                            Text("Default Chunk Duration", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(120, 180, 240, 300).forEach { sec ->
                                    FilterChip(
                                        selected = prefs.defaultChunkDurationSec == sec,
                                        onClick = { viewModel.setChunkDurationSec(sec) },
                                        label = { Text("${sec / 60} min") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }

                            Text("Default Dialogue Style", style = MaterialTheme.typography.titleMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                DialogueStyle.all.forEach { style ->
                                    FilterChip(
                                        selected = prefs.defaultDialogueStyle == style,
                                        onClick = { viewModel.setDialogueStyle(style) },
                                        label = { Text(style) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Default Speech Speed", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    String.format(Locale.US, "%.2fx", prefs.defaultSpeechSpeed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SaffronGold
                                )
                            }
                            Slider(
                                value = prefs.defaultSpeechSpeed,
                                onValueChange = { viewModel.setSpeechSpeed(it) },
                                valueRange = 0.75f..1.35f,
                                colors = SliderDefaults.colors(thumbColor = SaffronGold, activeTrackColor = SaffronGold)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Preserve BGM by Default", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = prefs.defaultPreserveBgm,
                                    onCheckedChange = { viewModel.setPreserveBgm(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold)
                                )
                            }

                            Text("Default Export Quality", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ExportQuality.all.forEach { q ->
                                    FilterChip(
                                        selected = prefs.defaultExportQuality == q,
                                        onClick = { viewModel.setExportQuality(q) },
                                        label = { Text(q) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Media3 Hardware Acceleration Card (Transformer & ExoPlayer)
                item {
                    val codecs = hardwareCodecs
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_media3_accel_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Speed,
                                        contentDescription = null,
                                        tint = ElectricCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "Media3 Hardware Acceleration",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "Transformer transcode speed & ExoPlayer async queueing",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                StatusPill(
                                    label = if (codecs?.hasHardwareAcceleration == true) "HW READY" else "AUTO",
                                    tone = if (codecs?.hasHardwareAcceleration == true) "SUCCESS" else "ACTIVE"
                                )
                            }

                            if (codecs != null) {
                                Surface(
                                    color = ObsidianElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ObsidianBorder)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Detected Hardware Codecs: ${codecs.hwVideoDecoderCount} HW Decoders • ${codecs.hwVideoEncoderCount} HW Encoders",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ElectricCyan
                                        )
                                        if (!codecs.primaryHwVideoDecoder.isNullOrBlank() || !codecs.primaryHwVideoEncoder.isNullOrBlank()) {
                                            Text(
                                                text = "Decoder: ${codecs.primaryHwVideoDecoder ?: "N/A"} • Encoder: ${codecs.primaryHwVideoEncoder ?: "N/A"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Text("Codec Acceleration Profile", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MediaAccelMode.all.forEach { mode ->
                                    FilterChip(
                                        selected = prefs.mediaAccelMode == mode,
                                        onClick = { viewModel.setMediaAccelMode(mode) },
                                        label = { Text(MediaAccelMode.displayLabel(mode)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        ),
                                        modifier = Modifier.testTag("settings_accel_mode_$mode")
                                    )
                                }
                            }
                            Text(
                                text = when (prefs.mediaAccelMode) {
                                    MediaAccelMode.HARDWARE_PRIORITY ->
                                        "Forces hardware-accelerated MediaCodec encoders/decoders first with max priority clock for fastest MP4 exports."
                                    MediaAccelMode.SOFTWARE_SAFE ->
                                        "Prioritizes software codecs to bypass buggy OEM hardware encoders when a long video stalls or renders green frames."
                                    else ->
                                        "Uses Android Media3 default codec negotiation."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Async MediaCodec Queueing", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Enables ExoPlayer async buffers & Transformer dynamic scheduling",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.mediaAsyncCodecQueueing,
                                    onCheckedChange = { viewModel.setMediaAsyncCodecQueueing(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                                    modifier = Modifier.testTag("settings_async_codec_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Max Hardware Operating Rate", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Boosts decoder/encoder clock rate hints during Transformer export",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.mediaHighOperatingRate,
                                    onCheckedChange = { viewModel.setMediaHighOperatingRate(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                                    modifier = Modifier.testTag("settings_operating_rate_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Auto Software Codec Fallback", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Automatically retries with Software Safe codecs if hardware stalls",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.mediaAllowCodecFallback,
                                    onCheckedChange = { viewModel.setMediaAllowCodecFallback(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                                    modifier = Modifier.testTag("settings_codec_fallback_switch")
                                )
                            }

                            Text("Transformer Muxer Stall Guard Timeout", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(15 to "15s", 30 to "30s", 60 to "60s", 0 to "No Limit").forEach { (sec, label) ->
                                    FilterChip(
                                        selected = prefs.mediaTransformerTimeoutSec == sec,
                                        onClick = { viewModel.setMediaTransformerTimeoutSec(sec) },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        ),
                                        modifier = Modifier.testTag("settings_muxer_timeout_$sec")
                                    )
                                }
                            }
                        }
                    }
                }

                // Local Open-Source GGUF LLM Card (Uncensored & 100% Offline)
                item {
                    val hasValidGguf = installedGgufModels.any { it.isValidGguf }
                    val resolvedActiveName = remember(installedGgufModels, prefs.activeGgufModelFileName) {
                        installedGgufModels.firstOrNull { it.isValidGguf && it.fileName == prefs.activeGgufModelFileName }?.fileName
                            ?: installedGgufModels.firstOrNull { it.isValidGguf }?.fileName
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_gguf_llm_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Memory,
                                        contentDescription = null,
                                        tint = ElectricCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "Local Open-Source GGUF LLM",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "100% offline, unfiltered scene-context Hindi translation",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                StatusPill(
                                    label = if (hasValidGguf) "READY" else "SETUP NEEDED",
                                    tone = if (hasValidGguf) "SUCCESS" else "WARN"
                                )
                            }

                            Surface(
                                color = ObsidianElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Phone Memory: ${String.format(Locale.US, "%.1f", deviceRamGb)} GB RAM",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan
                                    )
                                    Text(
                                        text = if (deviceRamGb >= 5.5f) "1.5B Model Supported" else "0.5B Lite Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "Runs Qwen 2.5, Gemma, or any custom .gguf file directly on your phone CPU. Understands Bollywood/OTT slang and raw adult dialogue context without cloud censorship or API quotas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (ggufProgress != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(
                                        progress = { ggufProgress!!.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .testTag("gguf_download_progress"),
                                        color = ElectricCyan,
                                        trackColor = ObsidianSurfaceVariant
                                    )
                                    if (!ggufProgressText.isNullOrBlank()) {
                                        Text(
                                            text = ggufProgressText!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ElectricCyan
                                        )
                                    }
                                }
                            }

                            // Curated Open-Source Presets
                            LocalLlmModelManager.PRESETS.forEach { preset ->
                                val installed = installedGgufModels.firstOrNull { it.fileName == preset.fileName && it.isValidGguf }
                                val isActive = resolvedActiveName == preset.fileName
                                val recommendedForPhone = (deviceRamGb >= 5.5f && preset.minRamGb >= 6) ||
                                    (deviceRamGb < 5.5f && preset.minRamGb <= 4)

                                Surface(
                                    color = ObsidianElevated,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isActive) EmeraldSuccess.copy(alpha = 0.65f) else ObsidianBorder
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
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(preset.displayName, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    text = "~${preset.approxSizeMb} MB • Min ${preset.minRamGb}GB RAM",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SaffronGold
                                                )
                                            }
                                            if (installed != null) {
                                                StatusPill(
                                                    label = if (isActive) "ACTIVE" else "INSTALLED",
                                                    tone = if (isActive) "SUCCESS" else "ACTIVE"
                                                )
                                            } else if (recommendedForPhone) {
                                                StatusPill(label = "RECOMMENDED", tone = "ACTIVE")
                                            }
                                        }
                                        Text(
                                            text = preset.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (installed == null) {
                                                Button(
                                                    onClick = { viewModel.downloadGgufPreset(preset) },
                                                    enabled = !isManagingGguf,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp)
                                                        .testTag("download_gguf_${preset.id}"),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = ElectricCyan,
                                                        contentColor = ObsidianNight
                                                    )
                                                ) {
                                                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Download (~${preset.approxSizeMb} MB)")
                                                }
                                            } else {
                                                if (!isActive) {
                                                    OutlinedButton(
                                                        onClick = { viewModel.selectActiveGguf(preset.fileName) },
                                                        enabled = !isManagingGguf,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(48.dp)
                                                    ) {
                                                        Text("Use This Model")
                                                    }
                                                } else {
                                                    Surface(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(48.dp),
                                                        color = EmeraldContainer.copy(alpha = 0.35f),
                                                        shape = MaterialTheme.shapes.small,
                                                        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.5f))
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxSize(),
                                                            horizontalArrangement = Arrangement.Center,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.Check,
                                                                contentDescription = null,
                                                                tint = EmeraldSuccess,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                "Active Offline LLM",
                                                                style = MaterialTheme.typography.labelLarge,
                                                                color = EmeraldSuccess
                                                            )
                                                        }
                                                    }
                                                }
                                                IconButton(
                                                    onClick = { viewModel.deleteGgufModel(preset.fileName) },
                                                    enabled = !isManagingGguf,
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.DeleteOutline,
                                                        contentDescription = "Delete ${preset.fileName}",
                                                        tint = ErrorCrimson
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Custom GGUF Importer (e.g. Gemma / Llama / Mistral / Uncensored GGUF from device storage)
                            OutlinedButton(
                                onClick = { ggufPickerLauncher.launch(arrayOf("*/*")) },
                                enabled = !isManagingGguf,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("import_custom_gguf_button")
                            ) {
                                Icon(Icons.Filled.FileOpen, contentDescription = null, tint = SaffronGold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Custom .gguf File (Gemma / Qwen / Llama)")
                            }

                            // Custom imported .gguf models not part of built-in presets
                            val customModels = installedGgufModels.filter { model ->
                                LocalLlmModelManager.PRESETS.none { it.fileName == model.fileName }
                            }
                            if (customModels.isNotEmpty()) {
                                HorizontalDivider(color = ObsidianBorder)
                                Text("Custom Imported Models", style = MaterialTheme.typography.labelLarge)
                                customModels.forEach { custom ->
                                    val isActive = resolvedActiveName == custom.fileName
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(custom.fileName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = "${WavUtils.formatFileSize(custom.sizeBytes)} • ${if (custom.isValidGguf) "Valid GGUF" else "Invalid GGUF"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (custom.isValidGguf) MaterialTheme.colorScheme.onSurfaceVariant else ErrorCrimson
                                            )
                                        }
                                        if (custom.isValidGguf) {
                                            FilterChip(
                                                selected = isActive,
                                                onClick = { viewModel.selectActiveGguf(custom.fileName) },
                                                label = { Text(if (isActive) "Active" else "Select") },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = EmeraldContainer,
                                                    selectedLabelColor = EmeraldSuccess
                                                )
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteGgufModel(custom.fileName) },
                                            enabled = !isManagingGguf,
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete ${custom.fileName}",
                                                tint = ErrorCrimson
                                            )
                                        }
                                    }
                                }
                            }

                            // Quick Live Test Box when at least one valid GGUF is ready
                            if (hasValidGguf) {
                                HorizontalDivider(color = ObsidianBorder)
                                Text("Test GGUF Hindi Dubbing (${prefs.defaultDialogueStyle})", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = testDialogueInput,
                                    onValueChange = { testDialogueInput = it },
                                    label = { Text("Sample English Movie Line") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("test_gguf_input")
                                )
                                Button(
                                    onClick = {
                                        viewModel.testGgufSample(testDialogueInput, prefs.defaultDialogueStyle)
                                    },
                                    enabled = !isTestingSample && testDialogueInput.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("test_gguf_translate_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SaffronGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    if (isTestingSample) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Running Local GGUF Model...")
                                    } else {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test Translate Offline")
                                    }
                                }

                                sampleTestOutput?.let { res ->
                                    Surface(
                                        color = ObsidianElevated,
                                        shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.55f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = res.hindiText,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = EmeraldSuccess
                                            )
                                            Text(
                                                text = String.format(Locale.US, "Speed: %.1f tokens/sec • 100%% Offline", res.tokensPerSecond),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Local JSON Slang & Context Dictionary Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slang_dictionary_settings_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.55f)),
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
                                            "Slang & Context JSON Dictionary",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${slangConfig.rules.count { it.enabled }} active / ${slangConfig.rules.size} total rules in ${SlangDictionaryManager.DICTIONARY_FILE_NAME}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = slangConfig.enabled,
                                    onCheckedChange = { viewModel.setSlangDictionaryEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold)
                                )
                            }
                            Text(
                                text = "Defines slang, colloquialisms & scene-context Hindi translations in private internal storage to override censored ML Kit words and enforce authentic terms in LLMs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenSlangDictionary,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_slang_dictionary_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SaffronGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("Open JSON Dictionary")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.resetSlangDictionary() },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Reset")
                                }
                            }
                        }
                    }
                }

                // On-Device Free Offline Models Card (~70 MB)
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
                            Text("On-Device Offline AI Models (~70 MB)", style = MaterialTheme.typography.titleMedium)
                            val st = modelsStatus
                            if (st != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Vosk English STT (~40 MB)", style = MaterialTheme.typography.bodySmall)
                                    StatusPill(
                                        label = if (st.voskDownloaded) "READY (${WavUtils.formatFileSize(st.voskSizeBytes)})" else "MISSING",
                                        tone = if (st.voskDownloaded) "SUCCESS" else "WARN"
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ML Kit EN→HI Translator (~30 MB)", style = MaterialTheme.typography.bodySmall)
                                    StatusPill(
                                        label = if (st.mlKitHindiDownloaded) "READY" else "MISSING",
                                        tone = if (st.mlKitHindiDownloaded) "SUCCESS" else "WARN"
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Android Hindi TTS Voice", style = MaterialTheme.typography.bodySmall)
                                    StatusPill(
                                        label = if (st.androidHindiTtsReady) "READY (${st.ttsVoicesCount})" else "SETUP NEEDED",
                                        tone = if (st.androidHindiTtsReady) "SUCCESS" else "WARN"
                                    )
                                }
                                Text(
                                    text = st.ttsNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!modelProgressText.isNullOrBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(color = SaffronGold)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(modelProgressText!!, style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.downloadOnDeviceModels() },
                                    enabled = !isManagingModels,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("download_on_device_models_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SaffronGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download / Check")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.deleteOnDeviceModels() },
                                    enabled = !isManagingModels && (modelsStatus?.voskDownloaded == true || modelsStatus?.mlKitHindiDownloaded == true),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }

                            if (modelsStatus?.androidHindiTtsReady == false) {
                                OutlinedButton(
                                    onClick = {
                                        OnDeviceModelManager.openTtsSettings(context)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text("Install Android Hindi TTS Voice Pack")
                                }
                            }
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
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Temporary Chunk Storage", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Currently using ${WavUtils.formatFileSize(tempSize)} across projects.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.cleanAllTempFiles() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("settings_clean_all_temp_button")
                            ) {
                                Icon(Icons.Filled.CleaningServices, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clean All")
                            }
                        }
                    }
                }

                item {
                    StudioDisclosureCard(
                        title = "Security: Firebase AI Logic + App Check",
                        body = "No secret Gemini API keys are stored inside the APK. Requests are authenticated through Firebase AI Logic and Firebase App Check.",
                        icon = Icons.Outlined.Security
                    )
                }

                item {
                    StudioDisclosureCard(
                        title = stringResource(R.string.privacy_notice_title),
                        body = stringResource(R.string.privacy_notice_body),
                        icon = Icons.Outlined.Lock
                    )
                }

                item {
                    StudioDisclosureCard(
                        title = stringResource(R.string.quota_notice_title),
                        body = stringResource(R.string.quota_notice_body),
                        isWarning = true
                    )
                }
            }
        }
    }
}
