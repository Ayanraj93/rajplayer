package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Video ki jaankari: resolution label (144p..4K), FPS, codec, HDR, audio details
object VideoInfo {

    class Info(
        val w: Int, val h: Int, val fps: Float, val vMime: String, val vBitrate: Long,
        val aMime: String, val aChannels: Int, val aRate: Int, val aBitrate: Long,
        val durMs: Long, val hdr: String, val std: String, val range: String, val rot: Int
    )

    private val EMPTY = Info(0, 0, 0f, "", 0L, "", 0, 0, 0L, 0L, "SDR", "", "", 0)
    private val cache = ConcurrentHashMap<String, Info>()
    private val pending = ConcurrentHashMap<String, Boolean>()
    private val pool = Executors.newSingleThreadExecutor()

    fun cached(u: Uri): Info? = cache[u.toString()]

    // ---------- labels ----------
    fun resLabel(w: Int, h: Int): String {
        val s = Math.min(w, h)
        val l = Math.max(w, h)
        return when {
            s >= 2000 || l >= 3600 -> "4K"
            s >= 1300 || l >= 2400 -> "1440p (2K)"
            s >= 960 || l >= 1800 -> "1080p"
            s >= 640 || l >= 1200 -> "720p"
            s >= 420 || l >= 800 -> "480p"
            s >= 300 || l >= 600 -> "360p"
            s >= 200 || l >= 400 -> "240p"
            else -> "144p"
        }
    }

    // Compact quality labels for Video List only.
    // Video Details continues using resLabel() and full width x height.
    fun listResLabel(w: Int, h: Int): String {
        val shortSide = minOf(w, h)
        return when {
            shortSide >= 4320 -> "8K"
            shortSide >= 2160 -> "4K"
            shortSide >= 1440 -> "2K"
            shortSide >= 1080 -> "1080p"
            shortSide >= 720 -> "720p"
            shortSide >= 480 -> "480p"
            shortSide >= 360 -> "360p"
            shortSide >= 240 -> "240p"
            else -> "144p"
        }
    }

    fun fpsLabel(f: Float): String {
        val r = Math.round(f)
        return if (Math.abs(f - r) < 0.05f) r.toString() else String.format("%.2f", f)
    }

    fun codecName(m: String): String = when (m) {
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/dolby-vision" -> "Dolby Vision"
        "video/3gpp" -> "H.263"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC-3 (Dolby Digital)"
        "audio/eac3" -> "E-AC-3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> if (m.isEmpty()) "-" else m
    }

    private fun bitrateLabel(b: Long): String =
        if (b <= 0) "" else if (b >= 1000000) String.format("%.1f Mbps", b / 1000000.0) else (b / 1000).toString() + " kbps"

    private fun sizeLabel(b: Long): String =
        if (b >= 1073741824L) String.format("%.2f GB", b / 1073741824.0) else String.format("%.1f MB", b / 1048576.0)

    private fun durLabel(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    // ---------- probing ----------
    private fun geti(f: MediaFormat, key: String): Int {
        if (!f.containsKey(key)) return 0
        return try {
            f.getInteger(key)
        } catch (e: Exception) {
            0
        }
    }

    private fun getf(f: MediaFormat, key: String): Float {
        if (!f.containsKey(key)) return 0f
        return try {
            f.getInteger(key).toFloat()
        } catch (e: Exception) {
            try {
                f.getFloat(key)
            } catch (e2: Exception) {
                0f
            }
        }
    }

    fun probe(c: Context, u: Uri): Info {
        val key = u.toString()
        val hit = cache[key]
        if (hit != null) return hit
        var info = EMPTY
        val ex = MediaExtractor()
        try {
            ex.setDataSource(c, u, null)
            var w = 0
            var h = 0
            var fps = 0f
            var vm = ""
            var vb = 0L
            var dur = 0L
            var am = ""
            var ch = 0
            var sr = 0
            var ab = 0L
            var hdr = "SDR"
            var std = ""
            var range = ""
            var rot = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/") && vm.isEmpty()) {
                    vm = m
                    w = geti(f, MediaFormat.KEY_WIDTH)
                    h = geti(f, MediaFormat.KEY_HEIGHT)
                    fps = getf(f, MediaFormat.KEY_FRAME_RATE)
                    vb = geti(f, MediaFormat.KEY_BIT_RATE).toLong()
                    rot = geti(f, MediaFormat.KEY_ROTATION)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION) / 1000
                    val tr = geti(f, MediaFormat.KEY_COLOR_TRANSFER)
                    hdr = when {
                        m == "video/dolby-vision" -> "Dolby Vision"
                        tr == MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
                        tr == MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                        f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) -> "HDR10"
                        else -> "SDR"
                    }
                    std = when (geti(f, MediaFormat.KEY_COLOR_STANDARD)) {
                        1 -> "BT.709"
                        2, 4 -> "BT.601"
                        6 -> "BT.2020"
                        else -> ""
                    }
                    range = when (geti(f, MediaFormat.KEY_COLOR_RANGE)) {
                        1 -> "Full"
                        2 -> "Limited"
                        else -> ""
                    }
                } else if (m.startsWith("audio/") && am.isEmpty()) {
                    am = m
                    ch = geti(f, MediaFormat.KEY_CHANNEL_COUNT)
                    sr = geti(f, MediaFormat.KEY_SAMPLE_RATE)
                    ab = geti(f, MediaFormat.KEY_BIT_RATE).toLong()
                    if (dur == 0L && f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION) / 1000
                }
            }
            if ((fps <= 0f && vm.isNotEmpty()) || dur == 0L) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(c, u)
                    val fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (fps <= 0f && fc > 0 && d > 0) fps = fc * 1000f / d
                    if (dur == 0L) dur = d
                } catch (e: Exception) {
                } finally {
                    r.release()
                }
            }
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            info = Info(w, h, fps, vm, vb, am, ch, sr, ab, dur, hdr, std, range, rot)
        } catch (e: Exception) {
        } finally {
            ex.release()
        }
        cache[key] = info
        return info
    }

    // list ke liye: background me jaanch, phir done()
    fun probeAsync(c: Context, u: Uri, done: () -> Unit) {
        val key = u.toString()
        if (cache.containsKey(key)) return
        if (pending.putIfAbsent(key, true) != null) return
        val app = c.applicationContext
        pool.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            probe(app, u)
            pending.remove(key)
            done()
        }
    }

    // ---------- details dialog ----------
    private fun basic(c: Context, u: Uri): Triple<Long, String, Long> {
        var size = 0L
        var path = ""
        var date = 0L
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_ADDED
            )
            c.contentResolver.query(u, proj, null, null, null)?.use { q ->
                if (q.moveToFirst()) {
                    size = q.getLong(0)
                    path = q.getString(1) ?: ""
                    date = q.getLong(2)
                }
            }
        } catch (e: Exception) {
            try {
                c.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { q ->
                    if (q.moveToFirst()) size = q.getLong(0)
                }
            } catch (e2: Exception) {
            }
        }
        return Triple(size, path, date)
    }

    private fun describe(c: Context, u: Uri, name: String, i: Info): String {
        val sb = StringBuilder()
        sb.append("Name: ").append(name).append("\n")
        val meta = basic(c, u)
        if (meta.second.isNotEmpty()) sb.append("Location: ").append(meta.second).append("\n")
        if (meta.first > 0) sb.append("Size: ").append(sizeLabel(meta.first)).append("\n")
        if (i.durMs > 0) sb.append("Duration: ").append(durLabel(i.durMs)).append("\n")
        if (i.w > 0) {
            sb.append("Resolution: ").append(i.w).append(" x ").append(i.h)
                .append("  (").append(resLabel(i.w, i.h)).append(")\n")
            if (i.fps > 0f) sb.append("Frame rate: ").append(fpsLabel(i.fps)).append(" fps\n")
            sb.append("Video codec: ").append(codecName(i.vMime)).append("\n")
            val vbl = bitrateLabel(i.vBitrate)
            if (vbl.isNotEmpty()) sb.append("Video bitrate: ").append(vbl).append("\n")
            sb.append("HDR: ").append(i.hdr).append("\n")
            if (i.std.isNotEmpty() || i.range.isNotEmpty()) {
                sb.append("Colour: ").append(i.std)
                if (i.std.isNotEmpty() && i.range.isNotEmpty()) sb.append("  \u2022  ")
                sb.append(i.range).append("\n")
            }
            if (i.rot != 0) sb.append("Rotation: ").append(i.rot).append("\u00B0\n")
        }
        if (i.aMime.isNotEmpty()) {
            sb.append("Audio: ").append(codecName(i.aMime))
            if (i.aChannels > 0) sb.append("  \u2022  ").append(i.aChannels).append(" ch")
            if (i.aRate > 0) sb.append("  \u2022  ").append(i.aRate / 1000.0).append(" kHz")
            val abl = bitrateLabel(i.aBitrate)
            if (abl.isNotEmpty()) sb.append("  \u2022  ").append(abl)
            sb.append("\n")
        }
        val type = try {
            c.contentResolver.getType(u)
        } catch (e: Exception) {
            null
        }
        if (type != null) sb.append("Type: ").append(type).append("\n")
        if (meta.third > 0) sb.append("Added: ").append(DateFormat.getDateTimeInstance().format(Date(meta.third * 1000))).append("\n")
        if (i.w == 0 && i.aMime.isEmpty()) sb.append("\n(Detailed info is not available for this source)")
        return sb.toString().trim()
    }

    private fun copy(a: Activity, text: String) {
        try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RajPlayer details", text))
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    fun show(a: Activity, u: Uri, name: String) {
        Toast.makeText(a, "Loading details...", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        Thread {
            val info = probe(app, u)
            val txt = describe(app, u, name, info)
            a.runOnUiThread {
                if (!a.isFinishing) {
                    AlertDialog.Builder(a)
                        .setTitle("Details")
                        .setMessage(txt)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Copy") { _, _ -> copy(a, txt) }
                        .show()
                }
            }
        }.start()
    }
}
