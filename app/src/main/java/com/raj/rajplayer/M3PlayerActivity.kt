package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

// Media3 video player: MediaCodec + SurfaceView (zero-copy), pitch-correct speed 0.25x..4x
@androidx.annotation.OptIn(UnstableApi::class)
open class M3PlayerActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val p: ExoPlayer get() = M3Holder.player!!

    private lateinit var root: FrameLayout
    private lateinit var pv: PlayerView
    private lateinit var touchView: View
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var titleTv: TextView
    private lateinit var msgTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var audioMgr: AudioManager
    private var atomic: AtomicView? = null
    private var iconCol = Color.WHITE
    private var slop = 24

    private val ui = Handler(Looper.getMainLooper())

    private var locked = false
    private var userSeeking = false
    private var videoPortrait = false
    private var switching = false
    private var abA = -1L
    private var abB = -1L
    private var saveCount = 0
    private var sleepRun: Runnable? = null

    // gestures
    private var gMode = 0
    private var seekTarget = 0L
    private var brightCur = 0.5f
    private var volCur = 0.5f

    // zoom
    private var zooming = false
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastFx = 0f
    private var lastFy = 0f

    // speed + auto-fix
    private var rate = 1f
    private val guard = SpeedGuard()
    private var decoderSwitched = false

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private val resizeNames = listOf("Fit", "Crop", "Fill", "Width", "Height")
    private var resizeIdx = 0

    private val hideRun = Runnable { hideControls() }
    private val hideMsg = Runnable { msgTv.visibility = View.GONE }

    private val plis = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playBtn.text = if (isPlaying) "\u23F8" else "\u25B6"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onItem()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError()
        }

        override fun onTracksChanged(tracks: Tracks) {
            checkTracks(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) videoPortrait = videoSize.height > videoSize.width
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val pl = M3Holder.player
            if (pl != null && !switching) {
                val len = pl.duration
                val t = pl.currentPosition
                if (len > 0) {
                    if (!userSeeking) {
                        seek.max = len.toInt()
                        seek.progress = t.toInt()
                    }
                    durTv.text = fmt(len)
                }
                if (abA >= 0 && abB > abA && t >= abB) seekTo(abA)
                if (!userSeeking) curTv.text = fmt(t)
                if (Tuning.autoFix(this@M3PlayerActivity)) {
                    val act = guard.sample(SystemClock.elapsedRealtime(), t, pl.isPlaying && !userSeeking, rate)
                    if (act != 0) handleGuard(act)
                }
                saveCount++
                if (saveCount % 10 == 0) savePos()
            }
            ui.postDelayed(this, if (rate > 2f) 800L else 500L)
        }
    }

    // ================= helpers =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE
    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun rateLabel(r: Float): String =
        String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.') + "x"

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun ib(sym: String, size: Float, f: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = size
        t.setTextColor(iconCol)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(10), dp(8), dp(10), dp(8))
        t.setOnClickListener { f(t) }
        return t
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resetZoom()
        hideBars()
    }

    private fun showMsg(s: String) {
        msgTv.text = s
        msgTv.visibility = View.VISIBLE
        ui.removeCallbacks(hideMsg)
        ui.postDelayed(hideMsg, 900)
    }

    private fun showControls() {
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 4000)
    }

    private fun hideControls() {
        topBar.visibility = View.GONE
        controls.visibility = View.GONE
        lockBtn.visibility = View.GONE
    }

    private fun controlsVisible() = lockBtn.visibility == View.VISIBLE

    private fun setupCutout() {
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    private fun padForCutout() {
        val t0 = intArrayOf(topBar.paddingLeft, topBar.paddingTop, topBar.paddingRight, topBar.paddingBottom)
        val b0 = intArrayOf(controls.paddingLeft, controls.paddingTop, controls.paddingRight, controls.paddingBottom)
        val s0 = intArrayOf(lockBtn.paddingLeft, lockBtn.paddingTop, lockBtn.paddingRight, lockBtn.paddingBottom)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val c = ins.displayCutout
            val l = c?.safeInsetLeft ?: 0
            val t = c?.safeInsetTop ?: 0
            val r = c?.safeInsetRight ?: 0
            val b = c?.safeInsetBottom ?: 0
            topBar.setPadding(t0[0] + l, t0[1] + t, t0[2] + r, t0[3])
            controls.setPadding(b0[0] + l, b0[1], b0[2] + r, b0[3] + b)
            lockBtn.setPadding(s0[0] + l, s0[1], s0[2], s0[3])
            ins
        }
        root.requestApplyInsets()
    }

    // ================= zoom =================
    private fun applyZoom() {
        if (zoom <= 1f) {
            tx = 0f
            ty = 0f
        } else {
            val mx = (zoom - 1f) * pv.width / 2f
            val my = (zoom - 1f) * pv.height / 2f
            tx = tx.coerceIn(-mx, mx)
            ty = ty.coerceIn(-my, my)
        }
        pv.scaleX = zoom
        pv.scaleY = zoom
        pv.translationX = tx
        pv.translationY = ty
    }

    private fun resetZoom() {
        zoom = 1f
        tx = 0f
        ty = 0f
        if (::pv.isInitialized) applyZoom()
    }

    // ================= player state helpers =================
    private fun curUri(): Uri? = M3Holder.currentUri()

    private fun savePos() {
        val pl = M3Holder.player ?: return
        val u = curUri() ?: return
        val len = pl.duration
        val t = pl.currentPosition
        Prefs.setPos(this, u.toString(), if (len > 0 && t > 5000 && t < len - 5000) t else 0L)
    }

    private fun seekTo(ms: Long) {
        guard.reset()
        p.seekTo(ms)
    }

    private fun seekBy(ms: Long) {
        seekTo(Math.max(0L, p.currentPosition + ms))
        showMsg(if (ms > 0) "+" + (ms / 1000) + "s" else "" + (ms / 1000) + "s")
    }

    private fun togglePlay() {
        if (p.isPlaying) p.pause() else p.play()
        showControls()
    }

    private fun setVideoOff(off: Boolean) {
        val pl = M3Holder.player ?: return
        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off)
            .build()
    }

    // ================= speed / auto-fix =================
    private fun applyRate() {
        val pl = M3Holder.player ?: return
        pl.playbackParameters = PlaybackParameters(rate, 1f)
        atomic?.setPaused(rate > 2f)
        speedBtn.text = rateLabel(rate)
        guard.reset()
    }

    private fun setRateTo(r: Float) {
        rate = Math.round(r.coerceIn(0.25f, 4f) * 20f) / 20f
        applyRate()
    }

    private fun openSpeed() {
        ui.removeCallbacks(hideRun)
        SpeedPanel.show(this, rate, Prefs.accent(this), { r -> setRateTo(r) }, { decoderDialog() })
    }

    private fun handleGuard(act: Int) {
        when (act) {
            1 -> {
                seekTo(p.currentPosition)
                showMsg("Auto-fix: re-syncing")
            }
            2 -> {
                setRateTo(Math.max(1f, rate - 0.5f))
                showMsg("Auto-fix: speed " + rateLabel(rate) + " for smooth playback")
            }
            3 -> switchDecoder()
            else -> {}
        }
    }

    // ================= decoder / fallback =================
    private fun flipDecoder() {
        val key = curUri()?.toString() ?: return
        val sw = M3Holder.swFiles.contains(key) || (Tuning.decMode(this) == "sw" && !M3Holder.hwFiles.contains(key))
        if (sw) {
            M3Holder.hwFiles.add(key)
            M3Holder.swFiles.remove(key)
        } else {
            M3Holder.swFiles.add(key)
            M3Holder.hwFiles.remove(key)
        }
    }

    private fun rebuild() {
        M3Holder.rebuild(this)
        val np = M3Holder.player ?: return
        pv.player = np
        np.addListener(plis)
        applyRate()
    }

    private fun switchDecoder() {
        if (decoderSwitched || switching) return
        decoderSwitched = true
        flipDecoder()
        showMsg("Auto-fix: switching decoder")
        rebuild()
    }

    private fun handleError() {
        if (switching) return
        if (!decoderSwitched) {
            decoderSwitched = true
            flipDecoder()
            showMsg("Decoder error: switching decoder")
            rebuild()
        } else {
            toVlc("Media3 could not play this file - using VLC")
        }
    }

    // Media3 jo format na chala sake use VLC par bhejo (usi position se)
    private fun checkTracks(t: Tracks) {
        if (switching || t.groups.isEmpty()) return
        val a = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val v = t.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if ((a.isNotEmpty() && a.none { it.isSupported }) || (v.isNotEmpty() && v.none { it.isSupported })) {
            toVlc("Format not fully supported by Media3 - using VLC")
        }
    }

    private fun toVlc(msg: String) {
        if (switching) return
        switching = true
        val idx = M3Holder.player?.currentMediaItemIndex ?: 0
        savePos()
        val u = curUri()
        if (u == null) {
            finish()
            return
        }
        val i = Intent(this, Orient.videoClass(this, u))
        i.data = u
        i.putStringArrayListExtra("list", ArrayList(M3Holder.uris.map { it.toString() }))
        i.putStringArrayListExtra("names", ArrayList(M3Holder.names))
        i.putExtra("idx", idx)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        M3Holder.release(this)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        startActivity(i)
        finish()
    }

    private fun decoderDialog() {
        val modes = arrayOf("auto", "hw", "hwplus", "sw")
        val names = arrayOf(
            "Auto (HW, falls back to SW)",
            "HW decoder",
            "HW+ decoder (forced hardware)",
            "SW decoder (software)"
        )
        val cur = modes.indexOf(Tuning.decMode(this))
        AlertDialog.Builder(this)
            .setTitle("Decoder")
            .setSingleChoiceItems(names, cur) { dlg, i ->
                Tuning.setDecMode(this, modes[i])
                Tuning.clearOverrides()
                M3Holder.clearOverrides()
                dlg.dismiss()
                rebuild()
                showMsg("Decoder: " + names[i])
            }
            .show()
    }

    // ================= item / hdr =================
    private fun applyHdrWindow() {
        val u = curUri() ?: return
        val inf = VideoInfo.cached(u)
        ColorGrade.hdrWindow(this, inf != null && inf.hdr != "SDR")
    }

    private fun onItem() {
        val pl = M3Holder.player ?: return
        val u = curUri() ?: return
        titleTv.text = M3Holder.names.getOrNull(pl.currentMediaItemIndex) ?: ""
        abA = -1L
        abB = -1L
        guard.newItem()
        decoderSwitched = false
        Prefs.setLastPlayedUri(this, u.toString())
        if (Prefs.autoOrient(this)) Orient.setFor(this, u) { videoPortrait = it }
        resetZoom()
        VideoInfo.probeAsync(this, u) { runOnUiThread { applyHdrWindow() } }
        applyHdrWindow()
    }

    // ================= tracks / menu =================
    private fun trackDialog(type: Int, title: String) {
        val pl = M3Holder.player ?: return
        val labels = ArrayList<String>()
        val refs = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in pl.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                labels.add(f.label ?: f.language ?: ("Track " + (labels.size + 1)))
                refs.add(Pair(g, i))
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No tracks available", Toast.LENGTH_SHORT).show()
            return
        }
        val isText = type == C.TRACK_TYPE_TEXT
        if (isText) labels.add(0, "Off")
        val off = if (isText) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                val b = pl.trackSelectionParameters.buildUpon()
                if (isText && i == 0) {
                    b.setTrackTypeDisabled(type, true)
                } else {
                    val r = refs[i - off]
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(r.first.mediaTrackGroup, r.second))
                }
                pl.trackSelectionParameters = b.build()
            }
            .show()
    }

    private fun showInfo() {
        val u = curUri() ?: return
        VideoInfo.show(this, u, titleTv.text.toString())
    }

    private fun sleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        val names = arrayOf("Off", "15 min", "30 min", "45 min", "60 min", "90 min")
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(names) { _, i ->
                val old = sleepRun
                if (old != null) ui.removeCallbacks(old)
                if (mins[i] > 0) {
                    val r = Runnable { M3Holder.player?.pause() }
                    sleepRun = r
                    ui.postDelayed(r, mins[i] * 60000L)
                }
                showMsg(if (mins[i] > 0) "Sleep timer: " + names[i] else "Sleep timer off")
            }
            .show()
    }

    private fun showMenu() {
        val abLabel = if (abA < 0) "A-B Repeat: set point A"
        else if (abB < 0) "A-B Repeat: set point B"
        else "A-B Repeat: clear"
        val opts = arrayOf(
            "Speed (ruler)  " + rateLabel(rate),
            "Decoder: " + Tuning.decLabel(Tuning.decMode(this)),
            "Smart auto-fix: " + onOff(Tuning.autoFix(this)),
            "Audio Track",
            "Subtitles",
            "Video Info",
            "Sleep Timer",
            "Add Bookmark",
            "View Bookmarks",
            abLabel,
            "Background Audio: " + onOff(Prefs.bgAudio(this)),
            "Picture-in-Picture",
            "Convert to Audio (MP3, M4A...)",
            "Margin Animation: " + onOff(Prefs.videoMarginAnim(this)),
            "Auto-rotate to match video: " + onOff(Prefs.autoOrient(this)),
            "Allow Background (Battery)",
            "Switch to VLC engine (grading, equalizer, ext. subtitles)"
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> openSpeed()
                    1 -> decoderDialog()
                    2 -> {
                        Tuning.setAutoFix(this, !Tuning.autoFix(this))
                        guard.newItem()
                        showMsg("Smart auto-fix " + onOff(Tuning.autoFix(this)))
                    }
                    3 -> trackDialog(C.TRACK_TYPE_AUDIO, "Audio Track")
                    4 -> trackDialog(C.TRACK_TYPE_TEXT, "Subtitles")
                    5 -> showInfo()
                    6 -> sleepDialog()
                    7 -> addBookmark()
                    8 -> bookmarksDialog()
                    9 -> abStep()
                    10 -> {
                        Prefs.setBgAudio(this, !Prefs.bgAudio(this))
                        showMsg("Background audio " + onOff(Prefs.bgAudio(this)))
                    }
                    11 -> enterPip()
                    12 -> {
                        val u = curUri()
                        if (u != null) Hooks.convert(this, listOf(u), listOf(titleTv.text.toString()), null)
                    }
                    13 -> toggleMargin()
                    14 -> {
                        Prefs.setAutoOrient(this, !Prefs.autoOrient(this))
                        val u = curUri()
                        if (Prefs.autoOrient(this) && u != null) Orient.setFor(this, u) { videoPortrait = it }
                        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showMsg("Auto-rotate " + onOff(Prefs.autoOrient(this)))
                    }
                    15 -> Power.request(this)
                    16 -> toVlc("Switching to VLC engine")
                    else -> {}
                }
            }
            .show()
    }

    private fun toggleMargin() {
        Prefs.setVideoMarginAnim(this, !Prefs.videoMarginAnim(this))
        var av = atomic
        if (av == null && Prefs.videoMarginAnim(this) && Prefs.themeMode(this) != "none") {
            av = AtomicView(this, true)
            atomic = av
            root.addView(av, 0, FrameLayout.LayoutParams(MP, MP))
            av.excludeSurfacesIn(pv)
        }
        av?.applyPrefs()
        applyRate()
        showMsg("Margin animation " + onOff(Prefs.videoMarginAnim(this)))
    }

    private fun addBookmark() {
        val u = curUri() ?: return
        val key = u.toString()
        val t = p.currentPosition
        Prefs.setBms(this, key, (Prefs.bms(this, key) + t).sorted())
        showMsg("Bookmark added at " + fmt(t))
    }

    private fun bookmarksDialog() {
        val u = curUri() ?: return
        val key = u.toString()
        val l = Prefs.bms(this, key)
        if (l.isEmpty()) {
            Toast.makeText(this, "No bookmarks for this media", Toast.LENGTH_SHORT).show()
            return
        }
        val names = l.map { fmt(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(names) { _, i ->
                seekTo(l[i])
                showControls()
            }
            .setNeutralButton("Clear All") { _, _ -> Prefs.setBms(this, key, emptyList()) }
            .show()
    }

    private fun abStep() {
        if (abA < 0) {
            abA = p.currentPosition
            showMsg("Point A set at " + fmt(abA))
        } else if (abB < 0) {
            val t = p.currentPosition
            if (t > abA) {
                abB = t
                showMsg("Point B set. Loop active")
            } else {
                showMsg("Point B must be after A")
            }
        } else {
            abA = -1L
            abB = -1L
            showMsg("A-B repeat cleared")
        }
    }

    private fun enterPip() {
        if (locked) return
        try {
            val b = PictureInPictureParams.Builder()
                .setAspectRatio(if (videoPortrait) Rational(9, 16) else Rational(16, 9))
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val pl = M3Holder.player
        if (pl != null && pl.isPlaying && !locked && !Prefs.bgAudio(this)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        atomic?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) hideControls() else showControls()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (locked) {
            showMsg("Screen is locked. Long-press the lock icon to unlock.")
            return
        }
        super.onBackPressed()
    }

    // ================= touch: gestures + isolated zoom =================
    private fun endGesture() {
        if (gMode == 3) seekTo(seekTarget)
        gMode = 0
        ui.removeCallbacks(hideMsg)
        ui.postDelayed(hideMsg, 600)
    }

    private fun setupTouch() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                lastFx = d.focusX
                lastFy = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val ns = (zoom * d.scaleFactor).coerceIn(0.5f, 5f)
                val real = ns / zoom
                val cx = pv.width / 2f
                val cy = pv.height / 2f
                tx += (1f - real) * (d.focusX - cx - tx) + (d.focusX - lastFx)
                ty += (1f - real) * (d.focusY - cy - ty) + (d.focusY - lastFy)
                lastFx = d.focusX
                lastFy = d.focusY
                zoom = ns
                applyZoom()
                showMsg("Zoom " + Math.round(zoom * 100) + "%")
                return true
            }
        })
        scaleDetector.isQuickScaleEnabled = false

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible()) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                if (zoom != 1f) {
                    resetZoom()
                    showMsg("Zoom reset")
                    return true
                }
                val w = touchView.width
                val sMs = (Prefs.seekSec(this@M3PlayerActivity) * 1000).toLong()
                if (e.x < w / 3f) seekBy(-sMs)
                else if (e.x > w * 2f / 3f) seekBy(sMs)
                else togglePlay()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || zooming) return false
                if (zoom > 1.02f) {
                    tx -= dx
                    ty -= dy
                    applyZoom()
                    return true
                }
                val w = touchView.width.toFloat()
                val hgt = touchView.height.toFloat()
                if (gMode == 0) {
                    val mdx = Math.abs(e2.x - e1.x)
                    val mdy = Math.abs(e2.y - e1.y)
                    if (Math.max(mdx, mdy) < slop * 2) return true
                    gMode = if (mdx > mdy) 3 else if (e1.x < w / 2f) 1 else 2
                    if (gMode == 3) seekTarget = p.currentPosition
                    if (gMode == 1) {
                        val b = window.attributes.screenBrightness
                        brightCur = if (b < 0f) 0.5f else b
                    }
                    if (gMode == 2) {
                        val mx = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volCur = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / mx
                    }
                }
                if (gMode == 3) {
                    val len = Math.max(0L, p.duration)
                    seekTarget = (seekTarget - (dx * 100f).toLong()).coerceIn(0L, len)
                    showMsg(fmt(seekTarget) + " / " + fmt(len))
                } else if (gMode == 1) {
                    brightCur = (brightCur + dy / hgt * 1.5f).coerceIn(0.02f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = brightCur
                    window.attributes = lp
                    showMsg("Brightness " + Math.round(brightCur * 100) + "%")
                } else if (gMode == 2) {
                    volCur = (volCur + dy / hgt * 1.5f).coerceIn(0f, 1f)
                    val mx = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volCur * mx), 0)
                    showMsg("Volume " + Math.round(volCur * 100) + "%")
                }
                return true
            }
        })

        touchView.setOnTouchListener { _, ev ->
            val act = ev.actionMasked
            if (act == MotionEvent.ACTION_DOWN) zooming = false
            if (!zooming && ev.pointerCount > 1) {
                zooming = true
                gMode = 0
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                gd.onTouchEvent(cancel)
                cancel.recycle()
            }
            scaleDetector.onTouchEvent(ev)
            if (!zooming) {
                gd.onTouchEvent(ev)
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) endGesture()
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                ui.removeCallbacks(hideMsg)
                ui.postDelayed(hideMsg, 600)
            }
            true
        }
    }

    // ================= lifecycle =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cont = intent.getBooleanExtra("cont", false) && M3Holder.player != null
        if (!cont) {
            val u0 = intent.data
            if (u0 == null) {
                finish()
                return
            }
            val l = intent.getStringArrayListExtra("list")
            val uris: List<Uri> = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
            val nm = intent.getStringArrayListExtra("names")
            val names: List<String> = if (nm != null && nm.size == uris.size) nm.toList() else uris.map { Engine.nameOf(this, it) }
            var index = intent.getIntExtra("idx", 0)
            if (index < 0 || index >= uris.size) index = 0
            if (Engine.active) Engine.stopAll(this)
            M3Holder.open(this, uris, names, index, Prefs.pos(this, uris[index].toString()))
        }
        val firstUri: Uri? = M3Holder.currentUri()

        slop = ViewConfiguration.get(this).scaledTouchSlop
        if (Prefs.autoOrient(this)) {
            if (firstUri != null) {
                val k = Orient.kindSync(this, firstUri)
                videoPortrait = k == 1
                requestedOrientation = Orient.flag(k)
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        setupCutout()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        audioMgr = getSystemService(AUDIO_SERVICE) as AudioManager

        val ac = Prefs.accent(this)
        iconCol = if (Prefs.hasOverride(this, Prefs.K_ICON)) Prefs.iconColor(this) else Color.WHITE
        val txtCol = Prefs.videoTextColor(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        if (Prefs.videoMarginAnim(this) && Prefs.themeMode(this) != "none") {
            val av = AtomicView(this, true)
            atomic = av
            root.addView(av, FrameLayout.LayoutParams(MP, MP))
        }

        // SurfaceView (zero-copy) wala PlayerView, apne controls hum lagate hain
        pv = PlayerView(this)
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.setShutterBackgroundColor(Color.TRANSPARENT)
        root.addView(pv, FrameLayout.LayoutParams(MP, MP))
        atomic?.excludeSurfacesIn(pv)

        touchView = View(this)
        root.addView(touchView, FrameLayout.LayoutParams(MP, MP))

        msgTv = TextView(this)
        msgTv.textSize = 18f
        msgTv.setTextColor(Color.WHITE)
        val msgBg = GradientDrawable()
        msgBg.setColor(0xCC000000.toInt())
        msgBg.cornerRadius = dp(8).toFloat()
        msgTv.background = msgBg
        msgTv.setPadding(dp(20), dp(10), dp(20), dp(10))
        msgTv.visibility = View.GONE
        root.addView(msgTv, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))

        // ---- top bar ----
        topBar = LinearLayout(this)
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Prefs.videoBarColor(this))
        topBar.setPadding(dp(20), dp(8), dp(20), dp(8))
        topBar.addView(ib("\u2190", 22f) { finish() })
        titleTv = TextView(this)
        titleTv.setTextColor(txtCol)
        titleTv.textSize = 15f
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))
        topBar.addView(ib("\u2139\uFE0F", 20f) {
            showInfo()
            showControls()
        })
        topBar.addView(ib("\uD83D\uDD04", 20f) {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            showControls()
        })
        root.addView(topBar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        // ---- bottom controls ----
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(Prefs.videoBarColor(this))
        controls.setPadding(dp(20), dp(4), dp(20), dp(12))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(txtCol)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(txtCol)
        durTv.textSize = 12f
        durTv.text = "0:00"
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ac)
        seek.thumbTintList = ColorStateList.valueOf(ac)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                if (fromUser) curTv.text = fmt(pr.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
                ui.removeCallbacks(hideRun)
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                seekTo((s?.progress ?: 0).toLong())
                userSeeking = false
                showControls()
            }
        })
        seekRow.addView(curTv)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        seekRow.addView(durTv)
        controls.addView(seekRow)

        val trRow = LinearLayout(this)
        trRow.gravity = Gravity.CENTER
        trRow.addView(ib("\u23EE", 22f) {
            if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            else Toast.makeText(this, "First item", Toast.LENGTH_SHORT).show()
        })
        trRow.addView(ib("-" + Prefs.seekSec(this) + "s", 15f) {
            seekBy((-Prefs.seekSec(this) * 1000).toLong())
            showControls()
        })
        playBtn = ib("\u23F8", 24f) { togglePlay() }
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(ac)
        playBtn.background = circle
        playBtn.setTextColor(contrast(ac))
        val plp = LinearLayout.LayoutParams(dp(54), dp(54))
        plp.setMargins(dp(12), 0, dp(12), 0)
        trRow.addView(playBtn, plp)
        trRow.addView(ib("+" + Prefs.seekSec(this) + "s", 15f) {
            seekBy((Prefs.seekSec(this) * 1000).toLong())
            showControls()
        })
        trRow.addView(ib("\u23ED", 22f) {
            if (p.hasNextMediaItem()) p.seekToNextMediaItem()
            else Toast.makeText(this, "Last item", Toast.LENGTH_SHORT).show()
        })
        controls.addView(trRow)

        val exRow = LinearLayout(this)
        exRow.gravity = Gravity.CENTER
        speedBtn = ib("1x", 14f) { openSpeed() }
        speedBtn.setOnLongClickListener {
            setRateTo(1f)
            showMsg("Speed 1x")
            true
        }
        exRow.addView(speedBtn)
        exRow.addView(ib("Fit", 14f) { b ->
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            pv.resizeMode = resizeModes[resizeIdx]
            b.text = resizeNames[resizeIdx]
            resetZoom()
            showControls()
        })
        val repBtn = ib("\uD83D\uDD01", 18f) { b ->
            val v = (Prefs.repeatFor(this, true) + 1) % 3
            Prefs.setRepeatFor(this, true, v)
            p.repeatMode = if (v == 2) Player.REPEAT_MODE_ONE else if (v == 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            b.text = if (v == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
            b.alpha = if (v == 0) 0.35f else 1f
            showMsg(if (v == 0) "Repeat: Off" else if (v == 1) "Repeat: All" else "Repeat: One")
            showControls()
        }
        val rv = Prefs.repeatFor(this, true)
        repBtn.text = if (rv == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
        repBtn.alpha = if (rv == 0) 0.35f else 1f
        exRow.addView(repBtn)
        val shufBtn = ib("\uD83D\uDD00", 18f) { b ->
            val v = !Prefs.shuffleFor(this, true)
            Prefs.setShuffleFor(this, true, v)
            p.shuffleModeEnabled = v
            b.alpha = if (v) 1f else 0.35f
            showMsg(if (v) "Shuffle: On" else "Shuffle: Off")
            showControls()
        }
        shufBtn.alpha = if (Prefs.shuffleFor(this, true)) 1f else 0.35f
        exRow.addView(shufBtn)
        exRow.addView(ib("Audio", 14f) { trackDialog(C.TRACK_TYPE_AUDIO, "Audio Track") })
        exRow.addView(ib("Sub", 14f) { trackDialog(C.TRACK_TYPE_TEXT, "Subtitles") })
        exRow.addView(ib("\u2699\uFE0F", 18f) { showMenu() })
        controls.addView(exRow)
        root.addView(controls, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        lockBtn = ib("\uD83D\uDD13", 22f) { b ->
            if (locked) {
                showMsg("Long-press to unlock")
            } else {
                locked = true
                b.text = "\uD83D\uDD12"
                topBar.visibility = View.GONE
                controls.visibility = View.GONE
                showMsg("Screen locked")
            }
            showControls()
        }
        lockBtn.setOnLongClickListener {
            if (locked) {
                locked = false
                lockBtn.text = "\uD83D\uDD13"
                showMsg("Screen unlocked")
                showControls()
            }
            true
        }
        val llp = FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.CENTER_VERTICAL)
        llp.setMargins(dp(20), 0, 0, 0)
        root.addView(lockBtn, llp)

        setContentView(root)
        padForCutout()

        // player ko screen se jodo
        val pl = M3Holder.player
        if (pl == null) {
            finish()
            return
        }
        pv.player = pl
        pl.addListener(plis)
        pl.repeatMode = if (rv == 2) Player.REPEAT_MODE_ONE else if (rv == 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        pl.shuffleModeEnabled = Prefs.shuffleFor(this, true)
        playBtn.text = if (pl.isPlaying) "\u23F8" else "\u25B6"
        setupTouch()
        applyRate()
        onItem()
        showControls()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        M3Service.stop(this)
        setVideoOff(false)
        hideBars()
    }

    override fun onPause() {
        super.onPause()
        savePos()
        val pl = M3Holder.player
        if (!switching && pl != null && Prefs.bgAudio(this) && pl.isPlaying) M3Service.start(this)
    }

    override fun onStop() {
        super.onStop()
        val pl = M3Holder.player ?: return
        if (switching) return
        if (pl.isPlaying) {
            if (!Prefs.bgAudio(this)) pl.pause() else setVideoOff(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        val pl = M3Holder.player
        if (pl != null) {
            pl.removeListener(plis)
            if (::pv.isInitialized) pv.player = null
            if (!switching) {
                if (!Prefs.bgAudio(this) || !pl.isPlaying) {
                    M3Holder.release(this)
                } else {
                    M3Service.start(this)
                }
            }
        }
        ColorGrade.hdrWindow(this, false)
    }
}
