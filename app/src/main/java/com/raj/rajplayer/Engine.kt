package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
object Engine {
    interface Listener { fun onItemChanged() }

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
    @Volatile var droppedFrames: Long = 0L
        private set
    @Volatile var renderedFrames: Long = 0L
        private set
    @Volatile var videoDecoderName: String = "Not initialized"
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastDropWarningAt = 0L
    private val sleepRun = Runnable { pause(); notifyChanged() }

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
            val net = u != null && (u.scheme == "http" || u.scheme == "https" || u.scheme == "rtsp")
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
        } catch (_: Exception) { }
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) { }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) { }
    }

    fun ensure(ctx: Context): MediaPlayer {
        app = ctx.applicationContext
        mp?.let { return it }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12000, 45000, 2500, 4000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(app)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
            // Force Media3's Sonic time-stretch path instead of device-specific AudioTrack speed.
            .setEnableAudioOutputPlaybackParameters(false)
        val exo = ExoPlayer.Builder(app, renderers)
            .setLoadControl(loadControl)
            .build()
        exo.setPlaybackParameters(PlaybackParameters(4f, 1f))
        exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        exo.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { active = true; if (exo.isPlaying) holdLocks(); notifyChanged() }
                    Player.STATE_ENDED -> onEnded()
                    Player.STATE_BUFFERING -> notifyChanged()
                    Player.STATE_IDLE -> notifyChanged()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) holdLocks() else releaseLocks()
                notifyChanged()
            }
            override fun onRenderedFirstFrame() {
                renderedFrames++
                notifyChanged()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                active = false
                releaseLocks()
                Toast.makeText(app, "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                notifyChanged()
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                this@Engine.renderedFrames += frameCount.toLong()
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoderName = decoderName
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                this@Engine.droppedFrames += droppedFrames.toLong()
                val now = android.os.SystemClock.elapsedRealtime()
                // Report measured drops; do not reload the item or switch to software decoding mid-playback.
                if (droppedFrames >= 12 && now - lastDropWarningAt > 10000L) {
                    lastDropWarningAt = now
                    main.post { Toast.makeText(app, "Frame drops detected (${this@Engine.droppedFrames} total). Playback timeline kept running.", Toast.LENGTH_SHORT).show() }
                }
            }
        })
        val compat = MediaPlayer(exo)
        mp = compat
        return compat
    }

    fun nameOf(ctx: Context, u: Uri): String {
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return u.lastPathSegment ?: "Media"
    }

    fun setQueue(ctx: Context, uris: List<Uri>, nm: List<String>, index: Int, video: Boolean) {
        if (uris.isEmpty()) return
        ensure(ctx)
        items = uris
        names = nm
        isVideo = video
        droppedFrames = 0L
        renderedFrames = 0L
        loadAt(ctx, index.coerceIn(0, uris.size - 1))
    }

    fun cur(): Uri = items[idx]
    fun isPlaying(): Boolean = mp?.isPlaying == true

    fun loadAt(ctx: Context, i: Int) {
        val p = ensure(ctx)
        if (items.isEmpty()) return
        saveProgress()
        idx = i.coerceIn(0, items.size - 1)
        val uri = items[idx]
        title = names.getOrNull(idx) ?: nameOf(app, uri)
        val resume = Prefs.pos(app, uri.toString())
        Prefs.setLastPlayedUri(app, uri.toString())
        videoDecoderName = "Initializing MediaCodec…"
        ended = false
        droppedFrames = 0L
        renderedFrames = 0L
        try {
            val item = MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
            p.player.setMediaItem(item, resume.coerceAtLeast(0L))
            p.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            p.player.setPlaybackParameters(PlaybackParameters(4f, 1f))
            requestFocus()
            p.player.prepare()
            p.player.playWhenReady = true
            p.player.play()
            active = true
            holdLocks()
        } catch (e: Exception) {
            Toast.makeText(app, "File nahi khuli: ${e.message}", Toast.LENGTH_LONG).show()
        }
        if (!isVideo && PlaybackService.instance == null) PlaybackService.start(ctx)
        listener?.onItemChanged()
        notifyChanged()
    }

    private fun onEnded() {
        if (items.isEmpty()) return
        Prefs.setPos(app, items[idx].toString(), 0L)
        val rep = Prefs.repeatFor(app, isVideo)
        when {
            rep == 2 -> loadAt(app, idx)
            Prefs.shuffleFor(app, isVideo) && items.size > 1 -> loadAt(app, randomOther())
            idx + 1 < items.size -> loadAt(app, idx + 1)
            rep == 1 -> loadAt(app, 0)
            else -> { ended = true; active = false; releaseLocks(); notifyChanged() }
        }
    }
    private fun randomOther(): Int {
        var r = (0 until items.size).random()
        if (r == idx) r = (r + 1) % items.size
        return r
    }

    fun toggle(ctx: Context) {
        val p = mp ?: return
        if (ended) { loadAt(ctx, idx); return }
        if (p.isPlaying) { resumeOnFocus = false; p.pause(); saveProgress(); releaseLocks() }
        else { requestFocus(); p.play(); holdLocks() }
        notifyChanged()
    }
    fun pause() { resumeOnFocus = false; mp?.pause(); saveProgress(); releaseLocks() }
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
    fun seekTo(ms: Long) { mp?.setTime(ms) }
    fun saveProgress() {
        val p = mp ?: return
        if (!active || items.isEmpty()) return
        val len = p.getLength(); val t = p.getTime(); val key = items[idx].toString()
        if (len > 0 && t > 5000 && t < len - 5000) Prefs.setPos(app, key, t) else Prefs.setPos(app, key, 0L)
    }
    fun sleep(minutes: Int) { main.removeCallbacks(sleepRun); if (minutes > 0) main.postDelayed(sleepRun, minutes * 60000L) }
    fun stopAll(ctx: Context) {
        saveProgress(); main.removeCallbacks(sleepRun); resumeOnFocus = false
        try { mp?.player?.stop(); mp?.player?.release() } catch (_: Exception) { }
        mp = null; active = false; ended = false; releaseLocks(); PlaybackService.stop(ctx); listener?.onItemChanged()
    }
    fun notifyChanged() { if (isPlaying()) holdLocks(); PlaybackService.instance?.refresh() }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (focusReq == null) {
                val attrs = AndroidAudioAttributes.Builder().setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                    .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC).build()
                focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { change -> main.post {
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; mp?.play(); holdLocks(); notifyChanged() }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying()) { resumeOnFocus = true; mp?.pause(); notifyChanged() }
                            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause(); notifyChanged() }
                        }
                    } }.build()
            }
            focusReq?.let { am.requestAudioFocus(it) }
        } catch (_: Exception) { }
    }
}

object Dialogs {
    fun eq(a: Activity) {
        val p = Engine.mp ?: return
        val n = MediaPlayer.Equalizer.getPresetCount()
        val names = ArrayList<String>(); names.add("Normal / Off")
        for (k in 0 until n) names.add(MediaPlayer.Equalizer.getPresetName(k))
        AlertDialog.Builder(a).setTitle("Equalizer Presets").setItems(names.toTypedArray()) { _, i ->
            if (i == 0) p.setEqualizer(null) else p.setEqualizer(MediaPlayer.Equalizer.createFromPreset(i - 1))
            Toast.makeText(a, "Equalizer: ${names[i]}", Toast.LENGTH_SHORT).show()
        }.show()
    }
    fun boost(a: Activity) {
        val p = Engine.mp ?: return
        val vals = intArrayOf(100, 125, 150, 175, 200)
        val names = arrayOf("100% (Normal)", "125%", "150%", "175%", "200% (Max Boost)")
        AlertDialog.Builder(a).setTitle("Audio Volume Boost").setItems(names) { _, i ->
            p.setVolume(vals[i]); Toast.makeText(a, "Audio Boost ${vals[i]}% (device output capped at 100%)", Toast.LENGTH_SHORT).show()
        }.show()
    }
    fun sleep(a: Activity) {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        val names = arrayOf("Off", "15 mins", "30 mins", "45 mins", "60 mins", "90 mins")
        AlertDialog.Builder(a).setTitle("Sleep Timer").setItems(names) { _, i ->
            Engine.sleep(mins[i]); Toast.makeText(a, if (mins[i] > 0) "Sleep timer: ${names[i]}" else "Sleep timer off", Toast.LENGTH_SHORT).show()
        }.show()
    }
}
