package com.example

import com.example.ai.LocalLlmModelManager
import com.example.ai.OnDeviceSpeakerAnalyzer
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DialogueStyle
import com.example.data.local.EngineMode
import com.example.media.AudioMixer
import com.example.media.AudioSeparator
import com.example.media.MediaInspector
import com.example.media.VideoExporter
import com.example.media.WavUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `wav write and read preserves format and duration`() {
        val outFile = tempFolder.newFile("test.wav")
        val sampleRate = 44100
        val channels = 2
        val frames = 44100 // 1 second
        val shorts = ShortArray(frames * channels) { idx ->
            val frame = idx / channels
            (sin(2.0 * PI * 440.0 * frame / sampleRate) * 10000).toInt().toShort()
        }

        WavUtils.writePcmBytesAsWav(
            pcmBytes = WavUtils.shortArrayToBytes(shorts),
            sampleRate = sampleRate,
            channels = channels,
            outFile = outFile
        )

        val header = WavUtils.readHeader(outFile)
        assertEquals(44100, header.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(16, header.bitsPerSample)
        assertEquals(1000L, header.durationMs)
    }

    @Test
    fun `stereo mid-side separator suppresses centered speech while preserving side BGM`() = runTest {
        val sampleRate = 44100
        val channels = 2
        val frames = sampleRate * 2 // 2 seconds: 0..1s speech+BGM, 1..2s BGM only
        val samples = ShortArray(frames * channels)

        for (f in 0 until frames) {
            // Side stereo BGM at 150 Hz (panned L/R opposite phase -> pure Side)
            val bgm = sin(2.0 * PI * 150.0 * f / sampleRate) * 6000.0
            // Centered speech-band 1000 Hz (identical L & R -> pure Mid)
            val speech = sin(2.0 * PI * 1000.0 * f / sampleRate) * 12000.0
            samples[f * 2] = (speech + bgm).toInt().coerceIn(-32768, 32767).toShort()
            samples[f * 2 + 1] = (speech - bgm).toInt().coerceIn(-32768, 32767).toShort()
        }

        val origWav = tempFolder.newFile("orig.wav")
        val bgmWav = tempFolder.newFile("bgm.wav")
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(samples), sampleRate, channels, origWav)

        val segs = listOf(
            DialogueSegmentEntity(
                projectId = 1L,
                chunkIndex = 0,
                orderIndex = 0,
                startMs = 0L,
                endMs = 1000L,
                speakerLabel = "SPEAKER_1",
                englishText = "Hello world",
                audioType = "SPEECH"
            )
        )

        AudioSeparator.separateBgmAndSfx(
            origWavFile = origWav,
            bgmWavFile = bgmWav,
            segments = segs,
            chunkStartMs = 0L,
            sourceChannels = 2,
            preserveBgm = true,
            removeEnglishDialogue = true
        )

        val hdr = WavUtils.readHeader(bgmWav)
        assertEquals(origWav.length(), bgmWav.length())
        assertEquals(2000L, hdr.durationMs)

        // Compare RMS in 0..1s (speech active) vs original: mid-side should significantly reduce total mid speech
        val outBytes = bgmWav.readBytes().copyOfRange(hdr.dataOffset.toInt(), bgmWav.length().toInt())
        val outShorts = WavUtils.bytesToShortArray(outBytes)

        var origMidEnergy = 0.0
        var outMidEnergy = 0.0
        for (f in 4410 until 40000) { // inside 0.1s..0.9s after ramp
            val origMid = (samples[f * 2] + samples[f * 2 + 1]) * 0.5
            val outMid = (outShorts[f * 2] + outShorts[f * 2 + 1]) * 0.5
            origMidEnergy += abs(origMid)
            outMidEnergy += abs(outMid)
        }
        assertTrue("Expected centered speech Mid energy to be suppressed", outMidEnergy < origMidEnergy * 0.4)
    }

    @Test
    fun `mixer places hindi TTS at exact timestamp and preserves chunk length`() = runTest {
        val sampleRate = 44100
        val channels = 2
        val origShorts = ShortArray(sampleRate * 2 * channels) { 200 }
        val ttsShorts = ShortArray((sampleRate / 2) * channels) { 10000 }

        val origWav = tempFolder.newFile("mix_orig.wav")
        val bgmWav = tempFolder.newFile("mix_bgm.wav")
        val ttsWav = tempFolder.newFile("seg_tts.wav")
        val outWav = tempFolder.newFile("mixed.wav")

        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(origShorts), sampleRate, channels, origWav)
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(origShorts), sampleRate, channels, bgmWav)
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(ttsShorts), sampleRate, channels, ttsWav)

        val seg = DialogueSegmentEntity(
            id = 10L,
            projectId = 1L,
            chunkIndex = 0,
            orderIndex = 0,
            startMs = 500L,
            endMs = 1000L,
            speakerLabel = "SPEAKER_1",
            englishText = "Test",
            hindiText = "परीक्षण",
            audioType = "SPEECH"
        )

        AudioMixer.mixChunk(
            origWavFile = origWav,
            bgmWavFile = bgmWav,
            ttsSegments = listOf(AudioMixer.PositionedTts(seg, ttsWav)),
            chunkStartMs = 0L,
            hindiVolume = 1.0f,
            bgmVolume = 0.5f,
            originalVolume = 0.0f,
            outputMixedWav = outWav
        )

        val outHdr = WavUtils.readHeader(outWav)
        assertEquals(2000L, outHdr.durationMs)
    }

    @Test
    fun `on-device speaker analyzer distinguishes male low pitch vs female higher pitch`() {
        val sampleRate = 16000
        val totalFrames = sampleRate * 3 // 0..1s 110Hz (Male), 1..2s silence, 2..3s 220Hz (Female)
        val pcm = ShortArray(totalFrames)
        for (i in 0 until sampleRate) {
            pcm[i] = (sin(2.0 * PI * 110.0 * i / sampleRate) * 14000).toInt().toShort()
        }
        for (i in sampleRate * 2 until sampleRate * 3) {
            pcm[i] = (sin(2.0 * PI * 220.0 * i / sampleRate) * 14000).toInt().toShort()
        }
        val sttWav = tempFolder.newFile("pitch_test.wav")
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(pcm), sampleRate, 1, sttWav)

        val result = OnDeviceSpeakerAnalyzer.analyzeSpeakersAndBuildTimeline(
            sttWavFile = sttWav,
            phrases = listOf(
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(50L, 950L, "Hello there"),
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(2050L, 2950L, "How are you")
            ),
            chunkDurationMs = 3000L
        )

        val speechSegs = result.segments.filter { it.audioType == "SPEECH" }
        assertEquals(2, speechSegs.size)
        val maleSpeaker = result.speakers.find { it.speakerLabel == speechSegs[0].speakerLabel }
        val femaleSpeaker = result.speakers.find { it.speakerLabel == speechSegs[1].speakerLabel }
        assertEquals("MALE", maleSpeaker?.inferredGender)
        assertEquals("FEMALE", femaleSpeaker?.inferredGender)
        assertTrue(result.segments.any { it.audioType == "SILENCE" })
    }

    @Test
    fun `multi-format container and MP4 mux compatibility detection handles MKV WebM AVI MOV TS FLV`() {
        assertEquals("MKV (Matroska)", MediaInspector.detectContainerFormat("film.mkv", "video/x-matroska", "video/avc"))
        assertEquals("WebM", MediaInspector.detectContainerFormat("clip.webm", "video/webm", "video/x-vnd.on2.vp9"))
        assertEquals("AVI", MediaInspector.detectContainerFormat("old_movie.avi", "video/x-msvideo", "video/mp4v-es"))
        assertEquals("MOV (QuickTime)", MediaInspector.detectContainerFormat("cam.mov", "video/quicktime", "video/hevc"))
        assertEquals("MPEG-TS", MediaInspector.detectContainerFormat("stream.ts", "video/mp2t", "video/avc"))

        assertTrue(VideoExporter.isVideoMimeMp4MuxCompatible("video/avc"))
        assertTrue(VideoExporter.isVideoMimeMp4MuxCompatible("video/hevc"))
        assertFalse(VideoExporter.isVideoMimeMp4MuxCompatible("video/x-vnd.on2.vp9"))
        assertFalse(VideoExporter.isVideoMimeMp4MuxCompatible("video/x-vnd.on2.vp8"))
    }

    @Test
    fun `GGUF validation rejects HTML error pages and tiny stubs`() {
        val fakeHtml = tempFolder.newFile("bad.gguf").apply {
            writeBytes("<html>404 Not Found</html>".toByteArray() + ByteArray(11 * 1024 * 1024))
        }
        val tinyGguf = tempFolder.newFile("tiny.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 0, 1))
        }
        val validHeaderOnly = tempFolder.newFile("valid.gguf").apply {
            val bytes = ByteArray(11 * 1024 * 1024)
            bytes[0] = 'G'.code.toByte()
            bytes[1] = 'G'.code.toByte()
            bytes[2] = 'U'.code.toByte()
            bytes[3] = 'F'.code.toByte()
            writeBytes(bytes)
        }

        assertFalse(LocalLlmModelManager.isValidGgufFile(fakeHtml))
        assertFalse(LocalLlmModelManager.isValidGgufFile(tinyGguf))
        assertTrue(LocalLlmModelManager.isValidGgufFile(validHeaderOnly))
        assertTrue(EngineMode.ON_DEVICE_LLM in EngineMode.all)
        assertTrue(DialogueStyle.RAW_UNCENSORED in DialogueStyle.all)
    }

    @Test
    fun `LocalGgufTranslator strips think blocks, English preambles, and detects refusals`() {
        val withThinkAndPrefix = """
            <think>Let me translate this colloquial movie line carefully.</think>
            Sure, here is the translation:
            Hindi: "यहाँ से दफ़ा हो जाओ!"
        """.trimIndent()
        assertEquals(
            "यहाँ से दफ़ा हो जाओ!",
            com.example.ai.LocalGgufTranslator.extractBestHindiLine(withThinkAndPrefix)
        )

        assertTrue(com.example.ai.LocalGgufTranslator.isRefusalOrMissingHindi("I cannot translate explicit content."))
        assertTrue(com.example.ai.LocalGgufTranslator.isRefusalOrMissingHindi("Yahan se dafa ho jao"))
        assertFalse(com.example.ai.LocalGgufTranslator.isRefusalOrMissingHindi("यहाँ से दफ़ा हो जाओ!"))
    }

    @Test
    fun `LocalGgufTranslator fast short-utterance mapper translates fillers with punctuation`() {
        assertEquals("हाँ!", com.example.ai.LocalGgufTranslator.tryFastShortUtteranceHindi("Yeah!"))
        assertEquals("रुको", com.example.ai.LocalGgufTranslator.tryFastShortUtteranceHindi("Wait..."))
        assertEquals("बकवास बंद कर!", com.example.ai.LocalGgufTranslator.tryFastShortUtteranceHindi("Shut up!"))
        assertEquals(null, com.example.ai.LocalGgufTranslator.tryFastShortUtteranceHindi("Give me the keys right now"))
    }
}
