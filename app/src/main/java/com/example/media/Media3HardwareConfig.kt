package com.example.media

import android.content.Context
import android.media.MediaCodecInfo as PlatformMediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.data.local.MediaAccelMode
import com.example.data.local.StudioPreferences
import com.google.common.collect.ImmutableList

private const val TAG = "Media3HardwareConfig"

data class MediaAccelSettings(
    val mode: String = MediaAccelMode.HARDWARE_PRIORITY,
    val asyncCodecQueueing: Boolean = true,
    val highOperatingRate: Boolean = true,
    val allowCodecFallback: Boolean = true,
    val transformerMuxerTimeoutSec: Int = 30
) {
    fun summaryBadge(): String {
        val parts = mutableListOf(MediaAccelMode.displayLabel(mode))
        if (asyncCodecQueueing && mode != MediaAccelMode.SOFTWARE_SAFE) parts.add("Async Queue")
        if (highOperatingRate && mode != MediaAccelMode.SOFTWARE_SAFE) parts.add("Max Clock")
        val timeoutText = if (transformerMuxerTimeoutSec <= 0) "No Timeout" else "${transformerMuxerTimeoutSec}s Guard"
        parts.add(timeoutText)
        return parts.joinToString(" • ")
    }

    companion object {
        fun fromPreferences(prefs: StudioPreferences): MediaAccelSettings = MediaAccelSettings(
            mode = prefs.mediaAccelMode,
            asyncCodecQueueing = prefs.mediaAsyncCodecQueueing,
            highOperatingRate = prefs.mediaHighOperatingRate,
            allowCodecFallback = prefs.mediaAllowCodecFallback,
            transformerMuxerTimeoutSec = prefs.mediaTransformerTimeoutSec
        )
    }
}

data class MediaCodecCapabilitiesSummary(
    val hwVideoDecoderCount: Int,
    val swVideoDecoderCount: Int,
    val hwVideoEncoderCount: Int,
    val swVideoEncoderCount: Int,
    val primaryHwVideoDecoder: String?,
    val primaryHwVideoEncoder: String?
) {
    val hasHardwareAcceleration: Boolean
        get() = hwVideoDecoderCount > 0 && hwVideoEncoderCount > 0
}

/**
 * Configures Media3 [Transformer] and [ExoPlayer] hardware-acceleration pipelines:
 * - Prioritizes hardware-accelerated MediaCodec decoders & encoders (or software-safe fallback)
 * - Controls asynchronous MediaCodec buffer queueing & dynamic scheduling
 * - Configures operating-rate hints & muxer stall timeout guards to prevent hangs on heavy dubbing exports
 */
object Media3HardwareConfig {

    fun inspectDeviceHardwareCodecs(): MediaCodecCapabilitiesSummary {
        return runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            var hwDec = 0
            var swDec = 0
            var hwEnc = 0
            var swEnc = 0
            var firstHwDec: String? = null
            var firstHwEnc: String? = null

            for (info in infos) {
                val videoTypes = info.supportedTypes.filter { it.startsWith("video/", ignoreCase = true) }
                if (videoTypes.isEmpty()) continue
                val isHw = isPlatformCodecHardware(info)
                if (info.isEncoder) {
                    if (isHw) {
                        hwEnc++
                        if (firstHwEnc == null) firstHwEnc = info.name
                    } else {
                        swEnc++
                    }
                } else {
                    if (isHw) {
                        hwDec++
                        if (firstHwDec == null) firstHwDec = info.name
                    } else {
                        swDec++
                    }
                }
            }

            MediaCodecCapabilitiesSummary(
                hwVideoDecoderCount = hwDec,
                swVideoDecoderCount = swDec,
                hwVideoEncoderCount = hwEnc,
                swVideoEncoderCount = swEnc,
                primaryHwVideoDecoder = firstHwDec,
                primaryHwVideoEncoder = firstHwEnc
            )
        }.getOrElse {
            MediaCodecCapabilitiesSummary(0, 0, 0, 0, null, null)
        }
    }

    internal fun isPlatformCodecHardware(info: PlatformMediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        val lower = info.name.lowercase()
        val isSoftwareName = lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") ||
            lower.contains(".sw.") ||
            lower.endsWith(".sw")
        return !isSoftwareName
    }

    @OptIn(UnstableApi::class)
    fun buildMediaCodecSelector(settings: MediaAccelSettings): MediaCodecSelector {
        return when (settings.mode) {
            MediaAccelMode.SOFTWARE_SAFE -> MediaCodecSelector.PREFER_SOFTWARE
            MediaAccelMode.BALANCED_AUTO -> MediaCodecSelector.DEFAULT
            else -> MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val rawInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                // Strictly float hardware-accelerated, non-software decoders to the front
                rawInfos.sortedBy { info ->
                    when {
                        info.hardwareAccelerated && !info.softwareOnly -> 0
                        !info.softwareOnly -> 1
                        else -> 2
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun buildVideoEncoderSelector(settings: MediaAccelSettings): EncoderSelector {
        return when (settings.mode) {
            MediaAccelMode.SOFTWARE_SAFE -> EncoderSelector { mimeType ->
                val all = EncoderUtil.getSupportedEncoders(mimeType)
                val swFirst = all.sortedBy { info ->
                    if (EncoderUtil.isHardwareAccelerated(info, mimeType)) 1 else 0
                }
                ImmutableList.copyOf(swFirst)
            }
            MediaAccelMode.BALANCED_AUTO -> EncoderSelector.DEFAULT
            else -> EncoderSelector { mimeType ->
                val all = EncoderUtil.getSupportedEncoders(mimeType)
                val hwFirst = all.sortedBy { info ->
                    when {
                        EncoderUtil.isHardwareAccelerated(info, mimeType) && isPlatformCodecHardware(info) -> 0
                        EncoderUtil.isHardwareAccelerated(info, mimeType) -> 1
                        else -> 2
                    }
                }
                ImmutableList.copyOf(hwFirst)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun buildExoPlayer(context: Context, settings: MediaAccelSettings): ExoPlayer {
        val useAsync = settings.asyncCodecQueueing && settings.mode != MediaAccelMode.SOFTWARE_SAFE
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setMediaCodecSelector(buildMediaCodecSelector(settings))
            setEnableDecoderFallback(settings.allowCodecFallback)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableAudioTrackPlaybackParams(true)
            setEnableAudioOutputPlaybackParameters(true)
            if (useAsync) {
                forceEnableMediaCodecAsynchronousQueueing()
            } else {
                forceDisableMediaCodecAsynchronousQueueing()
            }
        }

        Log.d(
            TAG,
            "Building ExoPlayer: mode=${settings.mode}, asyncQueue=$useAsync, fallback=${settings.allowCodecFallback}"
        )

        return ExoPlayer.Builder(context, renderersFactory)
            .setPriority(C.PRIORITY_PLAYBACK)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .experimentalSetDynamicSchedulingEnabled(
                useAsync && settings.mode == MediaAccelMode.HARDWARE_PRIORITY
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    fun buildTransformer(
        context: Context,
        settings: MediaAccelSettings,
        listener: Transformer.Listener
    ): Transformer {
        val isHwPriority = settings.mode == MediaAccelMode.HARDWARE_PRIORITY
        val isSwSafe = settings.mode == MediaAccelMode.SOFTWARE_SAFE
        val codecPriority = if (isHwPriority) {
            C.PRIORITY_MAX
        } else {
            C.PRIORITY_PROCESSING_FOREGROUND
        }

        val encoderSettings = if (settings.highOperatingRate && !isSwSafe) {
            // Let Media3's DEFAULT VideoEncoderSettings apply safe hardware operating-rate + chip overflow workarounds
            VideoEncoderSettings.DEFAULT
        } else {
            VideoEncoderSettings.Builder()
                .setEncoderPerformanceParameters(
                    VideoEncoderSettings.RATE_UNSET,
                    VideoEncoderSettings.RATE_UNSET
                )
                .build()
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setVideoEncoderSelector(buildVideoEncoderSelector(settings))
            .setRequestedVideoEncoderSettings(encoderSettings)
            .setEnableFallback(settings.allowCodecFallback)
            .setEnableFormatFallback(settings.allowCodecFallback)
            .setCodecPriority(codecPriority)
            .build()

        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setMediaCodecSelector(buildMediaCodecSelector(settings))
            .setEnableDecoderFallback(settings.allowCodecFallback)
            .setShouldConfigureOperatingRate(settings.highOperatingRate && !isSwSafe)
            .setCodecPriority(codecPriority)
            .experimentalSetDynamicSchedulingEnabled(settings.asyncCodecQueueing && isHwPriority)
            .build()

        val assetLoaderFactory = DefaultAssetLoaderFactory(
            context,
            decoderFactory,
            Clock.DEFAULT,
            /* logSessionId= */ null
        )

        val maxDelayBetweenMuxerSamplesMs = if (settings.transformerMuxerTimeoutSec <= 0) {
            C.TIME_UNSET
        } else {
            settings.transformerMuxerTimeoutSec.toLong() * 1000L
        }

        Log.i(
            TAG,
            "Building Transformer: mode=${settings.mode}, highOpRate=${settings.highOperatingRate && !isSwSafe}, " +
                "dynamicSched=${settings.asyncCodecQueueing && isHwPriority}, fallback=${settings.allowCodecFallback}, " +
                "muxerTimeoutMs=$maxDelayBetweenMuxerSamplesMs"
        )

        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setAssetLoaderFactory(assetLoaderFactory)
            .setMaxDelayBetweenMuxerSamplesMs(maxDelayBetweenMuxerSamplesMs)
            .addListener(listener)
            .build()
    }
}
