package com.example.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.data.local.ExportQuality
import com.example.data.local.MediaAccelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoExporter {

    /**
     * Encodes ordered [mixedChunkWavs] directly into a single AAC-LC M4A track [outputM4aFile]
     * without duplicating huge intermediate PCM files on disk.
     */
    suspend fun encodeMixedChunksToM4a(
        mixedChunkWavs: List<File>,
        outputM4aFile: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(mixedChunkWavs.isNotEmpty()) { "No mixed audio chunks available to export." }
        outputM4aFile.parentFile?.mkdirs()

        val firstHeader = WavUtils.readHeader(mixedChunkWavs.first())
        val sampleRate = firstHeader.sampleRate
        val channels = firstHeader.channels
        val totalBytes = mixedChunkWavs.sumOf { WavUtils.readHeader(it).dataSize }.coerceAtLeast(1L)

        val encoderFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false

        try {
            codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var encodedBytes = 0L
            var totalSamplesWritten = 0L
            val chunkBuffer = ByteArray(4096)

            for (wavFile in mixedChunkWavs) {
                val hdr = WavUtils.readHeader(wavFile)
                RandomAccessFile(wavFile, "r").use { raf ->
                    raf.seek(hdr.dataOffset)
                    var remaining = hdr.dataSize
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val readSize = minOf(remaining, chunkBuffer.size.toLong()).toInt()
                        raf.readFully(chunkBuffer, 0, readSize)
                        remaining -= readSize
                        encodedBytes += readSize

                        var offset = 0
                        while (offset < readSize) {
                            val inIdx = codec.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val toCopy = minOf(inBuf.remaining(), readSize - offset)
                                inBuf.put(chunkBuffer, offset, toCopy)
                                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                                codec.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                                val frames = toCopy / (channels * 2)
                                totalSamplesWritten += frames
                                offset += toCopy
                            }
                            drainEncoder(codec, muxer, bufferInfo, muxerTrack, muxerStarted).let {
                                muxerTrack = it.first
                                muxerStarted = it.second
                            }
                        }
                        onProgress(((encodedBytes * 45) / totalBytes).toInt().coerceIn(0, 45))
                    }
                }
            }

            // Signal EOS
            var eosQueued = false
            while (!eosQueued) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
                drainEncoder(codec, muxer, bufferInfo, muxerTrack, muxerStarted).let {
                    muxerTrack = it.first
                    muxerStarted = it.second
                }
            }

            // Drain remaining encoded frames
            var eosOut = false
            while (!eosOut) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrack, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosOut = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        currentTrack: Int,
        currentStarted: Boolean
    ): Pair<Int, Boolean> {
        var track = currentTrack
        var started = currentStarted
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && started) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                else -> return track to started
            }
        }
    }

    fun isVideoMimeMp4MuxCompatible(mime: String?): Boolean {
        val normalized = mime?.lowercase() ?: return false
        return normalized == MediaFormat.MIMETYPE_VIDEO_AVC ||
            normalized == MediaFormat.MIMETYPE_VIDEO_HEVC ||
            normalized == MediaFormat.MIMETYPE_VIDEO_MPEG4 ||
            normalized == MediaFormat.MIMETYPE_VIDEO_H263
    }

    private fun inspectSourceVideoMime(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var found: String? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    found = mime
                    break
                }
            }
            found
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    suspend fun exportFinalMp4(
        context: Context,
        sourceUri: Uri,
        mixedChunkWavs: List<File>,
        durationMs: Long,
        quality: String,
        outputMp4File: File,
        mediaAccelSettings: MediaAccelSettings = MediaAccelSettings(),
        onProgress: (Int, String) -> Unit
    ): File {
        val m4aTemp = File(outputMp4File.parentFile, "${outputMp4File.nameWithoutExtension}_audio.m4a")
        onProgress(5, "Encoding Hindi dubbed audio (AAC)...")
        encodeMixedChunksToM4a(mixedChunkWavs, m4aTemp) { p ->
            onProgress(p, "Encoding Hindi dubbed audio (AAC)...")
        }

        try {
            val sourceVideoMime = withContext(Dispatchers.IO) { inspectSourceVideoMime(context, sourceUri) }
            if (quality == ExportQuality.ORIGINAL && isVideoMimeMp4MuxCompatible(sourceVideoMime)) {
                var passthroughSucceeded = false
                try {
                    onProgress(50, "Muxing lossless original video + Hindi audio...")
                    muxOriginalVideoWithAudio(
                        context = context,
                        videoSourceUri = sourceUri,
                        audioM4aFile = m4aTemp,
                        durationMs = durationMs,
                        outputMp4File = outputMp4File
                    ) { p ->
                        onProgress(50 + (p / 2), "Muxing lossless original video + Hindi audio...")
                    }
                    passthroughSucceeded = true
                } catch (_: Exception) {
                    // Fall back to Media3 Transformer H.264 re-mux/transcode for exotic container framing
                    outputMp4File.delete()
                }
                if (!passthroughSucceeded) {
                    runTransformerWithSafeFallback(
                        context = context,
                        videoSourceUri = sourceUri,
                        audioM4aFile = m4aTemp,
                        targetHeight = null,
                        outputMp4File = outputMp4File,
                        mediaAccelSettings = mediaAccelSettings,
                        initialStage = "Converting container to MP4 (${MediaAccelMode.displayLabel(mediaAccelSettings.mode)})...",
                        renderStagePrefix = "Rendering Hindi dubbed MP4",
                        onProgress = onProgress
                    )
                }
            } else {
                val targetHeight: Int? = when (quality) {
                    ExportQuality.ORIGINAL -> null
                    ExportQuality.HD_1080P -> 1080
                    ExportQuality.HD_720P -> 720
                    ExportQuality.SD_480P -> 480
                    else -> 720
                }
                val label = if (targetHeight != null) "${targetHeight}p" else "Original Res"
                runTransformerWithSafeFallback(
                    context = context,
                    videoSourceUri = sourceUri,
                    audioM4aFile = m4aTemp,
                    targetHeight = targetHeight,
                    outputMp4File = outputMp4File,
                    mediaAccelSettings = mediaAccelSettings,
                    initialStage = "Transcoding video ($label • ${MediaAccelMode.displayLabel(mediaAccelSettings.mode)})...",
                    renderStagePrefix = "Rendering $label Hindi dubbed MP4",
                    onProgress = onProgress
                )
            }
        } finally {
            m4aTemp.delete()
        }

        onProgress(100, "Export Complete")
        return outputMp4File
    }

    private suspend fun runTransformerWithSafeFallback(
        context: Context,
        videoSourceUri: Uri,
        audioM4aFile: File,
        targetHeight: Int?,
        outputMp4File: File,
        mediaAccelSettings: MediaAccelSettings,
        initialStage: String,
        renderStagePrefix: String,
        onProgress: (Int, String) -> Unit
    ) {
        onProgress(50, initialStage)
        try {
            exportWithMedia3Transformer(
                context = context,
                videoSourceUri = videoSourceUri,
                audioM4aFile = audioM4aFile,
                targetHeight = targetHeight,
                outputMp4File = outputMp4File,
                mediaAccelSettings = mediaAccelSettings
            ) { p ->
                onProgress(50 + (p / 2), "$renderStagePrefix...")
            }
        } catch (err: Exception) {
            val canRetrySoftware = mediaAccelSettings.allowCodecFallback &&
                mediaAccelSettings.mode != MediaAccelMode.SOFTWARE_SAFE &&
                err !is kotlinx.coroutines.CancellationException
            if (!canRetrySoftware) throw err

            outputMp4File.delete()
            onProgress(50, "Hardware codec stalled — retrying with Media3 Software Safe mode...")
            val safeSettings = mediaAccelSettings.copy(
                mode = MediaAccelMode.SOFTWARE_SAFE,
                asyncCodecQueueing = false,
                highOperatingRate = false,
                transformerMuxerTimeoutSec = maxOf(mediaAccelSettings.transformerMuxerTimeoutSec, 60)
            )
            exportWithMedia3Transformer(
                context = context,
                videoSourceUri = videoSourceUri,
                audioM4aFile = audioM4aFile,
                targetHeight = targetHeight,
                outputMp4File = outputMp4File,
                mediaAccelSettings = safeSettings
            ) { p ->
                onProgress(50 + (p / 2), "$renderStagePrefix (Software Fallback)...")
            }
        }
    }

    /**
     * Lossless video track passthrough muxer: copies original compressed video frames untouched
     * alongside the newly encoded Hindi dubbed AAC track.
     */
    private suspend fun muxOriginalVideoWithAudio(
        context: Context,
        videoSourceUri: Uri,
        audioM4aFile: File,
        durationMs: Long,
        outputMp4File: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(context, videoSourceUri, null)
            audioExtractor.setDataSource(audioM4aFile.absolutePath)

            var srcVideoTrack = -1
            var videoRotation = 0
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    srcVideoTrack = i
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        videoRotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    break
                }
            }
            require(srcVideoTrack >= 0) { "No video track found in source movie." }
            videoExtractor.selectTrack(srcVideoTrack)

            var srcAudioTrack = -1
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    srcAudioTrack = i
                    break
                }
            }
            require(srcAudioTrack >= 0) { "No audio track found in encoded dubbed M4A." }
            audioExtractor.selectTrack(srcAudioTrack)

            val outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(srcVideoTrack))
            val outAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(srcAudioTrack))
            if (videoRotation != 0) {
                muxer.setOrientationHint(videoRotation)
            }
            muxer.start()
            muxerStarted = true

            val maxDurationUs = (durationMs * 1000L).coerceAtLeast(1L)
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1 MB sample buffer
            val info = MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = false

            while (!videoDone || !audioDone) {
                coroutineContext.ensureActive()
                val vTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val aTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoDone && (audioDone || vTime <= aTime)) {
                    buffer.clear()
                    val sz = videoExtractor.readSampleData(buffer, 0)
                    if (sz < 0) {
                        videoDone = true
                    } else {
                        info.offset = 0
                        info.size = sz
                        info.presentationTimeUs = vTime.coerceAtLeast(0L)
                        info.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(outVideoTrack, buffer, info)
                        videoExtractor.advance()
                        onProgress(((vTime * 100) / maxDurationUs).toInt().coerceIn(0, 99))
                    }
                } else if (!audioDone) {
                    buffer.clear()
                    val sz = audioExtractor.readSampleData(buffer, 0)
                    if (sz < 0) {
                        audioDone = true
                    } else {
                        info.offset = 0
                        info.size = sz
                        info.presentationTimeUs = aTime.coerceAtLeast(0L)
                        info.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(outAudioTrack, buffer, info)
                        audioExtractor.advance()
                    }
                }
            }
            onProgress(100)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun exportWithMedia3Transformer(
        context: Context,
        videoSourceUri: Uri,
        audioM4aFile: File,
        targetHeight: Int?,
        outputMp4File: File,
        mediaAccelSettings: MediaAccelSettings = MediaAccelSettings(),
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.Main) {
        outputMp4File.delete()
        suspendCancellableCoroutine<Unit> { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            val videoItemBuilder = EditedMediaItem.Builder(MediaItem.fromUri(videoSourceUri))
                .setRemoveAudio(true)
            if (targetHeight != null) {
                videoItemBuilder.setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForHeight(targetHeight))
                    )
                )
            }
            val videoItem = videoItemBuilder.build()
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioM4aFile)))
                .build()

            val videoSeq = EditedMediaItemSequence.withVideoFrom(listOf(videoItem))
            val audioSeq = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
            val composition = Composition.Builder(videoSeq, audioSeq)
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()

            lateinit var progressRunnable: Runnable
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.removeCallbacks(progressRunnable)
                    onProgress(100)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacks(progressRunnable)
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }

            val transformer = Media3HardwareConfig.buildTransformer(
                context = context,
                settings = mediaAccelSettings,
                listener = listener
            )

            val holder = ProgressHolder()
            progressRunnable = object : Runnable {
                override fun run() {
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress.coerceIn(0, 99))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 400L)
                    }
                }
            }

            transformer.start(composition, outputMp4File.absolutePath)
            mainHandler.post(progressRunnable)

            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(progressRunnable)
                mainHandler.post { runCatching { transformer.cancel() } }
            }
        }
    }

    suspend fun saveToDownloads(context: Context, mp4File: File, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HindiDubStudio")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out ->
                    mp4File.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                null
            }
        }

    suspend fun copyToUri(context: Context, mp4File: File, targetUri: Uri) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                mp4File.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Unable to open destination file for writing.")
        }

    fun getShareUri(context: Context, mp4File: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            mp4File
        )
    }
}
