package com.raj.rajplayer

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Video ki asli shape (16:9 / 9:16) pata karke sahi screen me bina flip ke kholta hai
object Orient {
    private val cache = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newSingleThreadExecutor()

    // 0 = landscape, 1 = portrait, 2 = pata nahi
    fun flag(k: Int): Int = when (k) {
        1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // kaun si screen (activity) kholni hai
    fun videoClass(c: Context, uri: Uri?): Class<*> {
        if (uri == null || !Prefs.autoOrient(c)) return PlayerActivity::class.java
        return when (kindSync(c, uri)) {
            1 -> PlayerPortraitActivity::class.java
            0 -> PlayerLandscapeActivity::class.java
            else -> PlayerActivity::class.java
        }
    }

    fun kindSync(c: Context, uri: Uri): Int {
        val key = uri.toString()
        val hit = cache[key]
        if (hit != null) return hit
        var k = fromStore(c, uri)
        if (k < 0) k = probe(c, uri)
        cache[key] = k
        return k
    }

    // video badalne par (next / prev)
    fun setFor(a: Activity, uri: Uri, cb: (Boolean) -> Unit) {
        val key = uri.toString()
        val c = cache[key]
        if (c != null) {
            cb(c == 1)
            a.requestedOrientation = flag(c)
            return
        }
        pool.execute {
            val k = probe(a, uri)
            cache[key] = k
            a.runOnUiThread {
                if (!a.isFinishing) {
                    cb(k == 1)
                    a.requestedOrientation = flag(k)
                }
            }
        }
    }

    private fun fromStore(c: Context, uri: Uri): Int {
        if (uri.scheme != "content") return -1
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.ORIENTATION
            )
            c.contentResolver.query(uri, proj, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val w = cur.getInt(0)
                    val h = cur.getInt(1)
                    val rot = cur.getInt(2)
                    if (w > 0 && h > 0) {
                        val dw = if (rot == 90 || rot == 270) h else w
                        val dh = if (rot == 90 || rot == 270) w else h
                        return if (dh > dw) 1 else 0
                    }
                }
            }
        } catch (e: Exception) {
        }
        return -1
    }

    private fun probe(c: Context, uri: Uri): Int {
        try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                val r = MediaMetadataRetriever()
                r.setDataSource(c, uri)
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
