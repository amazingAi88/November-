package com.example

import com.example.ai.LocalGgufTranslator
import com.example.ai.OnDeviceModelManager
import com.example.ai.OnDeviceSpeakerAnalyzer
import com.example.ai.ResumableHttpDownloader
import com.example.ai.TranscriptRepairHelper
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DialogueStyle
import com.example.data.local.SpeakerEntity
import com.example.data.local.SubtitleLanguage
import com.example.media.AudioMixer
import com.example.media.SrtSubtitleParser
import com.example.media.SubtitleCue
import com.example.media.VocalIsolationFilter
import com.example.media.WavUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SceneContextAndResumeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `ResumableHttpDownloader sends Range header and resumes from exact partial byte offset`() = runTest {
        val fullPayload = ByteArray(1024) { idx -> (idx % 251).toByte() }
        val partBytesCount = 400
        val capturedRangeHeader = AtomicReference<String?>(null)

        val server = ServerSocket(0)
        val serverThread = thread {
            server.use { srv ->
                val client = srv.accept()
                client.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            capturedRangeHeader.set(line.substringAfter(":").trim())
                        }
                    }
                    val remaining = fullPayload.size - partBytesCount
                    val headers = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Length: $remaining\r\n")
                        append("Content-Range: bytes $partBytesCount-${fullPayload.size - 1}/${fullPayload.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = sock.getOutputStream()
                    out.write(headers.toByteArray(Charsets.US_ASCII))
                    out.write(fullPayload, partBytesCount, remaining)
                    out.flush()
                }
            }
        }

        val partFile = tempFolder.newFile("model.gguf.part")
        partFile.writeBytes(fullPayload.copyOfRange(0, partBytesCount))
        val destFile = tempFolder.newFile("model.gguf").apply { delete() }

        var lastDone = 0L
        var lastTotal = 0L
        val result = ResumableHttpDownloader.downloadToFile(
            url = "http://127.0.0.1:${server.localPort}/model.gguf",
            partFile = partFile,
            destFile = destFile,
            fallbackApproxBytes = fullPayload.size.toLong(),
            reportIntervalBytes = 128L
        ) { done, total ->
            lastDone = done
            lastTotal = total
        }

        serverThread.join(3000L)

        assertEquals("bytes=$partBytesCount-", capturedRangeHeader.get())
        assertTrue(result.exists())
        assertFalse(partFile.exists())
        assertArrayEquals(fullPayload, result.readBytes())
        assertEquals(fullPayload.size.toLong(), lastDone)
        assertEquals(fullPayload.size.toLong(), lastTotal)
    }

    @Test
    fun `ResumableHttpDownloader parses Content-Range headers accurately`() {
        assertEquals(
            1120L * 1024L * 1024L,
            ResumableHttpDownloader.resolveTotalBytes(
                isPartial = true,
                existingBytes = 500_000_000L,
                contentLength = 674_405_120L,
                contentRangeHeader = "bytes 500000000-1174405119/1174405120",
                fallbackApproxBytes = 1000L
            )
        )
        assertEquals(123456L, ResumableHttpDownloader.parse416Total("bytes */123456"))
    }

    @Test
    fun `SrtSubtitleParser parses SRT and VTT cues cleanly and slices by chunk range`() {
        val rawSrt = """
            1
            00:00:02,500 --> 00:00:05,250
            <i>JOHN: What the hell</i>
            are you doing here?

            2
            00:03:01,000 --> 00:03:04,000
            - {\an8}Leave this room right now!
        """.trimIndent()

        val cues = SrtSubtitleParser.parse(rawSrt)
        assertEquals(2, cues.size)
        assertEquals(2_500L, cues[0].startMs)
        assertEquals(5_250L, cues[0].endMs)
        assertEquals("What the hell are you doing here?", cues[0].text)
        assertEquals("Leave this room right now!", cues[1].text)

        val chunk0Slices = SrtSubtitleParser.slicesForChunk(cues, chunkStartMs = 0L, chunkEndMs = 180_000L)
        assertEquals(1, chunk0Slices.size)
        assertEquals(2_500L, chunk0Slices[0].startOffsetMs)

        val chunk1Slices = SrtSubtitleParser.slicesForChunk(cues, chunkStartMs = 180_000L, chunkEndMs = 360_000L)
        assertEquals(1, chunk1Slices.size)
        assertEquals(1_000L, chunk1Slices[0].startOffsetMs)
        assertEquals(4_000L, chunk1Slices[0].endOffsetMs)

        val rawVtt = """
            WEBVTT

            00:10.500 --> 00:13.000
            Let's get out of here.
        """.trimIndent()
        val vttCues = SrtSubtitleParser.parse(rawVtt)
        assertEquals(1, vttCues.size)
        assertEquals(10_500L, vttCues[0].startMs)
        assertEquals("Let's get out of here.", vttCues[0].text)
    }

    @Test
    fun `VocalIsolationFilter suppresses wide stereo low bass while preserving centered speech`() {
        val rate = 16000
        val frames = 16000 // 1 sec
        // Compare pure wide-panned 80 Hz rumble vs centered 1000 Hz speech
        val wideRumble = ShortArray(frames * 2)
        val centeredSpeech = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val rumble = sin(2.0 * PI * 80.0 * i / rate) * 10000.0
            wideRumble[i * 2] = rumble.toInt().toShort()
            wideRumble[i * 2 + 1] = (-rumble * 0.8).toInt().toShort()

            val voice = sin(2.0 * PI * 1000.0 * i / rate) * 10000.0
            centeredSpeech[i * 2] = voice.toInt().toShort()
            centeredSpeech[i * 2 + 1] = voice.toInt().toShort()
        }

        val stateRumble = VocalIsolationFilter.StreamState(rate)
        val filteredRumble = stateRumble.processSttChunk(
            VocalIsolationFilter.extractCenterFocusedMono(wideRumble, rate, 2, rate)
        )

        val stateVoice = VocalIsolationFilter.StreamState(rate)
        val filteredVoice = stateVoice.processSttChunk(
            VocalIsolationFilter.extractCenterFocusedMono(centeredSpeech, rate, 2, rate)
        )

        val rumbleAvg = filteredRumble.drop(2000).map { abs(it.toInt()) }.average()
        val voiceAvg = filteredVoice.drop(2000).map { abs(it.toInt()) }.average()
        assertTrue("Expected voice energy ($voiceAvg) >> wide low-frequency rumble ($rumbleAvg)", voiceAvg > rumbleAvg * 3.0)
    }

    @Test
    fun `TranscriptRepairHelper merges choppy bursts, detects mood, listener gender, and ASR garble`() {
        val rawPhrases = listOf(
            OnDeviceSpeakerAnalyzer.SpeechRawPhrase(100L, 600L, "What what are"),
            OnDeviceSpeakerAnalyzer.SpeechRawPhrase(800L, 1400L, "you doing"),
            OnDeviceSpeakerAnalyzer.SpeechRawPhrase(1500L, 2100L, "oh yeah"),
            OnDeviceSpeakerAnalyzer.SpeechRawPhrase(2600L, 3500L, "I'm leaving now.")
        )
        val merged = TranscriptRepairHelper.mergeAndRepairPhrases(rawPhrases)
        // First two bursts merge (gap = 200ms <= 320ms); VOCAL_SFX "oh yeah" stays separate
        assertEquals(3, merged.size)
        assertEquals("What are you doing", merged[0].text)
        assertEquals("oh yeah", merged[1].text)
        assertEquals("I'm leaving now.", merged[2].text)

        val speakers = mapOf(
            "SPEAKER_1" to SpeakerEntity(projectId = 1L, speakerLabel = "SPEAKER_1", displayName = "S1", gender = "MALE"),
            "SPEAKER_2" to SpeakerEntity(projectId = 1L, speakerLabel = "SPEAKER_2", displayName = "S2", gender = "FEMALE")
        )
        val segments = listOf(
            DialogueSegmentEntity(
                id = 1L, projectId = 1L, chunkIndex = 0, orderIndex = 0,
                startMs = 0L, endMs = 1500L, speakerLabel = "SPEAKER_2",
                englishText = "Kiss me closer baby", emotion = "whisper", audioType = "SPEECH"
            ),
            DialogueSegmentEntity(
                id = 2L, projectId = 1L, chunkIndex = 0, orderIndex = 1,
                startMs = 1600L, endMs = 3000L, speakerLabel = "SPEAKER_1",
                englishText = "warming three mean more stone", emotion = "calm", audioType = "SPEECH"
            )
        )

        assertEquals("Intimate / Romantic", TranscriptRepairHelper.inferSceneMood(segments, 1))
        assertEquals("FEMALE", TranscriptRepairHelper.inferListenerGender(segments, 1, speakers))
        assertTrue(TranscriptRepairHelper.isLikelyAsrGarble("warming three mean more stone"))
        assertFalse(TranscriptRepairHelper.isLikelyAsrGarble("what are you doing right now"))
    }

    @Test
    fun `LocalGgufTranslator buildSingleLinePrompt includes Scene Mood, Listener Gender, and rolling window`() {
        val translator = LocalGgufTranslator()
        val prompt = translator.buildSingleLinePrompt(
            englishLine = "Don't stop now.",
            prevEnglish = "Kiss me closer.",
            prevHindi = "मेरे करीब आओ।",
            nextEnglish = "I want you tonight.",
            speakerGender = "MALE",
            listenerGender = "FEMALE",
            sceneMood = "Intimate / Romantic",
            asrMayHaveErrors = true,
            emotion = "whisper",
            glossaryHints = "",
            dialogueStyle = DialogueStyle.RAW_UNCENSORED
        )

        assertTrue(prompt.contains("Scene Mood: Intimate / Romantic"))
        assertTrue(prompt.contains("Prev line: \"Kiss me closer.\" (said: मेरे करीब आओ।)"))
        assertTrue(prompt.contains("Next line: \"I want you tonight.\""))
        assertTrue(prompt.contains("Speaker: MALE talking to FEMALE (whisper)"))
        assertTrue(prompt.contains("phonetic typos"))
    }

    @Test
    fun `AudioMixer prevents non-overlapping TTS clip from bleeding into next speaker slot`() = runTest {
        val sampleRate = 44100
        val channels = 2
        val totalFrames = sampleRate * 3 // 3 seconds
        val silentBed = ShortArray(totalFrames * channels) { 0 }

        // Clip 1 starts at 0.2s and is 2.0s long (would collide with clip 2 starting at 1.0s)
        val clip1Samples = ShortArray(sampleRate * 2 * channels) { 12000 }
        // Clip 2 starts at 1.0s and is 0.5s long with quiet constant amplitude 2000
        val clip2Samples = ShortArray((sampleRate / 2) * channels) { 2000 }

        val origWav = tempFolder.newFile("c_orig.wav")
        val bgmWav = tempFolder.newFile("c_bgm.wav")
        val tts1Wav = tempFolder.newFile("c_tts1.wav")
        val tts2Wav = tempFolder.newFile("c_tts2.wav")
        val outWav = tempFolder.newFile("c_mixed.wav")

        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(silentBed), sampleRate, channels, origWav)
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(silentBed), sampleRate, channels, bgmWav)
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(clip1Samples), sampleRate, channels, tts1Wav)
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(clip2Samples), sampleRate, channels, tts2Wav)

        val seg1 = DialogueSegmentEntity(
            id = 1L, projectId = 1L, chunkIndex = 0, orderIndex = 0,
            startMs = 200L, endMs = 800L, speakerLabel = "SPEAKER_1",
            englishText = "Long first line", hindiText = "पहली लंबी लाइन",
            isOverlapping = false, audioType = "SPEECH"
        )
        val seg2 = DialogueSegmentEntity(
            id = 2L, projectId = 1L, chunkIndex = 0, orderIndex = 1,
            startMs = 1000L, endMs = 1500L, speakerLabel = "SPEAKER_2",
            englishText = "Second line", hindiText = "दूसरी लाइन",
            isOverlapping = false, audioType = "SPEECH"
        )

        AudioMixer.mixChunk(
            origWavFile = origWav,
            bgmWavFile = bgmWav,
            ttsSegments = listOf(
                AudioMixer.PositionedTts(seg1, tts1Wav),
                AudioMixer.PositionedTts(seg2, tts2Wav)
            ),
            chunkStartMs = 0L,
            hindiVolume = 1.0f,
            bgmVolume = 0.0f,
            originalVolume = 0.0f,
            outputMixedWav = outWav
        )

        val hdr = WavUtils.readHeader(outWav)
        val mixedShorts = WavUtils.bytesToShortArray(
            outWav.readBytes().copyOfRange(hdr.dataOffset.toInt(), outWav.length().toInt())
        )
        // Inside Clip 2 window (1.1s mark = frame ~48510), only Clip 2 (~2000) should be heard, NOT 12000 + 2000!
        val checkIdx = (48510 * channels)
        assertTrue(
            "Expected clip 1 to fade out before clip 2 (got ${mixedShorts[checkIdx]})",
            abs(mixedShorts[checkIdx].toInt()) <= 2500
        )
    }

    @Test
    fun `OnDeviceSpeakerAnalyzer pitch hysteresis keeps same speaker stable under minor pitch drift`() {
        val sampleRate = 16000
        val pcm = ShortArray(sampleRate * 4)
        // 0..1s: Male ~126 Hz
        for (i in 0 until sampleRate) {
            pcm[i] = (sin(2.0 * PI * 126.0 * i / sampleRate) * 12000).toInt().toShort()
        }
        // 1..2s: Same Male slightly excited ~138 Hz (within +/-22 Hz hysteresis, would previously flip!)
        for (i in sampleRate until sampleRate * 2) {
            pcm[i] = (sin(2.0 * PI * 138.0 * i / sampleRate) * 12000).toInt().toShort()
        }
        // 3..4s: Female ~225 Hz (new speaker)
        for (i in sampleRate * 3 until sampleRate * 4) {
            pcm[i] = (sin(2.0 * PI * 225.0 * i / sampleRate) * 12000).toInt().toShort()
        }
        val sttWav = tempFolder.newFile("hysteresis_pitch.wav")
        WavUtils.writePcmBytesAsWav(WavUtils.shortArrayToBytes(pcm), sampleRate, 1, sttWav)

        val res = OnDeviceSpeakerAnalyzer.analyzeSpeakersAndBuildTimeline(
            sttWavFile = sttWav,
            phrases = listOf(
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(50L, 950L, "First sentence here."),
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(1050L, 1950L, "Second sentence here."),
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(3050L, 3950L, "Third female sentence.")
            ),
            chunkDurationMs = 4000L,
            mergeChoppyPhrases = false
        )

        val speechSegs = res.segments.filter { it.audioType == "SPEECH" }
        assertEquals(3, speechSegs.size)
        assertEquals(speechSegs[0].speakerLabel, speechSegs[1].speakerLabel)
        assertFalse(speechSegs[0].speakerLabel == speechSegs[2].speakerLabel)
        assertEquals(2, res.speakers.size)
    }

    @Test
    fun `SrtSubtitleParser detects Devanagari Hindi vs English subtitles`() {
        val enCues = listOf(SubtitleCue(0L, 1000L, "Where are you going now?"))
        val hiCues = listOf(SubtitleCue(0L, 1000L, "तुम अभी कहाँ जा रहे हो?"))
        assertEquals(SubtitleLanguage.ENGLISH, SrtSubtitleParser.detectLikelyLanguage(enCues))
        assertEquals(SubtitleLanguage.HINDI, SrtSubtitleParser.detectLikelyLanguage(hiCues))
    }

    @Test
    fun `OnDeviceModelManager validates GGML magic bytes and ResumableHttpDownloader enforces SHA-256`() = runTest {
        val validGgml = tempFolder.newFile("ggml-tiny.en.bin").apply {
            writeBytes("lmgg".toByteArray(Charsets.US_ASCII) + ByteArray(2048))
        }
        val htmlSpoof = tempFolder.newFile("bad-ggml.bin").apply {
            writeBytes("<!DOCTYPE html>".toByteArray(Charsets.US_ASCII) + ByteArray(2048))
        }
        assertTrue(OnDeviceModelManager.isValidGgmlFile(validGgml))
        assertFalse(OnDeviceModelManager.isValidGgmlFile(htmlSpoof))

        val payload = ByteArray(512) { idx -> (idx * 7).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val server = ServerSocket(0)
        val serverThread = thread {
            server.use { srv ->
                val sock = srv.accept()
                sock.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(headers.toByteArray(Charsets.US_ASCII))
                    out.write(payload)
                    out.flush()
                }
            }
        }

        val partBad = tempFolder.newFile("bad_sha.bin.part").apply { delete() }
        val destBad = tempFolder.newFile("bad_sha.bin").apply { delete() }
        var failedOnBadSha = false
        try {
            ResumableHttpDownloader.downloadToFile(
                url = "http://127.0.0.1:${server.localPort}/file.bin",
                partFile = partBad,
                destFile = destBad,
                fallbackApproxBytes = payload.size.toLong(),
                expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
            ) { _, _ -> }
        } catch (e: IllegalStateException) {
            failedOnBadSha = e.message?.contains("checksum", ignoreCase = true) == true
        }
        serverThread.join(3000L)
        assertTrue(failedOnBadSha)
        assertFalse(destBad.exists())

        // Verify exact matching SHA-256 succeeds
        assertEquals(expectedSha, ResumableHttpDownloader.computeSha256(tempFolder.newFile("good.bin").apply { writeBytes(payload) }))
    }
}
