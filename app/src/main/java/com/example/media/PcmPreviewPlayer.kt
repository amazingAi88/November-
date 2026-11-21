package com.example.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

data class AudioPlaybackState(
    val activeTag: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class PcmPreviewPlayer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    private var playJob: Job? = null
    private var externalStopCallback: (() -> Unit)? = null

    fun playWav(file: File, tag: String) {
        if (_state.value.activeTag == tag && _state.value.isPlaying) {
            stop()
            return
        }
        stop()
        if (!file.exists()) return

        playJob = scope.launch(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val header = WavUtils.readHeader(file)
                val channelMask = if (header.channels >= 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    header.sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(header.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                val durationMs = header.durationMs
                _state.value = AudioPlaybackState(tag, isPlaying = true, positionMs = 0L, durationMs = durationMs)

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(header.dataOffset)
                    val buf = ByteArray(4096)
                    var remaining = header.dataSize
                    var playedBytes = 0L
                    while (remaining > 0 && isActive) {
                        val toRead = minOf(remaining, buf.size.toLong()).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        track.write(buf, 0, read)
                        remaining -= read
                        playedBytes += read
                        val posMs = if (header.sampleRate > 0 && header.bytesPerFrame > 0) {
                            (playedBytes * 1000L) / (header.sampleRate.toLong() * header.bytesPerFrame)
                        } else 0L
                        _state.value = AudioPlaybackState(tag, isPlaying = true, positionMs = posMs, durationMs = durationMs)
                    }
                }
            } catch (_: Exception) {
                // Playback ended or interrupted
            } finally {
                runCatching {
                    track?.pause()
                    track?.flush()
                    track?.stop()
                    track?.release()
                }
                _state.value = AudioPlaybackState()
            }
        }
    }

    fun startExternalPlayback(
        tag: String,
        estimatedDurationMs: Long,
        onStop: () -> Unit
    ) {
        stop()
        externalStopCallback = onStop
        val dur = estimatedDurationMs.coerceAtLeast(800L)
        _state.value = AudioPlaybackState(activeTag = tag, isPlaying = true, positionMs = 0L, durationMs = dur)
        playJob = scope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            while (isActive && _state.value.activeTag == tag && _state.value.isPlaying) {
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtMost(dur)
                _state.value = AudioPlaybackState(
                    activeTag = tag,
                    isPlaying = true,
                    positionMs = elapsed,
                    durationMs = dur
                )
                delay(100L)
            }
        }
    }

    fun finishExternalPlayback(tag: String) {
        if (_state.value.activeTag == tag) {
            playJob?.cancel()
            playJob = null
            externalStopCallback = null
            _state.value = AudioPlaybackState()
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        externalStopCallback?.let { cb ->
            externalStopCallback = null
            runCatching { cb() }
        }
        _state.value = AudioPlaybackState()
    }
}
