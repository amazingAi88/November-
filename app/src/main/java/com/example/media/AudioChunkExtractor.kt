package com.example.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

object AudioChunkExtractor {
    const val MIX_SAMPLE_RATE = 44100
    const val MIX_CHANNELS = 2
    const val STT_SAMPLE_RATE = 16000
    const val STT_CHANNELS = 1

    /**
     * Decodes [startMs, endMs] of the audio track from [sourceUri] into:
     * - [origWavFile]: 44.1kHz Stereo 16-bit PCM WAV (for separation & final mixing)
     * - [sttWavFile]: 16kHz Mono 16-bit PCM WAV (compact <20MB payload for Gemini STT)
     */
    suspend fun extractChunk(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        origWavFile: File,
        sttWavFile: File
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            require(audioTrackIndex >= 0) { "No audio track found in movie." }
            extractor.selectTrack(audioTrackIndex)

            val trackFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track MIME type is missing")

            var codecSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var codecChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val seekStartUs = ((startMs - 400L).coerceAtLeast(0L)) * 1000L
            extractor.seekTo(seekStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(trackFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Audio codec '$mime' in this video file is not supported by your phone's built-in decoders.",
                    e
                )
            }

            origWavFile.parentFile?.mkdirs()
            sttWavFile.parentFile?.mkdirs()

            var origDataBytes = 0L
            var sttDataBytes = 0L

            RandomAccessFile(origWavFile, "rw").use { origRaf ->
                RandomAccessFile(sttWavFile, "rw").use { sttRaf ->
                    origRaf.setLength(0)
                    sttRaf.setLength(0)
                    WavUtils.writeHeader(origRaf, MIX_SAMPLE_RATE, MIX_CHANNELS, 16, 0L)
                    WavUtils.writeHeader(sttRaf, STT_SAMPLE_RATE, STT_CHANNELS, 16, 0L)

                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    val startUs = startMs * 1000L
                    val endUs = endMs * 1000L
                    var idleCycles = 0

                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        var madeProgress = false

                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                madeProgress = true
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                val sampleTimeUs = extractor.sampleTime
                                if (sampleSize < 0 || sampleTimeUs > endUs + 500_000L) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        inIdx, 0, sampleSize, sampleTimeUs, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                madeProgress = true
                                val outFmt = codec.outputFormat
                                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    codecSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    codecChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                            }
                            outIdx >= 0 -> {
                                madeProgress = true
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null && bufferInfo.size > 0) {
                                    val ptsUs = bufferInfo.presentationTimeUs
                                    val bytes = ByteArray(bufferInfo.size)
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    outBuf.get(bytes)

                                    val frameDurationUs =
                                        (bufferInfo.size.toLong() * 1_000_000L) /
                                            (codecSampleRate.toLong() * codecChannels * 2L).coerceAtLeast(1L)
                                    val bufferEndUs = ptsUs + frameDurationUs

                                    if (bufferEndUs > startUs && ptsUs < endUs) {
                                        val pcmShorts = WavUtils.bytesToShortArray(bytes)
                                        val totalFrames = pcmShorts.size / codecChannels.coerceAtLeast(1)

                                        val trimStartFrames = if (ptsUs < startUs) {
                                            (((startUs - ptsUs) * codecSampleRate) / 1_000_000L)
                                                .toInt().coerceIn(0, totalFrames)
                                        } else 0

                                        val trimEndFrames = if (bufferEndUs > endUs) {
                                            (((endUs - ptsUs) * codecSampleRate) / 1_000_000L)
                                                .toInt().coerceIn(trimStartFrames, totalFrames)
                                        } else totalFrames

                                        val validFrameCount = trimEndFrames - trimStartFrames
                                        if (validFrameCount > 0) {
                                            val slice = if (trimStartFrames == 0 && trimEndFrames == totalFrames) {
                                                pcmShorts
                                            } else {
                                                pcmShorts.copyOfRange(
                                                    trimStartFrames * codecChannels,
                                                    trimEndFrames * codecChannels
                                                )
                                            }

                                            val mixShorts = WavUtils.convertAndTimeScalePcm(
                                                input = slice,
                                                srcRate = codecSampleRate,
                                                srcChannels = codecChannels,
                                                dstRate = MIX_SAMPLE_RATE,
                                                dstChannels = MIX_CHANNELS,
                                                timeScale = 1.0f
                                            )
                                            val mixBytes = WavUtils.shortArrayToBytes(mixShorts)
                                            origRaf.write(mixBytes)
                                            origDataBytes += mixBytes.size

                                            val sttShorts = WavUtils.convertAndTimeScalePcm(
                                                input = slice,
                                                srcRate = codecSampleRate,
                                                srcChannels = codecChannels,
                                                dstRate = STT_SAMPLE_RATE,
                                                dstChannels = STT_CHANNELS,
                                                timeScale = 1.0f
                                            )
                                            val sttBytes = WavUtils.shortArrayToBytes(sttShorts)
                                            sttRaf.write(sttBytes)
                                            sttDataBytes += sttBytes.size
                                        }
                                    }
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                        }

                        if (madeProgress) {
                            idleCycles = 0
                        } else {
                            idleCycles++
                            if (idleCycles > 1500) { // ~15s consecutive codec dequeue timeout
                                throw IllegalStateException("Audio decoder stalled on malformed stream segment.")
                            }
                        }
                    }

                    WavUtils.patchDataSize(origRaf, origDataBytes)
                    WavUtils.patchDataSize(sttRaf, sttDataBytes)
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes any encoded audio file (MP3, AAC, OGG, FLAC, etc.) into 44.1kHz Stereo PCM16 WAV.
     */
    suspend fun decodeEncodedAudioToMixWav(
        encodedFile: File,
        outWavFile: File,
        timeScale: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(encodedFile.absolutePath)
            val trackIdx = findAudioTrack(extractor)
            require(trackIdx >= 0) { "No audio track in TTS response file" }
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")

            var srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 24000
            var srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outWavFile.parentFile?.mkdirs()
            var dataBytes = 0L
            RandomAccessFile(outWavFile, "rw").use { raf ->
                raf.setLength(0)
                WavUtils.writeHeader(raf, MIX_SAMPLE_RATE, MIX_CHANNELS, 16, 0L)

                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    coroutineContext.ensureActive()
                    if (!inDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(buf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                val bytes = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                outBuf.get(bytes)

                                val shorts = WavUtils.bytesToShortArray(bytes)
                                val scaled = WavUtils.convertAndTimeScalePcm(
                                    input = shorts,
                                    srcRate = srcRate,
                                    srcChannels = srcChannels,
                                    dstRate = MIX_SAMPLE_RATE,
                                    dstChannels = MIX_CHANNELS,
                                    timeScale = timeScale
                                )
                                val outBytes = WavUtils.shortArrayToBytes(scaled)
                                raf.write(outBytes)
                                dataBytes += outBytes.size
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outDone = true
                            }
                        }
                    }
                }
                WavUtils.patchDataSize(raf, dataBytes)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
