package com.raj.rajplayer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Video ki orientation background me pata karta hai, start me ruk-ruk nahi hota
object Orient {
    private val cache = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newSingleThreadExecutor()

    private fun put(a: Activity, k: Int, cb: (Boolean) -> Unit) {
        cb(k == 1)
        a.requestedOrientation = when (k) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun setFor(a: Activity, uri: Uri, cb: (Boolean) -> Unit) {
        val key = uri.toString()
        val c = cache[key]
        if (c != null) {
            put(a, c, cb)
            return
        }
        put(a, 0, cb)
        pool.execute {
            val k = probe(a, uri)
            cache[key] = k
            if (k != 0) a.runOnUiThread { if (!a.isFinishing) put(a, k, cb) }
        }
    }

    // 0 = landscape, 1 = portrait, 2 = pata nahi
    private fun probe(a: Activity, uri: Uri): Int {
        try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                val r = MediaMetadataRetriever()
                r.setDataSource(a, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                r.release()
                val dw = if (rot == 90 || rot == 270) h else w
                val dh = if (rot == 90 || rot == 270) w else h
                if (dw == 0 && dh == 0) return 2
                if (dh > dw) return 1
            }
        } catch (e: Exception) {
        }
        return 0
    }
}
