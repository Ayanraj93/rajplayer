package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContentValues
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.provider.MediaStore
import android.app.ProgressDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings
import android.widget.Toast

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
     * M4A uses Android AAC/MediaMuxer; MP3 uses the LGPL LAME encoder.
     */
    fun convert(a: Activity, uris: List<Uri>, names: List<String>, fin: (() -> Unit)?) {
        if (uris.isEmpty()) {
            Toast.makeText(a, "Select at least one video", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(a)
            .setTitle(if (uris.size == 1) "Convert video to audio" else "Batch convert videos")
            .setItems(arrayOf("M4A (AAC)", "MP3 (LAME)")) { _, which ->
                runBatchConversion(a, uris, names, if (which == 0) "M4A" else "MP3", fin)
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
                        encodeVideoAudioToM4a(a, uris[index], cancelled, onFileProgress).also {
                            if (!cancelled.get()) publishM4a(a, it, name)
                        }
                    } else {
                        encodeVideoAudioToMp3(a, uris[index], cancelled, onFileProgress).also {
                            if (!cancelled.get()) publishMp3(a, it, name)
                        }
                    }
                    temp.delete()
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

    /**
     * Decode the selected video's audio track to signed 16-bit PCM and encode it
     * incrementally with LAME. This keeps memory usage bounded for long videos.
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
                            val decoded = decoder.outputFormat
                            val sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pcmEncoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                                throw IllegalArgumentException("Decoder did not provide 16-bit PCM")
                            }
                            if (channels !in 1..2) {
                                throw IllegalArgumentException("MP3 supports mono or stereo audio")
                            }
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
                            lameHandle = Mp3Native.nativeCreate(sampleRate, channels, mp3Bitrate)
                            if (lameHandle == 0L) {
                                throw IllegalStateException("Could not initialize LAME encoder")
                            }
                            outputFormatReady = true
                        }
                        outputIndex >= 0 -> {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (info.size > 0 && buffer != null &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                if (!outputFormatReady || lameHandle == 0L) {
                                    throw IllegalStateException("Audio decoder format was not initialized")
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val shortCount = buffer.remaining() / 2
                                val pcm = ShortArray(shortCount)
                                for (i in 0 until shortCount) pcm[i] = buffer.short
                                val channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val samplesPerChannel = shortCount / channels
                                if (samplesPerChannel > 0) {
                                    val encoded = Mp3Native.nativeEncode(lameHandle, pcm, samplesPerChannel)
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
        val base = originalName.substringBeforeLast('.', originalName).ifBlank { "RajPlayer_Audio" }
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

    private fun encodeVideoAudioToM4a(
        context: Context,
        source: Uri,
        cancelled: AtomicBoolean,
        onProgress: (Int) -> Unit
    ): File {
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
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels !in 1..2) throw IllegalArgumentException("Only mono/stereo audio is supported")
            if (sampleRate !in 8000..96000) throw IllegalArgumentException("Unsupported audio sample rate")

            extractor.selectTrack(audioTrack)
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels)
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var decoderOutputEos = false
            var encoderInputEos = false
            var encoderOutputEos = false
            var pendingPcm = ByteArray(0)
            var pendingOffset = 0
            var pendingPts = 0L
            val bytesPerSecond = sampleRate.toLong() * channels * 2L

            while (!encoderOutputEos) {
                if (cancelled.get()) throw InterruptedException("Cancelled")

                // Feed decoded PCM into the AAC encoder. Keep any unconsumed PCM for
                // the next iteration rather than dropping samples when its input queue is full.
                if (pendingOffset < pendingPcm.size && !encoderInputEos) {
                    val inputIndex = encoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Encoder input buffer unavailable")
                        inputBuffer.clear()
                        val frameBytes = channels * 2
                        val availableBytes = minOf(inputBuffer.remaining(), pendingPcm.size - pendingOffset)
                        val count = (availableBytes / frameBytes) * frameBytes
                        if (count <= 0) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                            throw IllegalStateException("AAC input buffer is too small for one PCM frame")
                        }
                        inputBuffer.put(pendingPcm, pendingOffset, count)
                        val pts = pendingPts + (pendingOffset.toLong() * 1_000_000L / bytesPerSecond)
                        encoder.queueInputBuffer(inputIndex, 0, count, pts, 0)
                        pendingOffset += count
                        if (pendingOffset >= pendingPcm.size) {
                            pendingPcm = ByteArray(0)
                            pendingOffset = 0
                        }
                    }
                }

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
                    }
                }

                if (!decoderOutputEos && pendingPcm.isEmpty()) {
                    val outputIndex = decoder.dequeueOutputBuffer(decInfo, 0)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decodedFormat = decoder.outputFormat
                            val decodedRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val decodedChannels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (decodedRate != sampleRate || decodedChannels != channels) {
                                throw IllegalArgumentException("Audio format changes during decoding are not supported")
                            }
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (decInfo.size > 0 && outputBuffer != null) {
                                outputBuffer.position(decInfo.offset)
                                outputBuffer.limit(decInfo.offset + decInfo.size)
                                pendingPcm = ByteArray(decInfo.size)
                                outputBuffer.get(pendingPcm)
                                pendingPts = decInfo.presentationTimeUs
                            }
                            val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (eos) decoderOutputEos = true
                        }
                    }
                }

                if (decoderOutputEos && pendingPcm.isEmpty() && !encoderInputEos) {
                    val inputIndex = encoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputEos = true
                    }
                }

                var encoderIndex = encoder.dequeueOutputBuffer(encInfo, 0)
                while (encoderIndex >= 0 || encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw IllegalStateException("Encoder output format changed twice")
                        outputTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else {
                        val encoded = encoder.getOutputBuffer(encoderIndex)
                        if (encInfo.size > 0 && encoded != null &&
                            (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            if (!muxerStarted) throw IllegalStateException("Muxer has not started")
                            encoded.position(encInfo.offset)
                            encoded.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(outputTrack, encoded, encInfo)
                        }
                        val eos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(encoderIndex, false)
                        if (eos) encoderOutputEos = true
                    }
                    encoderIndex = encoder.dequeueOutputBuffer(encInfo, 0)
                }

                if (!decoderOutputEos || !encoderOutputEos) Thread.sleep(2L)
                if (decoderOutputEos && encoderOutputEos) break
            }

            if (!muxerStarted) throw IllegalStateException("No encoded audio was produced")
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
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun publishM4a(context: Context, source: File, originalName: String) {
        val base = originalName.substringBeforeLast('.', originalName).ifBlank { "RajPlayer_Audio" }
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
            ?: throw IllegalStateException("Could not create output file")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not write output file")
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
