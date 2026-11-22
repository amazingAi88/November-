package com.example

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DubbingSpeedMode
import com.example.data.local.MediaAccelMode
import com.example.data.local.PreferencesRepository
import com.example.data.local.SttEngineMode
import com.example.media.Media3HardwareConfig
import com.example.media.MediaAccelSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Media3HardwareConfigRobolectricTest {

    @Test
    fun `preferences repository persists Media3 hardware acceleration settings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = PreferencesRepository(context)

        val initial = repo.preferencesFlow.first()
        assertEquals(300, initial.defaultChunkDurationSec)
        assertEquals(DubbingSpeedMode.FULL_GGUF, initial.dubbingSpeedMode)
        assertEquals(SttEngineMode.WHISPER_BASE_EN, initial.sttEngineMode)
        assertEquals(MediaAccelMode.HARDWARE_PRIORITY, initial.mediaAccelMode)
        assertTrue(initial.mediaAsyncCodecQueueing)
        assertTrue(initial.mediaHighOperatingRate)
        assertTrue(initial.mediaAllowCodecFallback)
        assertEquals(30, initial.mediaTransformerTimeoutSec)

        repo.setDubbingSpeedMode(DubbingSpeedMode.HYBRID_TURBO)
        repo.setMediaAccelMode(MediaAccelMode.SOFTWARE_SAFE)
        repo.setMediaAsyncCodecQueueing(false)
        repo.setMediaHighOperatingRate(false)
        repo.setMediaAllowCodecFallback(false)
        repo.setMediaTransformerTimeoutSec(0) // Unlimited muxer timeout

        val updated = repo.preferencesFlow.first()
        assertEquals(DubbingSpeedMode.HYBRID_TURBO, updated.dubbingSpeedMode)
        assertEquals(MediaAccelMode.SOFTWARE_SAFE, updated.mediaAccelMode)
        assertFalse(updated.mediaAsyncCodecQueueing)
        assertFalse(updated.mediaHighOperatingRate)
        assertFalse(updated.mediaAllowCodecFallback)
        assertEquals(0, updated.mediaTransformerTimeoutSec)

        val badge = MediaAccelSettings.fromPreferences(updated).summaryBadge()
        assertTrue(badge.contains("Software Safe"))
        assertTrue(badge.contains("No Timeout"))
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun `Media3HardwareConfig builds ExoPlayer and Transformer cleanly across all acceleration profiles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyListener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = Unit
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) = Unit
        }

        for (mode in MediaAccelMode.all) {
            val settings = MediaAccelSettings(
                mode = mode,
                asyncCodecQueueing = mode != MediaAccelMode.SOFTWARE_SAFE,
                highOperatingRate = mode == MediaAccelMode.HARDWARE_PRIORITY,
                allowCodecFallback = true,
                transformerMuxerTimeoutSec = if (mode == MediaAccelMode.SOFTWARE_SAFE) 0 else 30
            )
            val exo = Media3HardwareConfig.buildExoPlayer(context, settings)
            assertNotNull(exo)
            exo.release()

            val transformer = Media3HardwareConfig.buildTransformer(context, settings, dummyListener)
            assertNotNull(transformer)
        }

        // Codec inspection returns non-negative counts safely on JVM/device
        val summary = Media3HardwareConfig.inspectDeviceHardwareCodecs()
        assertTrue(summary.hwVideoDecoderCount >= 0)
        assertTrue(summary.hwVideoEncoderCount >= 0)
    }
}
