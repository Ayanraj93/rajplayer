package com.raj.rajplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

// Background playback ke helpers
object Bg {
    private var savedTrack = -2
    private var savedKey = ""
    private var wl: PowerManager.WakeLock? = null

    // Background me video ki tasveer band, sirf awaaz (battery bachti hai)
    fun toAudioOnly() {
        val p = Engine.mp ?: return
        if (Engine.items.isEmpty()) return
        try {
            val t = p.getVideoTrack()
            if (t >= 0) {
                savedTrack = t
                savedKey = Engine.cur().toString()
                p.setVideoTrack(-1)
            }
        } catch (e: Exception) {
        }
    }

    // App me wapas aane par video chalu
    fun restoreVideo() {
        val p = Engine.mp ?: return
        if (savedTrack < 0 || Engine.items.isEmpty()) return
        try {
            if (Engine.cur().toString() == savedKey) p.setVideoTrack(savedTrack)
        } catch (e: Exception) {
        }
        savedTrack = -2
        savedKey = ""
    }

    // Screen off me bhi CPU jaagta rahe
    fun wake(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (wl == null) {
                    val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
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

    // Battery settings kholo
    fun battery(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Toast.makeText(a, "All apps me RajPlayer chuno -> Don't optimize / Unrestricted", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.fromParts("package", a.packageName, null)
                a.startActivity(i)
            } catch (e2: Exception) {
            }
        }
    }
}
