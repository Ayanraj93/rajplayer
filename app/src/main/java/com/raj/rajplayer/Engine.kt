package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

object Engine {

    interface Listener {
        fun onItemChanged()
    }

    var vlc: LibVLC? = null
    var mp: MediaPlayer? = null
    var vlayout: VLCVideoLayout? = null
    var items: List<Uri> = emptyList()
    var names: List<String> = emptyList()
    var idx = 0
    var title = ""
    var isVideo = false
    var active = false
    var ended = false
    var bgAudio = false
    var voutEver = false
    var vout = 0
    var forceSoft = false
    var listener: Listener? = null

    private lateinit var app: Context
    private var pfd: ParcelFileDescriptor? = null
    private var pendingSeek = 0L
    private val main = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var errStreak = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val sleepRun = Runnable {
        pause()
        notifyChanged()
    }

    private fun holdLocks() {
        try {
            var w = wake
            if (w == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RajPlayer:playback")
                w.setReferenceCounted(false)
                wake = w
            }
            if (w?.isHeld == false) w.acquire(6 * 60 * 60 * 1000L)

            val u = items.getOrNull(idx)
            val net = u != null && (u.scheme == "http" || u.scheme == "https" ||
                    u.scheme == "rtsp" || u.scheme == "rtmp")
            if (net) {
                var f = wifi
                if (f == null) {
                    val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    f = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RajPlayer:wifi")
                    f.setReferenceCounted(false)
                    wifi = f
                }
                if (f?.isHeld == false) f.acquire()
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            val w = wake
            if (w?.isHeld == true) w.release()
            val f = wifi
            if (f?.isHeld == true) f.release()
        } catch (e: Exception) {
        }
    }

    fun ensure(ctx: Context): MediaPlayer {
        app = ctx.applicationContext
        val existing = mp
        if (existing != null) return existing
        val v = LibVLC(app, Tuning.options())
        val p = MediaPlayer(v)
        p.setEventListener { ev ->
            val t = ev.type
            main.post { onEvent(t) }
        }
        vlc = v
        mp = p
        return p
    }

    private fun onEvent(t: Int) {
        val p = mp ?: return
        if (t == MediaPlayer.Event.Playing) {
            errStreak = 0
            voutEver = true
            vout = 1
            if (pendingSeek > 0) {
                p.setTime(pendingSeek)
                pendingSeek = 0
            }
            holdLocks()
            notifyChanged()
        } else if (t == MediaPlayer.Event.Paused) {
            releaseLocks()
            notifyChanged()
        } else if (t == MediaPlayer.Event.EndReached) {
            onEnded()
        } else if (t == MediaPlayer.Event.EncounteredError) {
            onError()
        }
    }

    private fun onError() {
        errStreak++
        if (errStreak > 5 || items.isEmpty() || idx + 1 >= items.size) {
            ended = true
            releaseLocks()
            notifyChanged()
            return
        }
        loadAt(app, idx + 1)
    }

    private fun onEnded() {
        if (items.isEmpty()) return
        Prefs.setPos(app, items[idx].toString(), 0L)
        val rep = Prefs.repeatFor(app, isVideo)
        if (rep == 2) {
            loadAt(app, idx)
        } else if (Prefs.shuffleFor(app, isVideo) && items.size > 1) {
            loadAt(app, randomOther())
        } else if (idx + 1 < items.size) {
            loadAt(app, idx + 1)
        } else if (rep == 1) {
            loadAt(app, 0)
        } else {
            ended = true
            releaseLocks()
            notifyChanged()
        }
    }

    private fun randomOther(): Int {
        var r = (0 until items.size).random()
        if (r == idx) r = (r + 1) % items.size
        return r
    }

    fun cur(): Uri = items[idx]

    fun isPlaying(): Boolean = mp?.isPlaying == true

    fun nameOf(ctx: Context, u: Uri): String {
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (n != null) return n
                }
            }
        } catch (e: Exception) {
        }
        return u.lastPathSegment ?: "Media"
    }

    fun setQueue(ctx: Context, uris: List<Uri>, nm: List<String>, index: Int, video: Boolean) {
        if (uris.isEmpty()) return
        ensure(ctx)
        items = uris
        names = nm
        isVideo = video
        errStreak = 0
        loadAt(ctx, index.coerceIn(0, uris.size - 1))
    }

    fun loadAt(ctx: Context, i: Int) {
        val p = ensure(ctx)
        if (items.isEmpty()) return
        saveProgress()
        idx = i.coerceIn(0, items.size - 1)
        val uri = items[idx]
        title = names.getOrNull(idx) ?: nameOf(app, uri)
        pendingSeek = Prefs.pos(app, uri.toString())
        Prefs.setLastPlayedUri(app, uri.toString())
        voutEver = false
        vout = 0
        val old = pfd
        try {
            val media: Media
            if (uri.scheme == "content") {
                pfd = app.contentResolver.openFileDescriptor(uri, "r")
                media = Media(vlc!!, pfd!!.fileDescriptor)
            } else {
                pfd = null
                media = Media(vlc!!, uri)
            }
            Tuning.applyDecoder(media, app, uri.toString())
            if (pendingSeek > 0) {
                media.addOption(":start-time=" + (pendingSeek / 1000.0))
                pendingSeek = 0
            }
            p.setMedia(media)
            media.release()
            resumeOnFocus = false
            requestFocus()
            p.play()
            holdLocks()
            active = true
            ended = false
        } catch (e: Exception) {
            Toast.makeText(app, "File nahi khuli: " + e.message, Toast.LENGTH_LONG).show()
        }
        try {
            old?.close()
        } catch (e: Exception) {
        }
        if (!isVideo && PlaybackService.instance == null) PlaybackService.start(ctx)
        listener?.onItemChanged()
        notifyChanged()
    }

    fun toggle(ctx: Context) {
        val p = mp ?: return
        if (ended) {
            loadAt(ctx, idx)
            return
        }
        if (p.isPlaying) {
            resumeOnFocus = false
            p.pause()
            saveProgress()
            releaseLocks()
        } else {
            requestFocus()
            p.play()
            holdLocks()
        }
        notifyChanged()
    }

    fun pause() {
        resumeOnFocus = false
        mp?.pause()
        saveProgress()
        releaseLocks()
    }

    fun next(ctx: Context) {
        if (items.isEmpty()) return
        if (Prefs.shuffleFor(app, isVideo) && items.size > 1) loadAt(ctx, randomOther())
        else if (idx + 1 < items.size) loadAt(ctx, idx + 1)
        else if (Prefs.repeatFor(app, isVideo) == 1) loadAt(ctx, 0)
    }

    fun prev(ctx: Context) {
        val p = mp ?: return
        if (p.getTime() > 3000 || idx == 0) p.setTime(0) else loadAt(ctx, idx - 1)
    }

    fun seekTo(ms: Long) {
        mp?.setTime(ms)
    }

    fun saveProgress() {
        val p = mp ?: return
        if (!active || items.isEmpty()) return
        val len = p.getLength()
        val t = p.getTime()
        val key = items[idx].toString()
        if (len > 0 && t > 5000 && t < len - 5000) Prefs.setPos(app, key, t) else Prefs.setPos(app, key, 0L)
    }

    fun sleep(minutes: Int) {
        main.removeCallbacks(sleepRun)
        if (minutes > 0) main.postDelayed(sleepRun, minutes * 60000L)
    }

    fun stopAll(ctx: Context) {
        saveProgress()
        main.removeCallbacks(sleepRun)
        resumeOnFocus = false
        try {
            mp?.stop()
        } catch (e: Exception) {
        }
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        pfd = null
        active = false
        ended = false
        releaseLocks()
        PlaybackService.stop(ctx)
        listener?.onItemChanged()
    }

    fun notifyChanged() {
        if (isPlaying()) holdLocks()
        PlaybackService.instance?.refresh()
    }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var r = focusReq
            if (r == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { c ->
                        main.post {
                            if (c == AudioManager.AUDIOFOCUS_GAIN) {
                                if (resumeOnFocus) {
                                    resumeOnFocus = false
                                    mp?.play()
                                    holdLocks()
                                    notifyChanged()
                                }
                            } else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                if (isPlaying()) {
                                    resumeOnFocus = true
                                    mp?.pause()
                                    notifyChanged()
                                }
                            } else if (c == AudioManager.AUDIOFOCUS_LOSS) {
                                resumeOnFocus = false
                                pause()
                                notifyChanged()
                            }
                        }
                    }
                    .build()
                focusReq = r
            }
            if (r != null) {
                am.requestAudioFocus(r)
            }
        } catch (e: Exception) {
        }
    }
}

object Dialogs {
    fun eq(a: Activity) {
        val p = Engine.mp ?: return
        val n = MediaPlayer.Equalizer.getPresetCount()
        val names = ArrayList<String>()
        names.add("Normal / Off")
        for (k in 0 until n) names.add(MediaPlayer.Equalizer.getPresetName(k))
        AlertDialog.Builder(a)
            .setTitle("Equalizer Presets")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) p.setEqualizer(null)
                else p.setEqualizer(MediaPlayer.Equalizer.createFromPreset(i - 1))
                Toast.makeText(a, "Equalizer: " + names[i], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun boost(a: Activity) {
        val p = Engine.mp ?: return
        val vals = intArrayOf(100, 125, 150, 175, 200)
        val names = arrayOf("100% (Normal)", "125%", "150%", "175%", "200% (Max Boost)")
        AlertDialog.Builder(a)
            .setTitle("Audio Volume Boost")
            .setItems(names) { _, i ->
                p.setVolume(vals[i])
                Toast.makeText(a, "Audio Boost " + vals[i] + "%", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun sleep(a: Activity) {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        val names = arrayOf("Off", "15 mins", "30 mins", "45 mins", "60 mins", "90 mins")
        AlertDialog.Builder(a)
            .setTitle("Sleep Timer")
            .setItems(names) { _, i ->
                Engine.sleep(mins[i])
                Toast.makeText(a, if (mins[i] > 0) "Sleep timer: " + names[i] else "Sleep timer off", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
