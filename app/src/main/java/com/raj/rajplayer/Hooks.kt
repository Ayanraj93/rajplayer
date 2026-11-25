package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the LAME MP3 encoder. The native library is packaged for
 * armeabi-v7a and arm64-v8a by the Android NDK build.
 */
object Mp3Native {
    init {
        System.loadLibrary("rajplayer_mp3")
    }

    external fun nativeCreate(sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    external fun nativeEncode(handle: Long, pcm: ShortArray, samplesPerChannel: Int): ByteArray
    external fun nativeFlush(handle: Long): ByteArray
    external fun nativeClose(handle: Long)
}

object Power {
    private var wl: PowerManager.WakeLock? = null

    fun isIgnoring(c: Context): Boolean {
        return try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(c.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun request(a: Activity) {
        if (isIgnoring(a)) {
            Toast.makeText(a, "Background activity already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + a.packageName)
            a.startActivity(i)
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(a, "Settings > Apps > RajPlayer > Battery > Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun askOnce(a: Activity) {
        try {
            val sp = a.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)
            if (sp.getBoolean("bg_asked", false)) return
            if (a.isFinishing) return
            sp.edit().putBoolean("bg_asked", true).apply()
            if (isIgnoring(a)) return
            AlertDialog.Builder(a)
                .setTitle("Allow background activity")
                .setMessage("To keep music and videos playing with the screen off, allow RajPlayer to run in the background (Battery: Unrestricted).")
                .setPositiveButton("Allow") { _, _ -> request(a) }
                .setNegativeButton("Later", null)
                .show()
        } catch (e: Exception) {
        }
    }

    fun wake(c: Context, on: Boolean) {
        try {
            if (on) {
                if (wl == null) {
                    val pm = c.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RajPlayer:play")
                    w.setReferenceCounted(false)
                    wl = w
                }
                val w2 = wl
                if (w2 != null && !w2.isHeld) w2.acquire(6 * 60 * 60 * 1000L)
            } else {
                val w3 = wl
                if (w3 != null && w3.isHeld) w3.release()
            }
        } catch (e: Exception) {
        }
    }
}

object Hooks {
    private val conversionExecutor = Executors.newSingleThreadExecutor()

    private fun safeBaseName(originalName: String): String {
        val bad = "\\/:*?\"<>|"
        val cleaned = buildString {
            for (ch in originalName.substringBeforeLast('.', originalName)) {
                if (ch.code < 32 || bad.indexOf(ch) >= 0) append('_') else append(ch)
            }
        }.trim().take(80)
        return if (cleaned.isBlank()) "RajPlayer_Audio" else cleaned
    }

    private fun reportConversionProgress(
        onProgress: (Int) -> Unit,
        positionUs: Long,
        durationUs: Long
    ) {
        if (durationUs <= 0L || positionUs < 0L) return
        val percent = ((positionUs.toDouble() / durationUs.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 99)
        onProgress(percent)
    }

    /**
     * Shows the output format picker, then converts the selected video(s).
     * M4A uses fast AAC remux with dynamic AAC transcode fallback; MP3 uses LAME.
     */
    fun convert(a: Activity, uris: List<Uri>, names: List<String>, fin: (() -> Unit)?) {
        if (uris.isEmpty()) {
            Toast.makeText(a, "Select at least one video", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(a)
            .setTitle(if (uris.size == 1) "Convert video to audio" else "Batch convert videos")
            .setItems(arrayOf("MP3 (LAME)", "M4A (AAC)")) { _, which ->
                runBatchConversion(a, uris, names, if (which == 0) "MP3" else "M4A", fin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBatchConversion(
        a: Activity,
        uris: List<Uri>,
        names: List<String>,
        format: String,
        fin: (() -> Unit)?
    ) {
        val cancelled = AtomicBoolean(false)
        val progress = ProgressDialog(a).apply {
            setTitle("Converting video to $format")
            setMessage("Preparing conversion...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = uris.size * 1000
            progress = 0
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }

        conversionExecutor.execute {
            var success = 0
            val errors = ArrayList<String>()
            for (index in uris.indices) {
                if (cancelled.get()) break
                val name = names.getOrNull(index) ?: "video_${index + 1}"
                var lastFileProgress = -1
                a.runOnUiThread {
                    if (progress.isShowing) {
                        progress.progress = index * 1000
                        progress.setMessage("Converting ${index + 1}/${uris.size} to $format\n$name")
                    }
                }

                val onFileProgress: (Int) -> Unit = { percent ->
                    val safePercent = percent.coerceIn(0, 99)
                    if (safePercent != lastFileProgress) {
                        lastFileProgress = safePercent
                        val overallProgress = index * 1000 + safePercent * 10
                        a.runOnUiThread {
                            if (progress.isShowing) {
                                progress.progress = overallProgress
                                progress.setMessage(
                                    "Converting ${index + 1}/${uris.size} to $format " +
                                        "• ${safePercent}%\n$name"
                                )
                            }
                        }
                    }
                }

                try {
                    val temp = if (format == "M4A") {
                        encodeVideoAudioToM4a(a, uris[index], cancelled, onFileProgress)
                    } else {
                        encodeVideoAudioToMp3(a, uris[index], cancelled, onFileProgress)
                    }
                    try {
                        if (!cancelled.get()) {
                            if (format == "M4A") publishM4a(a, temp, name)
                            else publishMp3(a, temp, name)
                        }
                    } finally {
                        temp.delete()
                    }
                    if (cancelled.get()) break
                    success++
                    a.runOnUiThread {
                        if (progress.isShowing) {
                            progress.progress = (index + 1) * 1000
                        }
                    }
                } catch (e: Exception) {
                    if (!cancelled.get()) {
                        errors.add("$name: ${e.message ?: "conversion failed"}")
                    }
                }
            }

            a.runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                val message = buildString {
                    append("Converted: $success/${uris.size} $format")
                    if (cancelled.get()) append("\nCancelled")
                    if (errors.isNotEmpty()) {
                        append("\nFailed: ${errors.size}")
                        append("\n")
                        append(errors.take(3).joinToString("\n"))
                    }
                }
                if (!a.isFinishing && !a.isDestroyed) {
                    AlertDialog.Builder(a)
                        .setTitle("Conversion finished")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
                fin?.invoke()
            }
        }
    }

    private fun extractPcm16Bytes(
        buf: ByteBuffer,
        offset: Int,
        size: Int,
        srcChannels: Int,
        dstChannels: Int,
        pcmEncoding: Int
    ): ByteArray {
        buf.position(offset)
        buf.limit(offset + size)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT && srcChannels == dstChannels) {
            val out = ByteArray(size)
            buf.get(out)
            return out
        }
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floatCount = size / 4
            val frames = if (srcChannels > 0) floatCount / srcChannels else 0
            val out = ByteArray(frames * dstChannels * 2)
            val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                for (c in 0 until srcChannels) {
                    val sample = buf.float
                    if (c < dstChannels) {
                        val clamped = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        outBuf.putShort(clamped)
                    }
                }
            }
            return out
        }
        val shortCount = size / 2
        val frames = if (srcChannels > 0) shortCount / srcChannels else 0
        val out = ByteArray(frames * dstChannels * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            for (c in 0 until srcChannels) {
                val s = buf.short
                if (c < dstChannels) outBuf.putShort(s)
            }
        }
        return out
    }

    /**
     * Decode the selected video's audio track to signed 16-bit PCM and encode it
     * incrementally with LAME. Handles mono, stereo, and >2ch downmix cleanly.
     */
    private fun encodeVideoAudioToMp3(
        context: Context,
        source: Uri,
        cancelled: AtomicBoolean,
        onProgress: (Int) -> Unit
    ): File {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var lameHandle = 0L
        val temp = File.createTempFile("rajplayer_audio_", ".mp3", context.cacheDir)
        var completed = false

        try {
            extractor.setDataSource(context, source, null)
            var audioTrack = -1
            var inputMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputMime = mime
                    break
                }
            }
            if (audioTrack < 0 || inputMime == null) {
                throw IllegalArgumentException("No audio track found")
            }

            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else -1L
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            FileOutputStream(temp).use { output ->
                val info = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                var outputFormatReady = false
                var srcChannels = 2
                var dstChannels = 2
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var pcmBuffer = ShortArray(0)

                fun initLameFromFormat(decoded: MediaFormat) {
                    val sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    dstChannels = srcChannels.coerceIn(1, 2)
                    pcmEncoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    if (sampleRate !in 8000..48000) {
                        throw IllegalArgumentException("Unsupported MP3 sample rate: $sampleRate")
                    }
                    val mp3Bitrate = when {
                        sampleRate < 12000 -> 32
                        sampleRate < 16000 -> 64
                        sampleRate < 24000 -> 96
                        sampleRate < 32000 -> 128
                        else -> 192
                    }
                    if (lameHandle != 0L) {
                        try { Mp3Native.nativeClose(lameHandle) } catch (_: Exception) {}
                        lameHandle = 0L
                    }
                    lameHandle = Mp3Native.nativeCreate(sampleRate, dstChannels, mp3Bitrate)
                    if (lameHandle == 0L) {
                        throw IllegalStateException("Could not initialize LAME encoder")
                    }
                    outputFormatReady = true
                }

                while (!outputEos) {
                    if (cancelled.get()) throw InterruptedException("Conversion cancelled")

                    if (!inputEos) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("Decoder input buffer unavailable")
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(
                                    inputIndex, 0, size, sampleTimeUs, extractor.sampleFlags
                                )
                                extractor.advance()
                                reportConversionProgress(onProgress, sampleTimeUs, durationUs)
                            }
                        }
                    }

                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            initLameFromFormat(decoder.outputFormat)
                        }
                        outputIndex >= 0 -> {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (info.size > 0 && buffer != null &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                if (!outputFormatReady || lameHandle == 0L) {
                                    initLameFromFormat(decoder.outputFormat)
                                }
                                val bytes = extractPcm16Bytes(
                                    buffer, info.offset, info.size,
                                    srcChannels, dstChannels, pcmEncoding
                                )
                                val shortCount = bytes.size / 2
                                if (pcmBuffer.size < shortCount) {
                                    pcmBuffer = ShortArray(shortCount)
                                }
                                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmBuffer, 0, shortCount)
                                val samplesPerChannel = shortCount / dstChannels
                                if (samplesPerChannel > 0) {
                                    val encoded = Mp3Native.nativeEncode(lameHandle, pcmBuffer, samplesPerChannel)
                                    if (encoded.isNotEmpty()) output.write(encoded)
                                }
                            }
                            outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                if (!outputFormatReady || lameHandle == 0L) {
                    throw IllegalArgumentException("No decodable audio samples found")
                }
                val tail = Mp3Native.nativeFlush(lameHandle)
                if (tail.isNotEmpty()) output.write(tail)
                output.flush()
                onProgress(100)
            }
            completed = true
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            if (lameHandle != 0L) {
                try { Mp3Native.nativeClose(lameHandle) } catch (_: Exception) {}
            }
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            if (!completed) temp.delete()
        }
    }

    private fun publishMp3(context: Context, source: File, originalName: String) {
        val base = safeBaseName(originalName)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${base}_audio.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RajPlayer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create MP3 output file")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not write MP3 output file")
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Fast path for M4A: if the video already carries AAC (audio/mp4a-latm) with valid CSD,
     * directly remux into M4A in ~1 second with zero quality loss.
     */
    private fun tryRemuxAacToM4a(
        context: Context,
        source: Uri,
        cancelled: AtomicBoolean,
        onProgress: (Int) -> Unit
    ): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val temp = File.createTempFile("rajplayer_remux_", ".m4a", context.cacheDir)
        try {
            extractor.setDataSource(context, source, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.equals("audio/mp4a-latm", ignoreCase = true)) {
                    audioTrack = i
                    inputFormat = f
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null || !inputFormat.containsKey("csd-0")) {
                temp.delete()
                return null
            }
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else -1L

            extractor.selectTrack(audioTrack)
            val maxInput = if (inputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(65536, 1024 * 1024)
            } else 256 * 1024

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(inputFormat)
            muxer.start()
            muxerStarted = true

            val buf = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            var lastPts = -1L
            var written = 0

            while (true) {
                if (cancelled.get()) throw InterruptedException("Cancelled")
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val rawPts = extractor.sampleTime
                val safePts = when {
                    rawPts < 0L -> if (lastPts < 0L) 0L else lastPts + 1L
                    lastPts >= 0L && rawPts <= lastPts -> lastPts + 1L
                    else -> rawPts
                }
                lastPts = safePts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = safePts
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(outTrack, buf, info)
                written++
                reportConversionProgress(onProgress, safePts, durationUs)
                extractor.advance()
            }
            if (written == 0) {
                temp.delete()
                return null
            }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            if (temp.length() <= 0L) {
                temp.delete()
                return null
            }
            onProgress(100)
            return temp
        } catch (e: InterruptedException) {
            temp.delete()
            throw e
        } catch (_: Exception) {
            temp.delete()
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun safeAacBitrate(sampleRate: Int, channels: Int): Int {
        val base = when {
            sampleRate <= 12000 -> 32_000
            sampleRate <= 16000 -> 48_000
            sampleRate <= 24000 -> 64_000
            sampleRate <= 32000 -> 96_000
            else -> 128_000
        }
        return if (channels >= 2 && sampleRate >= 44100) 192_000 else base
    }

    private fun encodeVideoAudioToM4a(
        context: Context,
        source: Uri,
        cancelled: AtomicBoolean,
        onProgress: (Int) -> Unit
    ): File {
        // 1. Try instant lossless AAC remux first (fixes HE-AAC/container quirks & runs in ~1s)
        val remuxed = tryRemuxAacToM4a(context, source, cancelled, onProgress)
        if (remuxed != null) return remuxed

        // 2. Fallback: Decode -> dynamic AAC-LC encode (initialized AFTER real decoder format)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputTrack = -1
        val temp = File.createTempFile("rajplayer_audio_", ".m4a", context.cacheDir)

        try {
            extractor.setDataSource(context, source, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = f
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw IllegalArgumentException("No audio track found")
            }

            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else -1L
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio codec")

            extractor.selectTrack(audioTrack)
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var decoderOutputEos = false
            var encoderInputEos = false
            var encoderOutputEos = false
            var srcChannels = 2
            var outChannels = 2
            var outSampleRate = 44100
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var pendingPcm = ByteArray(0)
            var pendingOffset = 0
            var totalFramesFed = 0L
            var lastMuxPts = -1L
            var muxerFramesWritten = 0

            fun ensureEncoder(decodedFormat: MediaFormat) {
                srcChannels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmEncoding = if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                if (encoder != null) return
                outSampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outChannels = srcChannels.coerceIn(1, 2)
                val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", outSampleRate, outChannels)
                encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                encFormat.setInteger(MediaFormat.KEY_BIT_RATE, safeAacBitrate(outSampleRate, outChannels))
                encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
                enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                encoder = enc
                muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            while (!encoderOutputEos) {
                if (cancelled.get()) throw InterruptedException("Cancelled")
                var didWork = false

                // Feed pending PCM into AAC encoder
                val encNow = encoder
                if (encNow != null && pendingOffset < pendingPcm.size && !encoderInputEos) {
                    val inputIndex = encNow.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val inputBuffer = encNow.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Encoder input buffer unavailable")
                        inputBuffer.clear()
                        val frameBytes = outChannels * 2
                        val availableBytes = minOf(inputBuffer.remaining(), pendingPcm.size - pendingOffset)
                        val count = (availableBytes / frameBytes) * frameBytes
                        if (count > 0) {
                            inputBuffer.put(pendingPcm, pendingOffset, count)
                            val pts = (totalFramesFed * 1_000_000L) / outSampleRate.toLong()
                            encNow.queueInputBuffer(inputIndex, 0, count, pts, 0)
                            totalFramesFed += (count / frameBytes).toLong()
                            pendingOffset += count
                            if (pendingOffset >= pendingPcm.size) {
                                pendingPcm = ByteArray(0)
                                pendingOffset = 0
                            }
                            didWork = true
                        } else {
                            encNow.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                            pendingPcm = ByteArray(0)
                            pendingOffset = 0
                        }
                    }
                }

                // Feed compressed samples into decoder
                if (!decoderInputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Decoder input buffer unavailable")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputEos = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                            extractor.advance()
                            reportConversionProgress(onProgress, sampleTimeUs, durationUs)
                        }
                        didWork = true
                    }
                }

                // Drain decoded PCM from decoder
                if (!decoderOutputEos && pendingOffset >= pendingPcm.size) {
                    val outputIndex = decoder.dequeueOutputBuffer(decInfo, if (encoder == null) 5_000 else 0)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            ensureEncoder(decoder.outputFormat)
                            didWork = true
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (decInfo.size > 0 && outputBuffer != null &&
                                (decInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                ensureEncoder(decoder.outputFormat)
                                pendingPcm = extractPcm16Bytes(
                                    outputBuffer, decInfo.offset, decInfo.size,
                                    srcChannels, outChannels, pcmEncoding
                                )
                                pendingOffset = 0
                            }
                            val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (eos) decoderOutputEos = true
                            didWork = true
                        }
                    }
                }

                // Signal EOS to encoder once decoder is finished and all PCM is fed
                if (decoderOutputEos && pendingOffset >= pendingPcm.size) {
                    val encEos = encoder
                    if (encEos == null) break
                    if (!encoderInputEos) {
                        val inputIndex = encEos.dequeueInputBuffer(0)
                        if (inputIndex >= 0) {
                            val eosPts = (totalFramesFed * 1_000_000L) / outSampleRate.toLong()
                            encEos.queueInputBuffer(inputIndex, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputEos = true
                            didWork = true
                        }
                    }
                }

                // Drain encoded AAC packets into MP4 muxer
                val encOut = encoder
                val mux = muxer
                if (encOut != null && mux != null) {
                    var encoderIndex = encOut.dequeueOutputBuffer(encInfo, 0)
                    while (encoderIndex >= 0 || encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        didWork = true
                        if (encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                outputTrack = mux.addTrack(encOut.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        } else {
                            val encoded = encOut.getOutputBuffer(encoderIndex)
                            if (encInfo.size > 0 && encoded != null &&
                                (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                if (!muxerStarted) {
                                    outputTrack = mux.addTrack(encOut.outputFormat)
                                    mux.start()
                                    muxerStarted = true
                                }
                                encoded.position(encInfo.offset)
                                encoded.limit(encInfo.offset + encInfo.size)
                                var pts = encInfo.presentationTimeUs
                                if (pts < 0L) pts = if (lastMuxPts < 0L) 0L else lastMuxPts + 1L
                                if (lastMuxPts >= 0L && pts <= lastMuxPts) pts = lastMuxPts + 1L
                                lastMuxPts = pts
                                encInfo.presentationTimeUs = pts
                                mux.writeSampleData(outputTrack, encoded, encInfo)
                                muxerFramesWritten++
                            }
                            val eos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encOut.releaseOutputBuffer(encoderIndex, false)
                            if (eos) {
                                encoderOutputEos = true
                                break
                            }
                        }
                        encoderIndex = encOut.dequeueOutputBuffer(encInfo, 0)
                    }
                }

                if (!didWork && !encoderOutputEos) Thread.sleep(1L)
            }

            if (!muxerStarted || muxerFramesWritten == 0) {
                throw IllegalStateException("No encoded audio was produced")
            }
            muxer?.stop()
            muxerStarted = false
            muxer?.release()
            muxer = null
            onProgress(100)
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun publishM4a(context: Context, source: File, originalName: String) {
        val base = safeBaseName(originalName)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${base}_audio.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RajPlayer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create M4A output file")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not write M4A output file")
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}
