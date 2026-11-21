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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.DisposableEffect
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
import com.example.R
import com.example.ai.HindiVoicePresets
import com.example.ai.OnDeviceModelManager
import com.example.ai.VoicePreset
import com.example.data.local.DialogueStyle
import com.example.data.local.DubbingSpeedMode
import com.example.data.local.EngineMode
import com.example.data.local.SpeakerEntity
import com.example.data.local.SttEngineMode
import com.example.data.local.SubtitleLanguage
import com.example.media.AudioPlaybackState
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
import com.example.ui.viewmodel.ProjectStudioViewModel
import com.example.ui.viewmodel.VoicePreviewState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DubbingSettingsScreen(
    viewModel: ProjectStudioViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSlangDictionary: () -> Unit = {},
    onNextAudioSettings: () -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val voicePreviewState by viewModel.voicePreviewState.collectAsStateWithLifecycle()
    val activeGgufModelName by viewModel.activeGgufModelName.collectAsStateWithLifecycle()
    val dubbingSpeedMode by viewModel.dubbingSpeedMode.collectAsStateWithLifecycle()
    val sttEngineMode by viewModel.sttEngineMode.collectAsStateWithLifecycle()
    val sttVocalIsolationEnabled by viewModel.sttVocalIsolationEnabled.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSubtitleFile(uri)
        }
    }

    var genderFilter by remember { mutableStateOf("ALL") }
    var auditionText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshActiveGgufModel()
    }

    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner!!)
            viewModel.clearBanner()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopVoicePreview() }
    }

    Scaffold(
        containerColor = ObsidianNight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                title = "Dubbing Settings",
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
            val currentProject = project
            if (currentProject == null) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SaffronGold)
                    Text("Loading project...")
                }
                return@Box
            }

            val filteredVoices = remember(genderFilter) {
                when (genderFilter) {
                    "MALE", "FEMALE", "NEUTRAL" -> HindiVoicePresets.all.filter { it.gender == genderFilter }
                    else -> HindiVoicePresets.all
                }
            }
            val activePreset = HindiVoicePresets.all.find { it.id == currentProject.defaultVoicePreset }
                ?: HindiVoicePresets.defaultForGender(currentProject.defaultVoiceGender)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StepHeaderRow(stepNumber = 2, stepName = "Language, Engine & Voices")
                }

                // Engine Mode Selector Card (100% On-Device Free vs Optional Gemini Cloud)
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Dubbing Processing Engine", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = currentProject.engineMode == EngineMode.ON_DEVICE_FREE,
                                    onClick = { viewModel.updateDubbingSettings(engineMode = EngineMode.ON_DEVICE_FREE) },
                                    label = { Text("On-Device Free (Phone Only ₹0)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    ),
                                    modifier = Modifier.testTag("engine_on_device_chip")
                                )
                                FilterChip(
                                    selected = currentProject.engineMode == EngineMode.ON_DEVICE_LLM,
                                    onClick = {
                                        viewModel.refreshActiveGgufModel()
                                        viewModel.updateDubbingSettings(engineMode = EngineMode.ON_DEVICE_LLM)
                                    },
                                    label = { Text("Local GGUF LLM (Uncensored Offline ₹0)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    ),
                                    modifier = Modifier.testTag("engine_local_llm_chip")
                                )
                                FilterChip(
                                    selected = currentProject.engineMode == EngineMode.GEMINI_CLOUD,
                                    onClick = { viewModel.updateDubbingSettings(engineMode = EngineMode.GEMINI_CLOUD) },
                                    label = { Text("Gemini Cloud AI (Optional)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SaffronContainer,
                                        selectedLabelColor = SaffronGold
                                    ),
                                    modifier = Modifier.testTag("engine_gemini_chip")
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when (currentProject.engineMode) {
                                    EngineMode.ON_DEVICE_FREE ->
                                        "Runs completely inside your phone using Vosk Offline STT (~40 MB), Google ML Kit EN→HI (~30 MB), DSP pitch speaker detection & Android Hindi TTS. No paid API keys or 429 limits."
                                    EngineMode.ON_DEVICE_LLM ->
                                        "Uses Vosk Offline STT + a local Open-Source GGUF LLM (Qwen 2.5 / Gemma GGUF) inside your phone + Android Hindi TTS. Understands movie/adult slang & context with 0% censorship and zero internet limits."
                                    else ->
                                        "Uses Firebase AI Logic Gemini cloud for cinematic dialogue & neural voices. Free-tier per-minute/daily quota limits apply."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (currentProject.engineMode == EngineMode.ON_DEVICE_LLM) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    color = ObsidianElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (activeGgufModelName != null) {
                                            EmeraldSuccess.copy(alpha = 0.55f)
                                        } else {
                                            SaffronGold.copy(alpha = 0.65f)
                                        }
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
                                            Text(
                                                text = if (activeGgufModelName != null) {
                                                    "Active GGUF: $activeGgufModelName"
                                                } else {
                                                    "No GGUF model installed yet"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (activeGgufModelName != null) EmeraldSuccess else SaffronGold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            StatusPill(
                                                label = if (activeGgufModelName != null) "READY" else "SETUP",
                                                tone = if (activeGgufModelName != null) "SUCCESS" else "WARN"
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = onOpenSettings,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("manage_gguf_from_project_button")
                                        ) {
                                            Text(
                                                if (activeGgufModelName != null) {
                                                    "Change / Test GGUF Model in Settings"
                                                } else {
                                                    "Download Qwen 2.5 or Import .gguf Model"
                                                }
                                            )
                                        }
                                        Text(
                                            text = "Speed Mode",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            DubbingSpeedMode.all.forEach { mode ->
                                                FilterChip(
                                                    selected = dubbingSpeedMode == mode,
                                                    onClick = { viewModel.updateDubbingSpeedMode(mode) },
                                                    label = { Text(DubbingSpeedMode.displayLabel(mode)) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = SaffronContainer,
                                                        selectedLabelColor = SaffronGold
                                                    ),
                                                    modifier = Modifier.testTag("project_speed_mode_$mode")
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (dubbingSpeedMode == DubbingSpeedMode.HYBRID_TURBO) {
                                                "Hybrid Turbo translates clean lines in ~10 ms via ML Kit and routes slang, bold/adult & complex lines to Local GGUF."
                                            } else {
                                                "Full GGUF processes every dialogue line on the local LLM."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (currentProject.engineMode == EngineMode.GEMINI_CLOUD) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    modifier = Modifier.testTag("project_gemini_cloud_info_card"),
                                    color = ObsidianElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f))
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
                                            Text(
                                                text = "Firebase AI Logic Active",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ElectricCyan,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            StatusPill(label = "ZERO API KEY", tone = "SUCCESS")
                                        }
                                        Text(
                                            text = "• Models: gemini-3.8-flash (Diarization & Scene-Context Translation) + gemini-3.1-flash-tts-preview (Neural Hindi Voices).\n• Optional Cloud Mode: Internet required. Free-tier 429 quota limits apply; attach an .SRT below to skip cloud audio upload.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedButton(
                                            onClick = onOpenSettings,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("open_settings_from_gemini_card")
                                        ) {
                                            Text("Test Gemini Cloud Connection in Settings")
                                        }
                                    }
                                }
                            }
                            if (currentProject.engineMode == EngineMode.ON_DEVICE_FREE || currentProject.engineMode == EngineMode.ON_DEVICE_LLM) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Speech Recognition (STT) & Noise Filter", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                if (!currentProject.subtitlePath.isNullOrBlank()) {
                                    Text(
                                        text = "Subtitles attached (${currentProject.subtitleFileName ?: "subtitles"}) — offline STT is skipped so movie lines are 100% clean.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EmeraldSuccess
                                    )
                                } else {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SttEngineMode.all.forEach { sttMode ->
                                            FilterChip(
                                                selected = sttEngineMode == sttMode,
                                                onClick = { viewModel.updateSttEngineMode(sttMode) },
                                                label = { Text(SttEngineMode.displayLabel(sttMode)) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SaffronContainer,
                                                    selectedLabelColor = SaffronGold
                                                ),
                                                modifier = Modifier.testTag("project_stt_engine_$sttMode")
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Vocal-Isolation Pre-Filter", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "Center vocal extraction + 160–3600 Hz bandpass before STT",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = sttVocalIsolationEnabled,
                                            onCheckedChange = { viewModel.updateSttVocalIsolationEnabled(it) },
                                            colors = SwitchDefaults.colors(checkedThumbColor = SaffronGold),
                                            modifier = Modifier.testTag("project_vocal_isolation_switch")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Optional .SRT / .VTT Subtitle Track Card (Zero-Error Dialogue Input)
                item {
                    val hasSub = !currentProject.subtitlePath.isNullOrBlank()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("project_subtitle_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (hasSub) EmeraldSuccess.copy(alpha = 0.55f) else ElectricCyan.copy(alpha = 0.45f)
                        ),
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
                                    Icon(
                                        imageVector = Icons.Filled.Subtitles,
                                        contentDescription = null,
                                        tint = if (hasSub) EmeraldSuccess else ElectricCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(".SRT / .VTT Subtitle Track (Optional)", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (hasSub) {
                                                currentProject.subtitleFileName ?: "subtitles.srt"
                                            } else {
                                                "Attach English subtitles for 100% accurate movie lines"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (hasSub) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                StatusPill(
                                    label = if (hasSub) "ATTACHED" else "STT AUTO",
                                    tone = if (hasSub) "SUCCESS" else "ACTIVE"
                                )
                            }
                            Text(
                                text = "When attached, speech recognition is skipped cleanly while keeping voice pitch detection (Male/Female), Vocal SFX preservation & BGM mixing active.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasSub) {
                                Text("Subtitle Language", style = MaterialTheme.typography.labelLarge)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SubtitleLanguage.all.forEach { lang ->
                                        FilterChip(
                                            selected = currentProject.subtitleLanguage == lang,
                                            onClick = { viewModel.updateSubtitleLanguage(lang) },
                                            label = { Text(SubtitleLanguage.displayLabel(lang)) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SaffronContainer,
                                                selectedLabelColor = SaffronGold
                                            ),
                                            modifier = Modifier.testTag("project_sub_lang_$lang")
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("project_subtitle_picker_button")
                                ) {
                                    Icon(Icons.Filled.Subtitles, contentDescription = null, tint = ElectricCyan)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (hasSub) "Replace .SRT / .VTT" else "Attach .SRT / .VTT File")
                                }
                                if (hasSub) {
                                    IconButton(
                                        onClick = { viewModel.clearSubtitleFile() },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("project_subtitle_clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove attached subtitle file",
                                            tint = ErrorCrimson
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Source -> Target Language Card
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Translate,
                                    contentDescription = null,
                                    tint = SaffronGold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Translation Pair", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "English Dialogue → Natural Hindi (हिन्दी)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            StatusPill(label = "EN → HI", tone = "ACTIVE")
                        }
                    }
                }

                // Dialogue Style
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Dialogue Style", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                DialogueStyle.all.forEach { style ->
                                    FilterChip(
                                        selected = currentProject.dialogueStyle == style,
                                        onClick = { viewModel.updateDubbingSettings(dialogueStyle = style) },
                                        label = { Text(style) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onOpenSlangDictionary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("project_open_slang_dictionary_button")
                            ) {
                                Text("Customize Slang & Context JSON Dictionary")
                            }
                        }
                    }
                }

                // Default Voice Gender, Speed & Emotion
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Default Voice, Speed & Emotion", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                "Default Voice Profile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("MALE", "FEMALE", "NEUTRAL").forEach { gender ->
                                    FilterChip(
                                        selected = currentProject.defaultVoiceGender == gender,
                                        onClick = { viewModel.updateDubbingSettings(defaultVoiceGender = gender) },
                                        label = { Text(gender.lowercase().replaceFirstChar { it.uppercase() }) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Speaking Speed", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    String.format(Locale.US, "%.2fx", currentProject.defaultSpeechSpeed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SaffronGold
                                )
                            }
                            Slider(
                                value = currentProject.defaultSpeechSpeed,
                                onValueChange = { viewModel.updateDubbingSettings(defaultSpeechSpeed = it) },
                                valueRange = 0.75f..1.35f,
                                colors = SliderDefaults.colors(
                                    thumbColor = SaffronGold,
                                    activeTrackColor = SaffronGold
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Emotional Delivery Bias",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("natural", "dramatic", "calm", "energetic", "emotional").forEach { emo ->
                                    FilterChip(
                                        selected = currentProject.defaultEmotionBias == emo,
                                        onClick = { viewModel.updateDubbingSettings(defaultEmotionBias = emo) },
                                        label = { Text(emo.replaceFirstChar { it.uppercase() }) },
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

                // Hindi Voice Model Preview & Audition Studio
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hindi_voice_audition_card"),
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
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
                                        imageVector = Icons.Filled.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = ElectricCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Preview Hindi Voice Models", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Audition Hindi voices before applying them to your movie project",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Surface(
                                color = ObsidianElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, SaffronGold.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Applied Project Voice: ${activePreset.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SaffronGold
                                    )
                                    StatusPill(label = activePreset.gender, tone = "SUCCESS")
                                }
                            }

                            // Sample Hindi dialogue input + quick phrases
                            OutlinedTextField(
                                value = auditionText,
                                onValueChange = { auditionText = it },
                                label = { Text("Sample Hindi Dialogue (Optional)") },
                                placeholder = { Text("Default cinema dialogue will be spoken if left blank") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_audition_text_input")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    "Action" to "हमें सूरज ढलने से पहले यहाँ से निकलना होगा!",
                                    "Drama" to "सच चाहे कितना भी कड़वा हो, आज मैं जानकर रहूँगा।",
                                    "Warm" to "चिंता मत करो, जब तक हम साथ हैं सब ठीक रहेगा।"
                                ).forEach { (chipTitle, sampleLine) ->
                                    FilterChip(
                                        selected = auditionText == sampleLine,
                                        onClick = {
                                            auditionText = if (auditionText == sampleLine) "" else sampleLine
                                        },
                                        label = { Text("Sample: $chipTitle") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        )
                                    )
                                }
                            }

                            // Gender filter for voice catalog
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("ALL", "MALE", "FEMALE", "NEUTRAL").forEach { filter ->
                                    FilterChip(
                                        selected = genderFilter == filter,
                                        onClick = { genderFilter = filter },
                                        label = { Text(filter.lowercase().replaceFirstChar { it.uppercase() }) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SaffronContainer,
                                            selectedLabelColor = SaffronGold
                                        ),
                                        modifier = Modifier.testTag("voice_filter_$filter")
                                    )
                                }
                            }

                            if (voicePreviewState is VoicePreviewState.Error) {
                                val err = voicePreviewState as VoicePreviewState.Error
                                Surface(
                                    color = ObsidianElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ErrorCrimson.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = err.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ErrorCrimson
                                        )
                                        if (err.message.contains("Text-to-Speech", ignoreCase = true) ||
                                            err.message.contains("TTS", ignoreCase = true)
                                        ) {
                                            OutlinedButton(
                                                onClick = { OnDeviceModelManager.openTtsSettings(context) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Open Android Hindi TTS Settings")
                                            }
                                        }
                                    }
                                }
                            }

                            // Voice model audition cards
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                filteredVoices.forEach { preset ->
                                    val isLoadingThis = voicePreviewState is VoicePreviewState.Loading &&
                                        (voicePreviewState as VoicePreviewState.Loading).presetId == preset.id
                                    val isPlayingThis = playbackState.activeTag == "voice_preview_${preset.id}" &&
                                        playbackState.isPlaying
                                    val isApplied = currentProject.defaultVoicePreset == preset.id

                                    VoiceModelAuditionRow(
                                        preset = preset,
                                        isApplied = isApplied,
                                        isLoading = isLoadingThis,
                                        isPlaying = isPlayingThis,
                                        playbackState = playbackState,
                                        onPreview = {
                                            viewModel.previewHindiVoicePreset(
                                                presetId = preset.id,
                                                sampleHindiText = auditionText
                                            )
                                        },
                                        onApply = {
                                            viewModel.applyVoicePresetToProject(preset.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Multi-Speaker Voice Assignment
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speaker Voice Assignments", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Speakers detected in the movie appear here automatically; you can also pre-assign speaker slots.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.addManualSpeakerPreset(currentProject.defaultVoiceGender) },
                            modifier = Modifier.testTag("add_speaker_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }

                if (speakers.isEmpty()) {
                    item {
                        StudioDisclosureCard(
                            title = "Auto-Diarization Ready",
                            body = if (currentProject.engineMode == EngineMode.ON_DEVICE_FREE || currentProject.engineMode == EngineMode.ON_DEVICE_LLM) {
                                "Vosk + DSP Pitch Analyzer will automatically detect deeper Male vs higher Female voices (SPEAKER_1, SPEAKER_2...) inside your phone and assign Hindi TTS pitch/voice profiles. Tap 'Add' above to pre-configure slots."
                            } else {
                                "When Gemini transcribes each audio chunk, distinct speakers (SPEAKER_1, SPEAKER_2...) will be auto-detected and assigned matching Male, Female, or Neutral Hindi voices. Tap 'Add' above to pre-configure speaker slots now."
                            }
                        )
                    }
                } else {
                    items(speakers, key = { it.id }) { speaker ->
                        val speakerPreviewTag = "speaker_preview_${speaker.id}_${speaker.voicePresetName}"
                        val isSpeakerLoading = voicePreviewState is VoicePreviewState.Loading &&
                            (voicePreviewState as VoicePreviewState.Loading).presetId == speaker.voicePresetName
                        val isSpeakerPlaying = playbackState.activeTag == speakerPreviewTag && playbackState.isPlaying
                        SpeakerVoiceCard(
                            speaker = speaker,
                            isPreviewLoading = isSpeakerLoading,
                            isPreviewPlaying = isSpeakerPlaying,
                            onPreviewSpeaker = {
                                viewModel.previewHindiVoicePreset(
                                    presetId = speaker.voicePresetName,
                                    sampleHindiText = auditionText,
                                    speedOverride = speaker.speedMultiplier,
                                    tagPrefix = "speaker_preview_${speaker.id}"
                                )
                            },
                            onUpdate = { g, preset, spd ->
                                viewModel.updateSpeaker(speaker, gender = g, voicePresetName = preset, speedMultiplier = spd)
                            }
                        )
                    }
                }

                item {
                    StudioDisclosureCard(
                        title = "Synthesized Voice Disclosure",
                        body = stringResource(R.string.voice_disclosure)
                    )
                }

                item {
                    Button(
                        onClick = {
                            viewModel.stopVoicePreview()
                            onNextAudioSettings()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("next_audio_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SaffronGold,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Next: Audio & Background Music")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceModelAuditionRow(
    preset: VoicePreset,
    isApplied: Boolean,
    isLoading: Boolean,
    isPlaying: Boolean,
    playbackState: AudioPlaybackState,
    onPreview: () -> Unit,
    onApply: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_model_card_${preset.id}"),
        color = if (isApplied) EmeraldContainer.copy(alpha = 0.25f) else ObsidianElevated,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isPlaying -> ElectricCyan
                isApplied -> EmeraldSuccess.copy(alpha = 0.65f)
                else -> ObsidianBorder
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isApplied) EmeraldSuccess else MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    label = preset.gender,
                    tone = if (isPlaying) "ACTIVE" else "NEUTRAL"
                )
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = ElectricCyan,
                    trackColor = ObsidianSurfaceVariant
                )
            } else if (isPlaying) {
                val fraction = if (playbackState.durationMs > 0L) {
                    (playbackState.positionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = ElectricCyan,
                    trackColor = ObsidianSurfaceVariant
                )
                Text(
                    text = "Playing sample • ${WavUtils.formatTimecode(playbackState.positionMs)} / ${WavUtils.formatTimecode(playbackState.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("preview_voice_${preset.id}")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generating...")
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPlaying) "Stop" else "Preview")
                    }
                }

                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("apply_voice_${preset.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isApplied) EmeraldSuccess else SaffronGold,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isApplied) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Applied")
                    } else {
                        Text("Apply Voice")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerVoiceCard(
    speaker: SpeakerEntity,
    isPreviewLoading: Boolean,
    isPreviewPlaying: Boolean,
    onPreviewSpeaker: () -> Unit,
    onUpdate: (gender: String, preset: String, speed: Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = SaffronGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(speaker.displayName, style = MaterialTheme.typography.titleMedium)
                        if (speaker.traits.isNotBlank()) {
                            Text(
                                speaker.traits,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                StatusPill(label = speaker.gender, tone = "ACTIVE")
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice Preset", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = onPreviewSpeaker,
                    enabled = !isPreviewLoading,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("preview_speaker_${speaker.id}")
                ) {
                    if (isPreviewLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ElectricCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Loading...")
                    } else {
                        Icon(
                            imageVector = if (isPreviewPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPreviewPlaying) "Stop" else "Preview")
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HindiVoicePresets.all.forEach { preset ->
                    FilterChip(
                        selected = speaker.voicePresetName == preset.id,
                        onClick = { onUpdate(preset.gender, preset.id, speaker.speedMultiplier) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SaffronContainer,
                            selectedLabelColor = SaffronGold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Speaker Speed", style = MaterialTheme.typography.bodySmall)
                Text(
                    String.format(Locale.US, "%.2fx", speaker.speedMultiplier),
                    style = MaterialTheme.typography.labelMedium,
                    color = SaffronGold
                )
            }
            Slider(
                value = speaker.speedMultiplier,
                onValueChange = { onUpdate(speaker.gender, speaker.voicePresetName, it) },
                valueRange = 0.75f..1.35f,
                colors = SliderDefaults.colors(
                    thumbColor = SaffronGold,
                    activeTrackColor = SaffronGold
                )
            )
        }
    }
}
