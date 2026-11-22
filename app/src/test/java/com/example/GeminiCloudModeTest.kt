package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiDubbingService
import com.example.data.local.EngineMode
import com.example.data.local.PreferencesRepository
import com.example.security.configureAppCheck
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiCloudModeTest {

    @Test
    fun `parseTranscriptionJson parses speakers and sorts segments chronologically`() {
        val service = GeminiDubbingService()
        val rawJson = """
            {
              "speakers": [
                {"speakerId": "SPEAKER_1", "inferredGender": "male", "traits": "Deep hero voice"},
                {"speakerId": "SPEAKER_2", "inferredGender": "female", "traits": "Clear voice"}
              ],
              "segments": [
                {
                  "startOffsetMs": 4200,
                  "endOffsetMs": 6800,
                  "speakerId": "SPEAKER_2",
                  "englishText": "Stay behind me!",
                  "emotion": "fearful",
                  "isOverlapping": false,
                  "audioType": "SPEECH"
                },
                {
                  "startOffsetMs": 500,
                  "endOffsetMs": 2200,
                  "speakerId": "SPEAKER_1",
                  "englishText": "Move now!",
                  "emotion": "angry",
                  "isOverlapping": false,
                  "audioType": "speech"
                }
              ]
            }
        """.trimIndent()

        val result = service.parseTranscriptionJson(rawJson, chunkDurationMs = 10_000L)
        assertEquals(2, result.speakers.size)
        assertEquals("MALE", result.speakers[0].inferredGender)
        assertEquals(2, result.segments.size)
        assertEquals(500L, result.segments[0].startOffsetMs)
        assertEquals("SPEECH", result.segments[0].audioType)
        assertEquals("Move now!", result.segments[0].englishText)
        assertEquals(4200L, result.segments[1].startOffsetMs)
    }

    @Test
    fun `parseTranslationJson maps valid segmentIds and drops invalid entries`() {
        val service = GeminiDubbingService()
        val rawJson = """
            {
              "translations": [
                {"segmentId": 12, "hindiText": "तुम क्या कर रहे हो?", "emotion": "ANGRY"},
                {"segmentId": -1, "hindiText": "ignored", "emotion": "neutral"}
              ]
            }
        """.trimIndent()

        val parsed = service.parseTranslationJson(rawJson)
        assertEquals(1, parsed.size)
        assertEquals(12L, parsed[0].segmentId)
        assertEquals("तुम क्या कर रहे हो?", parsed[0].hindiText)
        assertEquals("angry", parsed[0].emotion)
    }

    @Test
    fun `computeEffectiveSpeed fits overflowing Hindi TTS into slot up to 1_45x`() {
        val service = GeminiDubbingService()
        // 3000ms raw audio in a 2000ms slot -> overflows by >4%, should scale up (~1.45x max)
        val scaledUp = service.computeEffectiveSpeed(
            rawFrameCount = 72_000,
            sampleRate = 24_000,
            requestedSpeed = 1.0f,
            targetSlotMs = 2000L
        )
        assertTrue(scaledUp > 1.35f)
        assertTrue(scaledUp <= 1.45f)

        // 1800ms raw audio in a 2000ms slot -> fits naturally at requested 1.0f
        val unchanged = service.computeEffectiveSpeed(
            rawFrameCount = 43_200,
            sampleRate = 24_000,
            requestedSpeed = 1.0f,
            targetSlotMs = 2000L
        )
        assertEquals(1.0f, unchanged, 0.001f)
    }

    @Test
    fun `mapException classifies App Check 403 and quota 429 cleanly`() {
        val service = GeminiDubbingService()

        val appCheckErr = service.mapException("Test", RuntimeException("HTTP 403 App Check token invalid"))
        assertTrue(appCheckErr.isAppCheckOrAuth)
        assertFalse(appCheckErr.isRateLimitOrQuota)

        val quotaErr = service.mapException("Test", RuntimeException("RESOURCE_EXHAUSTED 429 rate limit exceeded"))
        assertTrue(quotaErr.isRateLimitOrQuota)
        assertFalse(quotaErr.isAppCheckOrAuth)
    }

    @Test
    fun `configureAppCheck persists debug token synchronously when launch intent provides extra`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hasGoogleAppId = context.resources.getIdentifier(
            "google_app_id",
            "string",
            context.packageName
        ) != 0
        if (!hasGoogleAppId && FirebaseApp.getApps(context).isEmpty()) {
            // CI checkouts without google-services.json rely on googleServices.missing.passthrough=true;
            // seed a test-only FirebaseApp so configureAppCheck debug persistence can be verified in CI.
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:000000000000:android:0000000000000000000000")
                    .setApiKey("robolectric-unit-test-placeholder")
                    .setProjectId("robolectric-unit-test-project")
                    .build()
            )
        }
        val launchIntent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "test-debug-token-12345")

        configureAppCheck(context, launchIntent)

        val app = FirebaseApp.getApps(context).firstOrNull()
        assertTrue(app != null)
        val persistenceKey = app!!.persistenceKey
        val prefs = context.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.$persistenceKey",
            Context.MODE_PRIVATE
        )
        assertEquals(
            "test-debug-token-12345",
            prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        )
    }

    @Test
    fun `PreferencesRepository switches optional GEMINI_CLOUD and ON_DEVICE_FREE cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = PreferencesRepository(context)

        repo.setEngineMode(EngineMode.GEMINI_CLOUD)
        assertEquals(EngineMode.GEMINI_CLOUD, repo.preferencesFlow.first().defaultEngineMode)

        repo.setEngineMode(EngineMode.ON_DEVICE_FREE)
        assertEquals(EngineMode.ON_DEVICE_FREE, repo.preferencesFlow.first().defaultEngineMode)
    }
}
