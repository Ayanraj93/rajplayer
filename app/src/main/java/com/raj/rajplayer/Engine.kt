package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

object Engine {

    interface Listener {
        fun onItemChanged()
    }

    var vlc: LibVLC? = null
    var mp: MediaPlayer? = null
    var items: List<Uri> = emptyList()
    var names: List<String> = emptyList()
    var idx = 0
    var title = ""
    var isVideo = false
    var active = false
    var ended = false
    var bgAudio = false
    var listener: Listener? = null

    private lateinit var app: Context
    private var pfd: ParcelFileDescriptor? = null
    private var pendingSeek = 0L
    private val main = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private val sleepRun = Runnable {
        pause()
        notifyChanged()
    }

    fun ensure(ctx: Context): MediaPlayer {
        app = ctx.applicationContext
        val existing = mp
        if (existing != null) return existing
        val v = LibVLC(app)
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
            if (pendingSeek > 0) {
                p.setTime(pendingSeek)
                pendingSeek = 0
            }
            notifyChanged()
        } else if (t == MediaPlayer.Event.Paused) {
            notifyChanged()
        } else if (t == MediaPlayer.Event.EndReached) {
            onEnded()
        }
    }

    private fun onEnded() {
        if (items.isEmpty()) return
        Prefs.setPos(app, items[idx].toString(), 0L)
        val rep = Prefs.repeat(app)
        if (rep == 2) {
            loadAt(app, idx)
        } else if (Prefs.shuffle(app) && items.size > 1) {
            loadAt(app, randomOther())
        } else if (idx + 1 < items.size) {
            loadAt(app, idx + 1)
        } else if (rep == 1) {
            loadAt(app, 0)
        } else {
            ended = true
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
            media.setHWDecoderEnabled(Prefs.hw(app), false)
            p.setMedia(media)
            media.release()
            requestFocus()
            p.play()
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
            p.pause()
            saveProgress()
        } else {
            requestFocus()
            p.play()
        }
        notifyChanged()
    }

    fun pause() {
        mp?.pause()
        saveProgress()
    }

    fun next(ctx: Context) {
        if (items.isEmpty()) return
        if (Prefs.shuffle(app) && items.size > 1) loadAt(ctx, randomOther())
        else if (idx + 1 < items.size) loadAt(ctx, idx + 1)
        else if (Prefs.repeat(app) == 1) loadAt(ctx, 0)
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
        PlaybackService.stop(ctx)
        listener?.onItemChanged()
    }

    fun notifyChanged() {
        PlaybackService.instance?.refresh()
    }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var r = focusReq
            if (r == null) {
                r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener { c ->
                        if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            main.post {
                                pause()
                                notifyChanged()
                            }
                        }
                    }
                    .build()
                focusReq = r
            }
            am.requestAudioFocus(r)
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
