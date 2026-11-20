package com.example.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class GgufModelPreset(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeMb: Int,
    val minRamGb: Int,
    val description: String
)

data class InstalledGgufModel(
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    val isValidGguf: Boolean
)

/**
 * Manages local open-source GGUF language models (Qwen / Gemma / Llama GGUF) stored privately
 * inside the phone for 100% offline, unfiltered, scene-context-aware Hindi movie dialogue translation.
 */
object LocalLlmModelManager {

    val PRESETS = listOf(
        GgufModelPreset(
            id = "qwen25_1_5b",
            displayName = "Qwen 2.5 1.5B Instruct (Q4_K_M)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            approxSizeMb = 1120,
            minRamGb = 6,
            description = "Best Hindi fluency, slang & Bollywood dialogue nuance. Recommended for Redmi Note 10 Pro (6GB+ RAM)."
        ),
        GgufModelPreset(
            id = "qwen25_0_5b",
            displayName = "Qwen 2.5 0.5B Instruct (Q4_K_M Lite)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxSizeMb = 410,
            minRamGb = 4,
            description = "Fast lightweight GGUF model (~410 MB) for 4GB RAM devices & quick chunk translation."
        )
    )

    private const val MODELS_SUBDIR = "llm_models"
    private const val MIN_VALID_GGUF_BYTES = 10L * 1024L * 1024L

    fun modelsDir(context: Context): File {
        val base = context.getExternalFilesDir(MODELS_SUBDIR) ?: File(context.filesDir, MODELS_SUBDIR)
        return base.apply { mkdirs() }
    }

    fun getDeviceTotalRamGb(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4.0f
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024f * 1024f * 1024f)
    }

    fun isValidGgufFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < MIN_VALID_GGUF_BYTES) return false
        return runCatching {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                val read = ins.read(magic)
                read == 4 &&
                    magic[0] == 'G'.code.toByte() &&
                    magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    fun listInstalledModels(context: Context): List<InstalledGgufModel> {
        val dir = modelsDir(context)
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        return files.map { file ->
            InstalledGgufModel(
                fileName = file.name,
                file = file,
                sizeBytes = file.length(),
                isValidGguf = isValidGgufFile(file)
            )
        }
    }

    fun resolveActiveModelFile(context: Context, preferredFileName: String?): File? {
        val installed = listInstalledModels(context).filter { it.isValidGguf }
        if (installed.isEmpty()) return null
        if (!preferredFileName.isNullOrBlank()) {
            installed.firstOrNull { it.fileName == preferredFileName }?.let { return it.file }
        }
        return installed.firstOrNull()?.file
    }

    fun hasReadyModel(context: Context, preferredFileName: String? = null): Boolean =
        resolveActiveModelFile(context, preferredFileName) != null

    suspend fun downloadPresetModel(
        context: Context,
        preset: GgufModelPreset,
        onProgress: (downloadedMb: Float, totalMb: Float, fraction: Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = modelsDir(context)
        val finalFile = File(dir, preset.fileName)
        if (isValidGgufFile(finalFile)) {
            onProgress(finalFile.length() / (1024f * 1024f), finalFile.length() / (1024f * 1024f), 1f)
            return@withContext finalFile
        }

        val partFile = File(dir, "${preset.fileName}.part")
        partFile.delete()

        var currentUrl = preset.downloadUrl
        var redirects = 0
        var activeConn: HttpURLConnection? = null
        while (redirects < 8) {
            coroutineContext.ensureActive()
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "HindiMovieDubbingStudio/1.0")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect missing Location header.")
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                activeConn = conn
                break
            }
        }

        val conn = activeConn ?: throw IllegalStateException("Too many HTTP redirects downloading GGUF model.")
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} while downloading ${preset.displayName}.")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0L }
                ?: (preset.approxSizeMb.toLong() * 1024L * 1024L)
            val totalMb = totalBytes / (1024f * 1024f)
            var downloaded = 0L
            var lastReport = 0L
            val buf = ByteArray(64 * 1024)

            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= 2L * 1024L * 1024L) {
                            lastReport = downloaded
                            val dlMb = downloaded / (1024f * 1024f)
                            val frac = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                            onProgress(dlMb, totalMb, frac)
                        }
                    }
                }
            }

            if (!isValidGgufFile(partFile)) {
                partFile.delete()
                throw IllegalStateException("Downloaded file did not have a valid GGUF header.")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            val finalMb = finalFile.length() / (1024f * 1024f)
            onProgress(finalMb, finalMb, 1f)
            finalFile
        } catch (e: Exception) {
            partFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    suspend fun importGgufFromUri(
        context: Context,
        sourceUri: Uri,
        onProgress: (copiedMb: Float, totalMb: Float, fraction: Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var resolvedName = "custom_model_${System.currentTimeMillis()}.gguf"
        var totalBytes = 0L

        cr.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    val raw = c.getString(nameIdx)?.trim().orEmpty()
                    if (raw.isNotBlank()) {
                        val safe = raw.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
                        resolvedName = if (safe.endsWith(".gguf", ignoreCase = true)) safe else "$safe.gguf"
                    }
                }
                if (sizeIdx >= 0) {
                    totalBytes = c.getLong(sizeIdx).coerceAtLeast(0L)
                }
            }
        }

        val dir = modelsDir(context)
        val partFile = File(dir, "$resolvedName.part")
        val finalFile = File(dir, resolvedName)
        partFile.delete()

        val rawInput = cr.openInputStream(sourceUri)
            ?: throw IllegalStateException("Unable to open the selected GGUF file.")

        try {
            BufferedInputStream(rawInput, 64 * 1024).use { input ->
                val header = ByteArray(4)
                val headerRead = input.read(header)
                if (headerRead < 4 ||
                    header[0] != 'G'.code.toByte() ||
                    header[1] != 'G'.code.toByte() ||
                    header[2] != 'U'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) {
                    throw IllegalStateException("Selected file is not a valid GGUF model (missing 'GGUF' file signature).")
                }

                FileOutputStream(partFile).use { output ->
                    output.write(header, 0, 4)
                    var copied = 4L
                    var lastReported = 0L
                    val buf = ByteArray(64 * 1024)
                    val totalMb = if (totalBytes > 0L) totalBytes / (1024f * 1024f) else 0f

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        copied += read
                        if (copied - lastReported >= 4L * 1024L * 1024L) {
                            lastReported = copied
                            val copiedMb = copied / (1024f * 1024f)
                            val frac = if (totalBytes > 0L) (copied.toFloat() / totalBytes).coerceIn(0f, 0.99f) else 0.5f
                            onProgress(copiedMb, totalMb, frac)
                        }
                    }
                }
            }

            if (!isValidGgufFile(partFile)) {
                partFile.delete()
                throw IllegalStateException("Imported file is too small or corrupted.")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            val finalMb = finalFile.length() / (1024f * 1024f)
            onProgress(finalMb, finalMb, 1f)
            finalFile
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
    }

    fun deleteModel(context: Context, fileName: String): Long {
        val file = File(modelsDir(context), fileName)
        val size = if (file.exists()) file.length() else 0L
        file.delete()
        return size
    }
}
