package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

// Battery / background permission + wake lock
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

    // pehli baar permission maangta hai
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

    // Playback ke time CPU jaagta rahe (screen off me bhi)
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

// Optional features (Convert / Orient) ke liye safe jodne wale hooks
object Hooks {
    fun convert(a: Activity, uris: List<Uri>, names: List<String>, fin: (() -> Unit)?) {
        Toast.makeText(a, "Convert feature installed nahi hai", Toast.LENGTH_SHORT).show()
    }

    fun videoClass(c: Context, uri: Uri?): Class<*> {
        return PlayerActivity::class.java
    }
}
