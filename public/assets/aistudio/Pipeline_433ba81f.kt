package com.hindidub

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Seg(val start: Double, val end: Double, val text: String, val female: Boolean, var hi: String = "")

class Pipeline(private val ctx: Context, private val say: (String, Int) -> Unit) {
    private val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }

    suspend fun run(uri: Uri): String {
        say("1/6 ऑडियो निकाल रहा हूँ…", 2)
        val orig = File(work, "orig.pcm")
        val (rate, ch) = decodeAudio(uri, orig)
        say("2/6 English मॉडल तैयार (पहली बार ~40MB)…", 8)
        val model = ensureVosk()
        val segs = recognize(orig, rate, ch, model)
        model.close()
        if (segs.isEmpty()) return "कोई बोली हुई आवाज़ नहीं मिली"
        say("4/6 हिंदी अनुवाद…", 45)
        translate(segs)
        say("5/6 हिंदी आवाज़ बना रहा हूँ…", 50)
        val clips = tts(segs)
        say("6/6 BGM मिक्स + MP4 बना रहा हूँ…", 80)
        val mixed = File(work, "mixed.pcm"); mix(orig, rate, ch, segs, clips, mixed)
        val aac = File(work, "audio.m4a"); encodeAac(mixed, rate, ch, aac)
        val name = "HindiDub_${System.currentTimeMillis()}.mp4"
        finalMux(uri, aac, name)
        work.deleteRecursively()
        return "✅ हो गया! Downloads/$name में सेव है"
    }

    // ---------- 1. Audio decode (stream to disk, RAM कम) ----------
    private fun decodeAudio(uri: Uri, out: File): Pair<Int, Int> {
        val ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo(); var inDone = false; var done = false
        FileOutputStream(out).buffered(1 shl 16).use { os ->
            while (!done) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10000)
                if (o >= 0) {
                    val b = dec.getOutputBuffer(o)!!; val arr = ByteArray(info.size)
                    b.position(info.offset); b.get(arr); os.write(arr)
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        dec.stop(); dec.release(); ex.release()
        return rate to ch
    }

    // ---------- 2. Vosk (chunk-by-chunk) + pitch से Male/Female ----------
    private fun ensureVosk(): Model {
        val m = File(ctx.filesDir, "vosk/model")
        if (!File(m, "am").exists()) {
            m.deleteRecursively(); m.mkdirs()
            ZipInputStream(URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(m, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { z.copyTo(it) } }
                }
            }
        }
        return Model(m.path)
    }

    private fun recognize(pcm: File, rate: Int, ch: Int, model: Model): List<Seg> {
        say("3/6 English सुनकर लिख रहा हूँ…", 10)
        val rec = Recognizer(model, 16000f).apply { setWords(true) }
        val segs = ArrayList<Seg>()
        val ratio = rate / 16000.0
        val buf = ByteArray(rate * 2 * ch * 2) // 2 सेकंड
        var acc = ShortArray(16000 * 90); var an = 0; var lastF0 = 0f
        fun parse(json: String) {
            val j = JSONObject(json); val w = j.optJSONArray("result")
            if (w != null && w.length() > 0) {
                var f0 = medianF0(acc, an); if (f0 == 0f) f0 = lastF0 else lastF0 = f0
                segs.add(Seg(w.getJSONObject(0).getDouble("start"), w.getJSONObject(w.length() - 1).getDouble("end"),
                    j.getString("text"), f0 >= 165f))
            }
            an = 0
        }
        FileInputStream(pcm).use { ins ->
            var read = 0L; val total = max(1L, pcm.length())
            while (true) {
                val n = ins.read(buf); if (n <= 0) break
                read += n
                val frames = n / (2 * ch)
                val sh = ShortArray(frames * ch); ByteBuffer.wrap(buf, 0, frames * 2 * ch).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                val outLen = (frames / ratio).toInt()
                val ob = ByteBuffer.allocate(outLen * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until outLen) {
                    val a = (j * ratio).toInt(); val e = min(frames, max(a + 1, ((j + 1) * ratio).toInt()))
                    var s = 0L; for (k in a until e) for (c in 0 until ch) s += sh[k * ch + c]
                    val v = (s / ((e - a) * ch)).toShort(); ob.putShort(v)
                    if (an < acc.size) acc[an++] = v
                }
                if (rec.acceptWaveForm(ob.array(), outLen * 2)) parse(rec.result)
                say("3/6 English सुनकर लिख रहा हूँ…", 10 + (35 * read / total).toInt())
            }
        }
        parse(rec.finalResult); rec.close()
        return segs
    }

    private fun medianF0(s: ShortArray, n: Int): Float {
        val f = ArrayList<Float>(); var p = 0
        while (p + 1024 <= n) {
            var e = 0.0; for (i in 0 until 1024) { val v = s[p + i].toDouble(); e += v * v }
            if (sqrt(e / 1024) > 500) {
                var best = 0.0; var bl = 0
                for (l in 53..213) { var c = 0.0; for (i in 0 until 1024 - l) c += s[p + i].toDouble() * s[p + i + l]; if (c > best) { best = c; bl = l } }
                if (bl > 0 && best / e > 0.4) f.add(16000f / bl)
            }
            p += 4096
        }
        return if (f.isEmpty()) 0f else f.sorted()[f.size / 2]
    }

    // ---------- 3. ML Kit अनुवाद ----------
    private suspend fun translate(segs: List<Seg>) {
        val t = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.HINDI).build())
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        segs.forEach { it.hi = t.translate(it.text).await() }
        t.close()
    }

    // ---------- 4. Hindi TTS (समय में फिट) ----------
    private suspend fun tts(segs: List<Seg>): List<File?> {
        val tts = suspendCancellableCoroutine<TextToSpeech?> { c ->
            var t: TextToSpeech? = null
            t = TextToSpeech(ctx) { st -> c.resume(if (st == TextToSpeech.SUCCESS) t else null) }
        } ?: throw Exception("TTS नहीं मिला — Settings में Google Speech Services + Hindi voice इंस्टॉल करें")
        tts.language = Locale("hi", "IN")
        val clips = ArrayList<File?>()
        segs.forEachIndexed { i, s ->
            val f = File(work, "tts$i.wav"); val pitch = if (s.female) 1.15f else 0.85f
            synth(tts, s.hi, f, pitch, 1.05f)
            var dur = wavInfo(f).let { it.second / it.first.toDouble() }
            val slot = s.end - s.start
            if (dur > slot && slot > 0.3) { // लंबा है → तेज़ बोलकर दोबारा
                f.delete(); synth(tts, s.hi, f, pitch, min(1.8f, (1.05 * dur / slot).toFloat()))
            }
            clips.add(if (f.exists() && f.length() > 44) f else null)
            say("5/6 हिंदी आवाज़ बना रहा हूँ… ${i + 1}/${segs.size}", 50 + 30 * (i + 1) / segs.size)
        }
        tts.shutdown(); return clips
    }

    private suspend fun synth(t: TextToSpeech, text: String, f: File, pitch: Float, rate: Float) {
        t.setPitch(pitch); t.setSpeechRate(rate)
        suspendCancellableCoroutine<Unit> { c ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) { if (c.isActive) c.resume(Unit) }
                @Deprecated("x") override fun onError(u: String?) { if (c.isActive) c.resume(Unit) }
            })
            t.synthesizeToFile(text, null, f, UUID.randomUUID().toString())
        }
    }

    /** returns (sampleRate, sampleCount mono) */
    private fun wavInfo(f: File): Pair<Int, Int> {
        if (!f.exists() || f.length() <= 44) return 22050 to 0
        val b = ByteBuffer.wrap(f.readBytes(0, 44)).order(ByteOrder.LITTLE_ENDIAN)
        val ch = b.getShort(22).toInt(); return b.getInt(24) to ((f.length() - 44) / 2 / ch).toInt()
    }
    private fun File.readBytes(off: Int, n: Int) = RandomAccessFile(this, "r").use { val a = ByteArray(n); it.readFully(a); a }

    private fun readWav(f: File, outRate: Int): ShortArray {
        val all = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val ch = all.getShort(22).toInt(); val sr = all.getInt(24)
        val n = (all.capacity() - 44) / 2 / ch
        val mono = ShortArray(n) { all.getShort(44 + it * 2 * ch) }
        val m = (n.toLong() * outRate / sr).toInt()
        return ShortArray(m) { mono[min(n - 1, (it.toLong() * sr / outRate).toInt())] } // simple resample
    }

    // ---------- 5. Mix: Hindi + दबी हुई original (BGM/effects बचते हैं) ----------
    private fun mix(orig: File, rate: Int, ch: Int, segs: List<Seg>, clips: List<File?>, out: File) {
        val raf = RandomAccessFile(orig, "r"); val total = raf.length() / (2 * ch)
        FileOutputStream(out).buffered(1 shl 16).use { os ->
            var pos = 0L
            fun copy(frames: Long) {
                raf.seek(pos * 2 * ch); var left = frames * 2 * ch; val b = ByteArray(1 shl 16)
                while (left > 0) { val n = raf.read(b, 0, min(left, b.size.toLong()).toInt()); if (n <= 0) break; os.write(b, 0, n); left -= n }
                pos += frames
            }
            segs.forEachIndexed { i, s ->
                val c = clips[i] ?: return@forEachIndexed
                val h = readWav(c, rate)
                val st = max(pos, (s.start * rate).toLong()); if (st >= total) return@forEachIndexed
                copy(st - pos)
                val len = min(total - st, max(h.size.toLong(), (s.end * rate).toLong() - st)).toInt()
                raf.seek(st * 2 * ch)
                val bb = ByteArray(len * 2 * ch); raf.readFully(bb)
                val sb = ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (f in 0 until len) for (k in 0 until ch) {
                    val idx = f * ch + k
                    val v = sb.get(idx) * 0.25 + (if (f < h.size) h[f] * 1.3 else 0.0)
                    sb.put(idx, v.coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
                os.write(bb); pos = st + len
            }
            copy(total - pos)
        }
        raf.close()
    }

    // ---------- 6. AAC encode + MP4 mux (वीडियो बिना re-encode) ----------
    private fun encodeAac(pcm: File, rate: Int, ch: Int, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536) }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); var track = -1
        val info = MediaCodec.BufferInfo(); var inDone = false; var done = false; var bytes = 0L
        FileInputStream(pcm).use { ins ->
            while (!done) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!; val tmp = ByteArray(min(b.capacity(), 16384)); val n = ins.read(tmp)
                        if (n <= 0) { enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { b.put(tmp, 0, n); enc.queueInputBuffer(i, 0, n, bytes / (2 * ch) * 1_000_000L / rate, 0); bytes += n }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start() }
                else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) mux.writeSampleData(track, enc.getOutputBuffer(o)!!, info)
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        enc.stop(); enc.release(); mux.stop(); mux.release()
    }

    private fun finalMux(src: Uri, audio: File, name: String) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS) }
        val dest = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openFileDescriptor(dest, "rw")!!.use { pfd ->
            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val v = MediaExtractor().apply { setDataSource(ctx, src, null) }
            val a = MediaExtractor().apply { setDataSource(audio.path) }
            val vi = (0 until v.trackCount).first { v.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            v.selectTrack(vi); a.selectTrack(0)
            val vf = v.getTrackFormat(vi)
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
            val vt = mux.addTrack(vf); val at = mux.addTrack(a.getTrackFormat(0))
            mux.start(); pump(v, vt, mux); pump(a, at, mux)
            mux.stop(); mux.release(); v.release(); a.release()
        }
    }

    private fun pump(e: MediaExtractor, t: Int, m: MediaMuxer) {
        val b = ByteBuffer.allocate(4 shl 20); val i = MediaCodec.BufferInfo()
        while (true) {
            val n = e.readSampleData(b, 0); if (n < 0) break
            i.set(0, n, e.sampleTime, e.sampleFlags); m.writeSampleData(t, b, i); e.advance()
        }
    }
}
