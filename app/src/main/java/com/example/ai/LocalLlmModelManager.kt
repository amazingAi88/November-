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

data class PartialGgufDownload(
    val fileName: String,
    val bytesDownloaded: Long,
    val approxTotalMb: Int,
    val progressFraction: Float
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

    fun listPartialDownloads(context: Context): Map<String, PartialGgufDownload> {
        val dir = modelsDir(context)
        val result = mutableMapOf<String, PartialGgufDownload>()
        for (preset in PRESETS) {
            val finalFile = File(dir, preset.fileName)
            if (isValidGgufFile(finalFile)) continue
            val partFile = File(dir, "${preset.fileName}.part")
            if (partFile.exists() && partFile.isFile && partFile.length() > 0L) {
                val approxBytes = (preset.approxSizeMb.toLong() * 1024L * 1024L).coerceAtLeast(1L)
                val frac = (partFile.length().toFloat() / approxBytes.toFloat()).coerceIn(0.01f, 0.99f)
                result[preset.fileName] = PartialGgufDownload(
                    fileName = preset.fileName,
                    bytesDownloaded = partFile.length(),
                    approxTotalMb = preset.approxSizeMb,
                    progressFraction = frac
                )
            }
        }
        return result
    }

    fun deletePartialDownload(context: Context, fileName: String): Long {
        val partFile = File(modelsDir(context), "$fileName.part")
        val size = if (partFile.exists()) partFile.length() else 0L
        partFile.delete()
        return size
    }

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
        val approxTotalBytes = preset.approxSizeMb.toLong() * 1024L * 1024L
        ResumableHttpDownloader.downloadToFile(
            url = preset.downloadUrl,
            partFile = partFile,
            destFile = finalFile,
            fallbackApproxBytes = approxTotalBytes,
            reportIntervalBytes = 1024L * 1024L,
            validateComplete = { f -> isValidGgufFile(f) },
            onProgress = { downloadedBytes, totalBytes ->
                val dlMb = downloadedBytes / (1024f * 1024f)
                val totBytes = totalBytes.coerceAtLeast(approxTotalBytes).coerceAtLeast(1L)
                val totMb = totBytes / (1024f * 1024f)
                val frac = (downloadedBytes.toFloat() / totBytes.toFloat()).coerceIn(0f, 1f)
                onProgress(dlMb, totMb, frac)
            }
        )
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
        val dir = modelsDir(context)
        val file = File(dir, fileName)
        val partFile = File(dir, "$fileName.part")
        var size = if (file.exists()) file.length() else 0L
        file.delete()
        if (partFile.exists()) {
            size += partFile.length()
            partFile.delete()
        }
        return size
    }
}
