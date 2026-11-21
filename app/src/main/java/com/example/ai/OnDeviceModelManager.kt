package com.example.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.example.data.local.SttEngineMode
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OnDeviceModelsStatus(
    val voskDownloaded: Boolean,
    val voskSizeBytes: Long,
    val whisperTinyDownloaded: Boolean,
    val whisperTinySizeBytes: Long,
    val whisperBaseDownloaded: Boolean,
    val whisperBaseSizeBytes: Long,
    val mlKitHindiDownloaded: Boolean,
    val androidHindiTtsReady: Boolean,
    val ttsVoicesCount: Int,
    val ttsNote: String
)

/**
 * Manages one-time free downloads of on-device models (Vosk English Small ~40MB, Whisper Tiny/Base EN,
 * and ML Kit EN->HI ~30MB) and inspects Android built-in Hindi TextToSpeech availability.
 */
object OnDeviceModelManager {

    private val VOSK_EN_URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "https://alphacephei.com/kaldi/models/vosk-model-small-en-us-0.15.zip"
    )

    private const val WHISPER_TINY_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
    private const val WHISPER_BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
    private const val WHISPER_TINY_FILE = "ggml-tiny.en-q5_1.bin"
    private const val WHISPER_BASE_FILE = "ggml-base.en-q5_1.bin"

    private const val VOSK_DIR_NAME = "vosk-en-us-small"
    private const val WHISPER_DIR_NAME = "whisper"
    private const val READY_MARKER = ".ready"

    fun voskModelDir(context: Context): File =
        File(File(context.filesDir, "models"), VOSK_DIR_NAME)

    fun whisperModelsDir(context: Context): File =
        File(File(context.filesDir, "models"), WHISPER_DIR_NAME).apply { mkdirs() }

    fun whisperModelFile(context: Context, sttEngineMode: String): File {
        val fileName = when (sttEngineMode) {
            SttEngineMode.WHISPER_BASE_EN -> WHISPER_BASE_FILE
            else -> WHISPER_TINY_FILE
        }
        return File(whisperModelsDir(context), fileName)
    }

    fun isValidGgmlFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 1024L) return false
        return runCatching {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                val read = ins.read(magic)
                // whisper.cpp GGML magic is 0x67676d6c ('lmgg' little-endian or 'ggml' big-endian)
                read == 4 && (
                    (magic[0] == 'l'.code.toByte() && magic[1] == 'm'.code.toByte() && magic[2] == 'g'.code.toByte() && magic[3] == 'g'.code.toByte()) ||
                        (magic[0] == 'g'.code.toByte() && magic[1] == 'g'.code.toByte() && magic[2] == 'm'.code.toByte() && magic[3] == 'l'.code.toByte())
                    )
            }
        }.getOrDefault(false)
    }

    fun isWhisperModelReady(context: Context, sttEngineMode: String): Boolean {
        val f = whisperModelFile(context, sttEngineMode)
        val minBytes = if (sttEngineMode == SttEngineMode.WHISPER_BASE_EN) 25L * 1024L * 1024L else 15L * 1024L * 1024L
        return f.exists() && f.isFile && f.length() >= minBytes && isValidGgmlFile(f)
    }

    fun isVoskModelReady(context: Context): Boolean {
        val dir = voskModelDir(context)
        return dir.exists() &&
            File(dir, READY_MARKER).exists() &&
            File(dir, "am").exists() &&
            File(dir, "conf").exists()
    }

    fun getVoskModelSizeBytes(context: Context): Long {
        val dir = voskModelDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun inspectStatus(context: Context): OnDeviceModelsStatus = withContext(Dispatchers.IO) {
        val voskReady = isVoskModelReady(context)
        val voskBytes = getVoskModelSizeBytes(context)
        val wTinyReady = isWhisperModelReady(context, SttEngineMode.WHISPER_TINY_EN)
        val wTinyBytes = if (wTinyReady) whisperModelFile(context, SttEngineMode.WHISPER_TINY_EN).length() else 0L
        val wBaseReady = isWhisperModelReady(context, SttEngineMode.WHISPER_BASE_EN)
        val wBaseBytes = if (wBaseReady) whisperModelFile(context, SttEngineMode.WHISPER_BASE_EN).length() else 0L

        val mlKitReady = try {
            val hindiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
            RemoteModelManager.getInstance().isModelDownloaded(hindiModel).awaitTask()
        } catch (_: Exception) {
            false
        }

        val (ttsReady, voicesCount, ttsNote) = checkAndroidHindiTts(context)

        OnDeviceModelsStatus(
            voskDownloaded = voskReady,
            voskSizeBytes = voskBytes,
            whisperTinyDownloaded = wTinyReady,
            whisperTinySizeBytes = wTinyBytes,
            whisperBaseDownloaded = wBaseReady,
            whisperBaseSizeBytes = wBaseBytes,
            mlKitHindiDownloaded = mlKitReady,
            androidHindiTtsReady = ttsReady,
            ttsVoicesCount = voicesCount,
            ttsNote = ttsNote
        )
    }

    suspend fun isOfflineModelsDownloaded(
        context: Context,
        sttEngineMode: String = SttEngineMode.VOSK_FAST
    ): Boolean = withContext(Dispatchers.IO) {
        val sttOk = when (sttEngineMode) {
            SttEngineMode.WHISPER_TINY_EN,
            SttEngineMode.WHISPER_BASE_EN -> isWhisperModelReady(context, sttEngineMode)
            else -> isVoskModelReady(context)
        }
        if (!sttOk) return@withContext false
        try {
            val hindiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
            RemoteModelManager.getInstance().isModelDownloaded(hindiModel).awaitTask()
        } catch (_: Exception) {
            false
        }
    }

    fun openTtsSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            val launched = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }

    suspend fun ensureAllModelsReady(
        context: Context,
        sttEngineMode: String = SttEngineMode.VOSK_FAST,
        onStatus: (String) -> Unit
    ) {
        ensureSttModelReady(context, sttEngineMode, onStatus)
        ensureMlKitHindiModel(onStatus)
        val (ttsReady, _, ttsNote) = checkAndroidHindiTts(context)
        if (!ttsReady) {
            throw IllegalStateException(
                "Android Hindi Text-to-Speech voice data is not ready on this phone ($ttsNote). " +
                    "Tap 'Install Hindi TTS Voice' or enable Google Speech Services in Android Settings."
            )
        }
    }

    suspend fun ensureSttModelReady(
        context: Context,
        sttEngineMode: String,
        onStatus: (String) -> Unit
    ) {
        when (sttEngineMode) {
            SttEngineMode.WHISPER_TINY_EN,
            SttEngineMode.WHISPER_BASE_EN -> {
                if (!isWhisperModelReady(context, sttEngineMode)) {
                    downloadWhisperModel(context, sttEngineMode, onStatus)
                }
            }
            else -> {
                if (!isVoskModelReady(context)) {
                    downloadAndExtractVoskModel(context, onStatus)
                }
            }
        }
    }

    suspend fun downloadWhisperModel(
        context: Context,
        sttEngineMode: String,
        onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destFile = whisperModelFile(context, sttEngineMode)
        if (isWhisperModelReady(context, sttEngineMode)) return@withContext destFile

        val isBase = sttEngineMode == SttEngineMode.WHISPER_BASE_EN
        val url = if (isBase) WHISPER_BASE_URL else WHISPER_TINY_URL
        val approxBytes = if (isBase) 60L * 1024L * 1024L else 32L * 1024L * 1024L
        val label = if (isBase) "Whisper Base EN (~60 MB)" else "Whisper Tiny EN (~32 MB)"
        val partFile = File(destFile.parentFile, "${destFile.name}.part")

        onStatus("Downloading $label...")
        ResumableHttpDownloader.downloadToFile(
            url = url,
            partFile = partFile,
            destFile = destFile,
            fallbackApproxBytes = approxBytes,
            reportIntervalBytes = 512L * 1024L,
            validateComplete = { f -> f.length() >= (approxBytes / 2L) && isValidGgmlFile(f) }
        ) { doneBytes, totalBytes ->
            val dlMb = doneBytes / (1024f * 1024f)
            val totMb = totalBytes.coerceAtLeast(approxBytes) / (1024f * 1024f)
            val pct = ((doneBytes.toFloat() / totalBytes.coerceAtLeast(approxBytes)) * 100f).toInt().coerceIn(0, 100)
            onStatus(String.format(Locale.US, "Downloading %s: %.0f / %.0f MB (%d%%)", label, dlMb, totMb, pct))
        }
    }

    suspend fun downloadAndExtractVoskModel(
        context: Context,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isVoskModelReady(context)) return@withContext

        val targetDir = voskModelDir(context)
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val zipPart = File(context.cacheDir, "vosk_en_small_tmp.zip.part")
        val zipFinal = File(context.cacheDir, "vosk_en_small_tmp.zip")
        var lastError: Exception? = null
        var downloadedOk = false

        try {
            for (urlStr in VOSK_EN_URLS) {
                try {
                    onStatus("Downloading Vosk Offline English Model (~40 MB)...")
                    ResumableHttpDownloader.downloadToFile(
                        url = urlStr,
                        partFile = zipPart,
                        destFile = zipFinal,
                        fallbackApproxBytes = 40L * 1024L * 1024L,
                        reportIntervalBytes = 512L * 1024L
                    ) { doneBytes, totalBytes ->
                        val doneMb = doneBytes / (1024f * 1024f)
                        val totalMb = totalBytes / (1024f * 1024f)
                        if (totalMb > 0f) {
                            onStatus("Downloading Vosk English Model: ${doneMb.toInt()} / ${totalMb.toInt()} MB")
                        } else {
                            onStatus("Downloading Vosk English Model: ${doneMb.toInt()} MB")
                        }
                    }
                    downloadedOk = true
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (!downloadedOk) {
                throw IllegalStateException(
                    "Unable to download free Vosk English speech model (~40 MB). Please check phone internet connection and retry.",
                    lastError
                )
            }

            onStatus("Extracting offline English speech model...")
            extractVoskZip(zipFinal, targetDir)
            File(targetDir, READY_MARKER).writeText("ok")
            zipFinal.delete()
        } finally {
            if (isVoskModelReady(context)) {
                zipPart.delete()
                zipFinal.delete()
            }
        }
    }

    suspend fun ensureMlKitHindiModel(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        onStatus("Checking ML Kit EN→HI offline translation model (~30 MB)...")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HINDI)
            .build()
        val translator = Translation.getClient(options)
        try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).awaitTask()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Unable to download free Google ML Kit English→Hindi offline model (~30 MB). Check phone internet and retry.",
                e
            )
        } finally {
            translator.close()
        }
    }

    suspend fun deleteOnDeviceModels(context: Context): Long = withContext(Dispatchers.IO) {
        val voskBytes = getVoskModelSizeBytes(context)
        voskModelDir(context).deleteRecursively()
        val whisperDir = whisperModelsDir(context)
        val whisperBytes = if (whisperDir.exists()) {
            whisperDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        whisperDir.deleteRecursively()
        runCatching {
            val hindiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(hindiModel).awaitTask()
        }
        voskBytes + whisperBytes
    }

    suspend fun checkAndroidHindiTts(context: Context): Triple<Boolean, Int, String> {
        return try {
            withTimeout(8_000L) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(context.applicationContext) { status ->
                        try {
                            if (status != TextToSpeech.SUCCESS) {
                                if (cont.isActive) {
                                    cont.resume(Triple(false, 0, "TTS Engine init error ($status)"))
                                }
                                return@TextToSpeech
                            }
                            val engine = tts
                            if (engine == null) {
                                if (cont.isActive) cont.resume(Triple(false, 0, "TTS unavailable"))
                                return@TextToSpeech
                            }
                            val res = engine.setLanguage(Locale.forLanguageTag("hi-IN"))
                            val ready = res != TextToSpeech.LANG_MISSING_DATA &&
                                res != TextToSpeech.LANG_NOT_SUPPORTED
                            val hindiVoices = runCatching {
                                engine.voices?.count { it.locale.language.equals("hi", ignoreCase = true) } ?: 0
                            }.getOrDefault(0)
                            val note = when (res) {
                                TextToSpeech.LANG_MISSING_DATA -> "Hindi TTS voice pack needs download"
                                TextToSpeech.LANG_NOT_SUPPORTED -> "Hindi TTS not supported on active engine"
                                else -> "$hindiVoices Hindi voice profile(s) available"
                            }
                            if (cont.isActive) cont.resume(Triple(ready, hindiVoices, note))
                        } finally {
                            runCatching { tts?.shutdown() }
                        }
                    }
                    cont.invokeOnCancellation {
                        runCatching { tts.shutdown() }
                    }
                }
            }
        } catch (_: Exception) {
            Triple(false, 0, "Timed out checking Android TTS")
        }
    }

    private fun extractVoskZip(zipFile: File, targetDir: File) {
        val targetCanonical = targetDir.canonicalPath
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                // Strip the top-level "vosk-model-small-en-us-0.15/" directory inside the zip
                val stripped = if (rawName.contains("/")) rawName.substringAfter("/") else rawName
                if (stripped.isNotBlank()) {
                    val outFile = File(targetDir, stripped)
                    val outCanonical = outFile.canonicalPath
                    if (!outCanonical.startsWith(targetCanonical + File.separator)) {
                        throw SecurityException("Unsafe zip entry path: $rawName")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zis.copyTo(out)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
