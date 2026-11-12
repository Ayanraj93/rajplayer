package com.raj.rajplayer

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

// Video kis engine se chalegi: Media3 (fast) ya VLC (fallback)
object Router {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    // auto | media3 | vlc
    fun mode(c: Context): String = p(c).getString("engine_mode", "auto") ?: "auto"

    fun cycle(c: Context) {
        val n = when (mode(c)) {
            "auto" -> "media3"
            "media3" -> "vlc"
            else -> "auto"
        }
        p(c).edit().putString("engine_mode", n).apply()
    }

    fun modeLabel(c: Context): String = when (mode(c)) {
        "media3" -> "Media3 (fast)"
        "vlc" -> "VLC"
        else -> "Auto (Media3, VLC fallback)"
    }

    private val okExt = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "ts", "mts", "m2ts", "flv")
    private val cache = ConcurrentHashMap<String, Boolean>()

    fun useMedia3(c: Context, uri: Uri): Boolean {
        if (mode(c) == "vlc") return false
        if (ColorGrade.on(c)) return false
        val s = uri.scheme
        if (s != "content" && s != "file") return false
        if (mode(c) == "media3") return true
        val key = uri.toString()
        val hit = cache[key]
        if (hit != null) return hit
        val name = try {
            Engine.nameOf(c, uri)
        } catch (e: Exception) {
            ""
        }
        val ok = okExt.contains(name.substringAfterLast('.', "").lowercase())
        cache[key] = ok
        return ok
    }

    fun m3Class(c: Context, uri: Uri?): Class<*> {
        if (uri == null || !Prefs.autoOrient(c)) return M3PlayerActivity::class.java
        return when (Orient.kindSync(c, uri)) {
            1 -> M3PortraitActivity::class.java
            0 -> M3LandscapeActivity::class.java
            else -> M3PlayerActivity::class.java
        }
    }

    // MainActivity yahi se activity chunti hai
    fun videoClass(c: Context, uri: Uri?): Class<*> {
        if (uri == null) return PlayerActivity::class.java
        // jo abhi chal raha hai (mini bar / notification) wahi khulna chahiye
        if (Engine.active && Engine.isVideo && uri == Engine.items.getOrNull(Engine.idx)) return Orient.videoClass(c, uri)
        if (M3Holder.player != null && uri == M3Holder.currentUri()) return m3Class(c, uri)
        val m3 = useMedia3(c, uri)
        if (m3) {
            if (Engine.active) Engine.stopAll(c)
        } else {
            if (M3Holder.player != null) M3Holder.release(c)
        }
        return if (m3) m3Class(c, uri) else Orient.videoClass(c, uri)
    }
}
