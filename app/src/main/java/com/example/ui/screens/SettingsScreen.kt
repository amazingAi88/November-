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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ai.OnDeviceModelManager
import com.example.data.local.DialogueStyle
import com.example.data.local.EngineMode
import com.example.data.local.ExportQuality
import com.example.media.WavUtils
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioDisclosureCard
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SaffronContainer
import com.example.ui.theme.SaffronGold
import com.example.ui.viewmodel.SettingsViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val tempSize by viewModel.tempCacheSizeBytes.collectAsStateWithLifecycle()
    val modelsStatus by viewModel.modelsStatus.collectAsStateWithLifecycle()
    val isManagingModels by viewModel.isManagingModels.collectAsStateWithLifecycle()
    val modelProgressText by viewModel.modelProgressText.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshTempSize()
        viewModel.refreshModelsStatus()
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
                subtitle = "Defaults, Storage & API Transparency",
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    enabled = !isManagingModels && (modelsStatus?.voskDownloaded == true || modelsStatus?.mlKitHindiDownloaded == true)
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
                                    modifier = Modifier.fillMaxWidth()
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
                                modifier = Modifier.testTag("settings_clean_all_temp_button")
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
