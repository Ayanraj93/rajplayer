package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
    @Volatile private var forceDav1dForCurrentItem: Boolean = false
    @Volatile private var av1FallbackTried: Boolean = false
    @Volatile private var forceSoftwareVideoForCurrentItem: Boolean = false
    @Volatile private var swFallbackTried: Boolean = false
    @Volatile var playerForeground: Boolean = false
    @Volatile var modeSwitching: Boolean = false
    @Volatile private var surfaceRecoveryCount: Int = 0
    @Volatile private var lastSurfaceRecoveryAt: Long = 0L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    @Volatile private var resumeAfterBluetooth = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
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
        registerAudioDeviceCallback()
        mp?.let { return it }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowMem = (am?.isLowRamDevice == true) || !android.os.Process.is64Bit() ||
            (Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (lowMem) 20000 else 30000,
                if (lowMem) 40000 else 60000,
                100,
                250
            )
            .setTargetBufferBytes(if (lowMem) 20 * 1024 * 1024 else 48 * 1024 * 1024)
            .setBackBuffer(if (lowMem) 3000 else 5000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val av1AwareSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (forceSoftwareVideoForCurrentItem && mimeType.startsWith("video/", ignoreCase = true)) {
                val swCodecs = codecs.filter {
                    it.softwareOnly || !it.hardwareAccelerated ||
                        it.name.startsWith("OMX.google.", ignoreCase = true) ||
                        it.name.startsWith("c2.android.", ignoreCase = true)
                }
                if (swCodecs.isNotEmpty()) swCodecs else codecs
            } else if (MimeTypes.VIDEO_AV1.equals(mimeType, ignoreCase = true) && Dav1dLibrary.isAvailable()) {
                if (forceDav1dForCurrentItem) {
                    emptyList()
                } else {
                    val hwCodecs = codecs.filter { it.hardwareAccelerated && !it.softwareOnly }
                    hwCodecs
                }
            } else {
                codecs
            }
        }
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                val bufferProvider = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(450_000)
                    .setMaxPcmBufferDurationUs(900_000)
                    .setPcmBufferMultiplicationFactor(4)
                    .build()
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioTrackBufferSizeProvider(bufferProvider)
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(av1AwareSelector)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
            // Force Media3's Sonic time-stretch path instead of device-specific AudioTrack speed.
            .setEnableAudioOutputPlaybackParameters(false)
        val mediaAudioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = ExoPlayer.Builder(app, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(mediaAudioAttrs, false)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(false)
            .setDetachSurfaceTimeoutMs(300L)
            .build()
        exo.setPriority(androidx.media3.common.C.PRIORITY_PLAYBACK)
        exo.setPlaybackParameters(PlaybackParameters(1f, 1f))
        exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        exo.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        exo.setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { active = true; surfaceRecoveryCount = 0; if (exo.isPlaying) holdLocks(); notifyChanged() }
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
                notifyChanged()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val errText = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ")
                val isSurfaceBreak =
                    errText.contains("ANativeWindow", ignoreCase = true) ||
                    errText.contains("output buffer to surface", ignoreCase = true) ||
                    errText.contains("Surface has been released", ignoreCase = true) ||
                    errText.contains("BufferQueue", ignoreCase = true) ||
                    errText.contains("abandoned", ignoreCase = true)
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastSurfaceRecoveryAt > 6000L) surfaceRecoveryCount = 0
                if (isSurfaceBreak && items.isNotEmpty() && surfaceRecoveryCount < 8) {
                    surfaceRecoveryCount++
                    lastSurfaceRecoveryAt = nowMs
                    val retryPos = exo.currentPosition.coerceAtLeast(0L)
                    val wasPlaying = exo.playWhenReady || exo.isPlaying
                    val uri = items[idx]
                    mp?.pauseSurface()
                    exo.stop()
                    val retryItem = MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
                    exo.setMediaItem(retryItem, retryPos)
                    exo.prepare()
                    exo.playWhenReady = wasPlaying
                    if (wasPlaying) exo.play()
                    if (playerForeground) {
                        main.postDelayed({
                            if (playerForeground && active) mp?.resumeSurface()
                        }, 16L)
                    }
                    notifyChanged()
                    return
                }
                val isDecoderFailure =
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                if (isDecoderFailure && isVideo && items.isNotEmpty()) {
                    val hasAv1 = exo.currentTracks.groups.any { g ->
                        (0 until g.length).any { i ->
                            MimeTypes.VIDEO_AV1.equals(g.getTrackFormat(i).sampleMimeType, ignoreCase = true)
                        }
                    }
                    if (hasAv1 && !av1FallbackTried && Dav1dLibrary.isAvailable()) {
                        av1FallbackTried = true
                        forceDav1dForCurrentItem = true
                        val retryPos = exo.currentPosition.coerceAtLeast(0L)
                        val uri = items[idx]
                        videoDecoderName = "Switching to dav1d AV1 fallback…"
                        exo.stop()
                        mp?.recreateSurface()
                        val retryItem = MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
                        exo.setMediaItem(retryItem, retryPos)
                        exo.prepare()
                        exo.playWhenReady = true
                        exo.play()
                        notifyChanged()
                        return
                    }
                    if (!swFallbackTried) {
                        swFallbackTried = true
                        forceSoftwareVideoForCurrentItem = true
                        val retryPos = exo.currentPosition.coerceAtLeast(0L)
                        val uri = items[idx]
                        videoDecoderName = "Switching to software video decoder…"
                        exo.stop()
                        mp?.recreateSurface()
                        val retryItem = MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
                        exo.setMediaItem(retryItem, retryPos)
                        exo.prepare()
                        exo.playWhenReady = true
                        exo.play()
                        notifyChanged()
                        return
                    }
                }
                active = false
                releaseLocks()
                val detail = error.cause?.message?.take(120) ?: error.message?.take(120) ?: ""
                val dav1dState = if (Dav1dLibrary.isAvailable()) "dav1d:on" else "dav1d:off"
                videoDecoderName = "Error (${error.errorCodeName}, $dav1dState): $detail"
                Toast.makeText(app, "Playback error: ${error.errorCodeName} ($dav1dState) $detail", Toast.LENGTH_LONG).show()
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
                // Keep internal frame-drop monitoring; do not interrupt playback with a Toast.
                this@Engine.droppedFrames += droppedFrames.toLong()
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
        val needsFreshSurface = isVideo && (
            forceDav1dForCurrentItem ||
            forceSoftwareVideoForCurrentItem ||
            videoDecoderName.contains("dav1d", ignoreCase = true)
        )
        forceDav1dForCurrentItem = false
        av1FallbackTried = false
        forceSoftwareVideoForCurrentItem = false
        swFallbackTried = false
        videoDecoderName = "Initializing MediaCodec…"
        ended = false
        resumeOnFocus = false
        resumeAfterBluetooth = false
        droppedFrames = 0L
        renderedFrames = 0L
        try {
            if (needsFreshSurface) {
                p.player.stop()
                p.recreateSurface()
            }
            val item = MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
            p.player.setMediaItem(item, resume.coerceAtLeast(0L))
            p.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            p.player.setPlaybackParameters(PlaybackParameters(1f, 1f))
            requestFocus()
            p.player.prepare()
            p.player.playWhenReady = true
            p.player.play()
            active = true
            holdLocks()
        } catch (e: Exception) {
            Toast.makeText(app, "File nahi khuli: ${e.message}", Toast.LENGTH_LONG).show()
        }
        // Keep the media foreground service available for audio and video playback.
        PlaybackService.start(ctx)
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
        if (p.isPlaying) { resumeOnFocus = false; resumeAfterBluetooth = false; p.pause(); saveProgress(); releaseLocks() }
        else { resumeAfterBluetooth = false; requestFocus(); p.play(); holdLocks() }
        notifyChanged()
    }
    fun pause() { resumeOnFocus = false; resumeAfterBluetooth = false; mp?.pause(); saveProgress(); releaseLocks() }
    fun next(ctx: Context) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            mp?.setTime(0)
            notifyChanged()
            return
        }
        if (Prefs.shuffleFor(app, isVideo)) loadAt(ctx, randomOther())
        else if (idx + 1 < items.size) loadAt(ctx, idx + 1)
        else loadAt(ctx, 0)
    }
    fun prev(ctx: Context) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            mp?.setTime(0)
            notifyChanged()
            return
        }
        val target = if (idx > 0) idx - 1 else items.lastIndex
        loadAt(ctx, target)
    }
    fun seekTo(ms: Long) { mp?.setTime(ms); notifyChanged() }
    fun saveProgress() {
        val p = mp ?: return
        if (!active || items.isEmpty()) return
        val len = p.getLength(); val t = p.getTime(); val key = items[idx].toString()
        if (len > 0 && t > 5000 && t < len - 5000) Prefs.setPos(app, key, t) else Prefs.setPos(app, key, 0L)
    }
    fun sleep(minutes: Int) { main.removeCallbacks(sleepRun); if (minutes > 0) main.postDelayed(sleepRun, minutes * 60000L) }
    fun stopAll(ctx: Context) {
        saveProgress(); main.removeCallbacks(sleepRun); resumeOnFocus = false; resumeAfterBluetooth = false
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
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { change -> main.post {
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                                resumeOnFocus = false
                                resumeAfterBluetooth = false
                                mp?.play()
                                holdLocks()
                                notifyChanged()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                // Do not duck or stutter on SMS/notification chimes or fingerprint UI.
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying()) { resumeOnFocus = true; mp?.pause(); notifyChanged() }
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                if (resumeAfterBluetooth) {
                                    resumeOnFocus = true
                                    mp?.pause()
                                    saveProgress()
                                    releaseLocks()
                                    notifyChanged()
                                } else {
                                    resumeOnFocus = false
                                    pause()
                                    notifyChanged()
                                }
                            }
                        }
                    } }.build()
            }
            val result = focusReq?.let { am.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && resumeOnFocus) {
                resumeOnFocus = false
                resumeAfterBluetooth = false
                mp?.play()
                holdLocks()
                notifyChanged()
            }
        } catch (_: Exception) { }
    }

    private fun isBluetoothOutput(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback != null) return
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    main.post {
                        val removedBt = removedDevices.any { isBluetoothOutput(it) }
                        val btStillConnected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBluetoothOutput(it) }
                        if (removedBt && !btStillConnected && (isPlaying() || resumeOnFocus)) {
                            resumeAfterBluetooth = true
                            resumeOnFocus = true
                            if (isPlaying()) mp?.pause()
                            saveProgress()
                            notifyChanged()
                        }
                    }
                }

                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    main.post {
                        if (addedDevices.any { isBluetoothOutput(it) } && resumeAfterBluetooth) {
                            requestFocus()
                        }
                    }
                }
            }
            am.registerAudioDeviceCallback(cb, main)
            audioDeviceCallback = cb
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
