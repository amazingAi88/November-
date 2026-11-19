package com.example.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OnDeviceModelsStatus(
    val voskDownloaded: Boolean,
    val voskSizeBytes: Long,
    val mlKitHindiDownloaded: Boolean,
    val androidHindiTtsReady: Boolean,
    val ttsVoicesCount: Int,
    val ttsNote: String
)

/**
 * Manages one-time free downloads of on-device models (Vosk English Small ~40MB + ML Kit EN->HI ~30MB)
 * and inspects Android built-in Hindi TextToSpeech availability. Zero paid API keys required.
 */
object OnDeviceModelManager {

    private val VOSK_EN_URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "https://alphacephei.com/kaldi/models/vosk-model-small-en-us-0.15.zip"
    )

    private const val VOSK_DIR_NAME = "vosk-en-us-small"
    private const val READY_MARKER = ".ready"

    fun voskModelDir(context: Context): File =
        File(File(context.filesDir, "models"), VOSK_DIR_NAME)

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
            mlKitHindiDownloaded = mlKitReady,
            androidHindiTtsReady = ttsReady,
            ttsVoicesCount = voicesCount,
            ttsNote = ttsNote
        )
    }

    suspend fun isOfflineModelsDownloaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isVoskModelReady(context)) return@withContext false
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
        onStatus: (String) -> Unit
    ) {
        if (!isVoskModelReady(context)) {
            downloadAndExtractVoskModel(context, onStatus)
        }
        ensureMlKitHindiModel(onStatus)
        val (ttsReady, _, ttsNote) = checkAndroidHindiTts(context)
        if (!ttsReady) {
            throw IllegalStateException(
                "Android Hindi Text-to-Speech voice data is not ready on this phone ($ttsNote). " +
                    "Tap 'Install Hindi TTS Voice' or enable Google Speech Services in Android Settings."
            )
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

        val zipTemp = File(context.cacheDir, "vosk_en_small_tmp.zip")
        var lastError: Exception? = null
        var downloadedOk = false

        try {
            for (urlStr in VOSK_EN_URLS) {
                try {
                    onStatus("Downloading Vosk Offline English Model (~40 MB)...")
                    downloadFileWithProgress(urlStr, zipTemp) { doneMb, totalMb ->
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
                    zipTemp.delete()
                }
            }

            if (!downloadedOk) {
                throw IllegalStateException(
                    "Unable to download free Vosk English speech model (~40 MB). Please check phone internet connection and retry.",
                    lastError
                )
            }

            onStatus("Extracting offline English speech model...")
            extractVoskZip(zipTemp, targetDir)
            File(targetDir, READY_MARKER).writeText("ok")
        } finally {
            zipTemp.delete()
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
        runCatching {
            val hindiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(hindiModel).awaitTask()
        }
        voskBytes
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

    private fun downloadFileWithProgress(
        urlStr: String,
        destFile: File,
        onProgressMb: (Float, Float) -> Unit
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null
        while (redirects < 5) {
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect missing Location")
                currentUrl = loc
                redirects++
            } else {
                conn = c
                break
            }
        }
        val activeConn = conn ?: throw IllegalStateException("Too many redirects")
        try {
            if (activeConn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${activeConn.responseCode} downloading model")
            }
            val totalBytes = activeConn.contentLengthLong.coerceAtLeast(0L)
            val totalMb = totalBytes / (1024f * 1024f)
            var downloaded = 0L
            var lastReported = 0L
            val buf = ByteArray(16 * 1024)
            BufferedInputStream(activeConn.inputStream).use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= 1024 * 1024) {
                            lastReported = downloaded
                            onProgressMb(downloaded / (1024f * 1024f), totalMb)
                        }
                    }
                }
            }
        } finally {
            activeConn.disconnect()
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
