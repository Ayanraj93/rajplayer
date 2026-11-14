package com.raj.rajplayer

import android.app.Activity
import android.view.animation.LinearInterpolator
import android.graphics.Matrix
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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
import java.io.File
import java.util.Locale

open class PlayerActivity : Activity() {

    companion object {
        var current: FrameLayout? = null
    }

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val mp: MediaPlayer get() = Engine.mp!!

    private lateinit var root: FrameLayout
    private lateinit var layout: FrameLayout
    private lateinit var touchView: View
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var titleTv: TextView
    private var titleRgbAnimator: ValueAnimator? = null
    private var titleRgbShader: LinearGradient? = null
    private val titleRgbMatrix = Matrix()
    private lateinit var msgTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var audioMgr: AudioManager
    private lateinit var audioModePanel: LinearLayout
    private lateinit var audioModeTitle: TextView
    private lateinit var audioModeSeek: SeekBar
    private lateinit var audioModePlayBtn: TextView
    private var audioMode = false
    private var audioChildOpen = false
    private var audioSettingsAnimator: ValueAnimator? = null
    private var playerGearAnimator: ValueAnimator? = null
    private var playerSettingsGear: TextView? = null
    private val controlRgbTargets = ArrayList<(Int) -> Unit>()
    private val controlRgbTick = object : Runnable {
        override fun run() {
            if (controlRgbTargets.isNotEmpty()) {
                val c = Theme.rgbNow(0f)
                controlRgbTargets.forEach { it(c) }
                ui.postDelayed(this, 70L)
            }
        }
    }
    private var atomic: AtomicView? = null
    private var iconCol = Color.WHITE
    private var slop = 24

    private val ui = Handler(Looper.getMainLooper())

    private var locked = false
    private var userSeeking = false
    private var videoPortrait = false
    private var abA = -1L
    private var abB = -1L

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

    // speed: 0.25x .. 4x (ruler) + smart auto-fix
    private var rate = 1f
    private var lastDropNoticeAt = 0L
    private var lastDropCountSeen = 0L

    private val scales = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3,
        MediaPlayer.ScaleType.SURFACE_ORIGINAL
    )
    private val scaleNames = listOf("Fit", "Screen", "Fill", "16:9", "4:3", "Orig")
    private var scaleIdx = 0

    private val hideRun = Runnable { hideControls() }
    private val hideMsg = Runnable { msgTv.visibility = View.GONE }
    private val rateRun = Runnable { applyRate() }

    private val lis = object : Engine.Listener {
        override fun onItemChanged() {
            runOnUiThread { onItem() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = Engine.mp
            if (p != null) {
                val len = p.getLength()
                val t = p.getTime()
                if (len > 0) {
                    if (!userSeeking) {
                        seek.max = len.toInt()
                        seek.progress = t.toInt()
                    }
                    durTv.text = fmt(len)
                }
                if (abA >= 0 && abB > abA && t >= abB) seekTo(abA)
                if (!userSeeking) curTv.text = fmt(t)
                playBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
                if (::audioModeSeek.isInitialized) {
                    if (!userSeeking && audioMode) {
                        audioModeSeek.max = len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        audioModeSeek.progress = t.toInt()
                    }
                    audioModePlayBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
                }
                // Media3 handles late-frame dropping against its playback clock. Do not
                // infer decoder failure from wall-clock position deltas or reload on a seek.
                val dropsNow = Engine.droppedFrames
                if (Tuning.autoFix(this@PlayerActivity) && dropsNow - lastDropCountSeen >= 8L) {
                    val now = SystemClock.elapsedRealtime()
                    lastDropCountSeen = dropsNow
                    if (now - lastDropNoticeAt > 8000L) {
                        lastDropNoticeAt = now
                        showMsg("Auto-Fix: Media3 is skipping late frames without restarting playback")
                    }
                }
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
        if (Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ICON)) {
            controlRgbTargets.add { c -> t.setTextColor(c) }
        }
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

    @Suppress("DEPRECATION")
    private fun showBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun showControls() {
        showBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 2600)
    }

    private fun hideControls() {
        hideBars()
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

    // ================= video surface (SurfaceView / TextureView for grading) =================
    private fun attachVideo() {
        try {
            mp.detachViews()
        } catch (e: Exception) {
        }
        mp.attachViews(layout, null, true, ColorGrade.on(this))
        atomic?.excludeSurfacesIn(layout)
        current = layout
        layout.post { ColorGrade.apply(this, layout) }
    }

    private fun enterAudioMode() {
        if (audioChildOpen || !Engine.active) return
        audioChildOpen = true
        ui.removeCallbacks(hideRun)
        try {
            val i = Intent(this, AudioActivity::class.java)
                .putExtra("cont", true)
                .putExtra("fromVideo", true)
            startActivity(i)
        } catch (e: Exception) {
            audioChildOpen = false
            Toast.makeText(this, "Audio Player open nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun returnToVideo() {
        if (!audioMode) return
        audioMode = false
        audioModePanel.visibility = View.GONE
        layout.visibility = View.VISIBLE
        touchView.visibility = View.VISIBLE
        atomic?.visibility = View.VISIBLE
        attachVideo()
        showControls()
        hideBars()
    }

    private fun updateAudioMode() {
        val p = Engine.mp ?: return
        audioModeTitle.text = Engine.title
        val len = p.getLength()
        val pos = p.getTime()
        if (!userSeeking && len > 0) {
            audioModeSeek.max = len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            audioModeSeek.progress = pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        audioModePlayBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
    }

    private fun applyHdrWindow() {
        if (Engine.items.isEmpty()) return
        val inf = VideoInfo.cached(Engine.cur())
        ColorGrade.hdrWindow(this, inf != null && inf.hdr != "SDR" && !ColorGrade.on(this))
    }

    private fun openGrade() {
        val inf = if (Engine.items.isEmpty()) null else VideoInfo.cached(Engine.cur())
        val hdr = inf?.hdr ?: "unknown"
        val note = "This video: " + hdr + "   |   Screen HDR: " +
                (if (ColorGrade.hdrSupported(this)) "supported" else "not supported") +
                "\nGrading runs on a TextureView (SDR). Turn it OFF to use the native surface (HDR passthrough where the device allows)."
        ColorGrade.dialog(this, note, {
            attachVideo()
            applyHdrWindow()
        }, {
            ColorGrade.apply(this, layout)
        })
    }

    // ================= zoom =================
    private fun applyZoom() {
        if (zoom <= 1f) {
            tx = 0f
            ty = 0f
        } else {
            val mx = (zoom - 1f) * layout.width / 2f
            val my = (zoom - 1f) * layout.height / 2f
            tx = tx.coerceIn(-mx, mx)
            ty = ty.coerceIn(-my, my)
        }
        layout.scaleX = zoom
        layout.scaleY = zoom
        layout.translationX = tx
        layout.translationY = ty
    }

    private fun resetZoom() {
        zoom = 1f
        tx = 0f
        ty = 0f
        if (::layout.isInitialized) applyZoom()
    }

    // ================= speed / sync / auto-fix =================
    private fun applyRate() {
        val p = Engine.mp ?: return
        try {
            p.setRate(rate)
        } catch (e: Exception) {
        }
        atomic?.setPaused(rate > 2f)
        speedBtn.text = rateLabel(rate)
    }

    private fun setRateTo(r: Float) {
        rate = Math.round(r.coerceIn(0.25f, 4f) * 20f) / 20f
        applyRate()
    }

    private fun openSpeed() {
        ui.removeCallbacks(hideRun)
        SpeedPanel.show(this, rate, Prefs.accent(this), { r -> setRateTo(r) }, { decoderDialog() })
    }

    private fun seekTo(ms: Long) {
        mp.setTime(ms)
    }

    private fun decoderDialog() {
        AlertDialog.Builder(this)
            .setTitle("Media3 hardware decoder")
            .setMessage("Engine: AndroidX Media3 ExoPlayer\nVideo decoder: ${Engine.videoDecoderName}\n\nMedia3 selects a supported Android MediaCodec decoder. VLC HW+ is not a Media3 mode. The video surface remains attached during seeks to reduce avoidable black frames.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun audioSyncDialog() {
        AlertDialog.Builder(this)
            .setTitle("Audio / Video synchronization")
            .setMessage("Media3 synchronizes audio and video using the media timestamps and playback clock. Manual audio offset is not yet implemented in this engine migration.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun seekBy(ms: Long) {
        seekTo(Math.max(0L, mp.getTime() + ms))
        showMsg(if (ms > 0) "+" + (ms / 1000) + "s" else "" + (ms / 1000) + "s")
    }

    private fun togglePlay() {
        Engine.toggle(this)
        showControls()
    }



    private fun startVideoTitleRgbAnimation() {
        titleRgbAnimator?.cancel()
        titleRgbShader = null
        titleTv.paint.shader = null

        titleTv.post {
            if (isFinishing || isDestroyed) return@post

            val width = titleTv.width.toFloat().coerceAtLeast(
                titleTv.paint.measureText(titleTv.text.toString()).coerceAtLeast(1f)
            )
            val rainbow = intArrayOf(
                Color.RED, 0xFFFF9800.toInt(), Color.YELLOW,
                Color.GREEN, Color.CYAN, Color.BLUE,
                0xFFE040FB.toInt(), Color.RED
            )

            val shader = LinearGradient(
                0f, 0f, width, 0f,
                rainbow, null, Shader.TileMode.MIRROR
            )
            titleRgbShader = shader
            titleTv.paint.shader = shader

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val shift = width * (animation.animatedValue as Float)
                    titleRgbMatrix.setTranslate(-shift, 0f)
                    titleRgbShader?.setLocalMatrix(titleRgbMatrix)
                    titleTv.invalidate()
                }
            }
            titleRgbAnimator = animator
            animator.start()
        }
    }

    private fun onItem() {
        if (!Engine.active || Engine.items.isEmpty()) {
            finish()
            return
        }
        val cu = Engine.cur()
        rate = 1f
        titleTv.text = Engine.title
        if (audioMode && ::audioModeTitle.isInitialized) {
            audioModeTitle.text = Engine.title
            updateAudioMode()
        }
        startVideoTitleRgbAnimation()
        abA = -1L
        abB = -1L
        lastDropCountSeen = Engine.droppedFrames
        Prefs.setLastPlayedUri(this, cu.toString())
        if (Prefs.autoOrient(this)) Orient.setFor(this, cu) { videoPortrait = it }
        resetZoom()
        VideoInfo.probeAsync(this, cu) { runOnUiThread { applyHdrWindow() } }
        applyHdrWindow()
        ui.removeCallbacks(rateRun)
        ui.postDelayed(rateRun, 500)
    }

    // ================= tracks / menu =================
    private fun pickTrack(title: String, tracks: Array<MediaPlayer.TrackDescription>?, set: (Int) -> Unit) {
        if (tracks == null || tracks.isEmpty()) {
            Toast.makeText(this, "No tracks available", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { it.name ?: ("Track " + it.id) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i -> set(tracks[i].id) }
            .show()
    }

    private fun showInfo() {
        if (Engine.items.isEmpty()) return
        VideoInfo.show(this, Engine.cur(), Engine.title)
    }

    private fun showMenu() {
        val abLabel = if (abA < 0) "A-B Repeat: set point A"
        else if (abB < 0) "A-B Repeat: set point B"
        else "A-B Repeat: clear"
        val opts = arrayOf(
            "Speed (ruler)  " + rateLabel(rate),
            "Decoder: " + Tuning.decLabel(Tuning.decMode(this)),
            "Smart auto-fix: " + onOff(Tuning.autoFix(this)),
            "Equalizer Presets",
            "Audio Boost (up to 200%)",
            "Sleep Timer",
            "Colour Grading / HDR",
            "Audio Sync (delay)",
            "Video Info",
            "Load External Subtitle",
            "Add Bookmark",
            "View Bookmarks",
            abLabel,
            "Background Audio: " + onOff(Engine.bgAudio),
            "Picture-in-Picture",
            "Convert to Audio (MP3, M4A...)",
            "Margin Animation: " + onOff(Prefs.videoMarginAnim(this)),
            "Auto-rotate to match video: " + onOff(Prefs.autoOrient(this)),
            "Allow Background (Battery)"
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> openSpeed()
                    1 -> decoderDialog()
                    2 -> {
                        Tuning.setAutoFix(this, !Tuning.autoFix(this))
                        showMsg("Smart auto-fix " + onOff(Tuning.autoFix(this)))
                    }
                    3 -> Dialogs.eq(this)
                    4 -> Dialogs.boost(this)
                    5 -> Dialogs.sleep(this)
                    6 -> openGrade()
                    7 -> audioSyncDialog()
                    8 -> showInfo()
                    9 -> pickSub()
                    10 -> addBookmark()
                    11 -> bookmarksDialog()
                    12 -> abStep()
                    13 -> {
                        Engine.bgAudio = !Engine.bgAudio
                        Prefs.setBgAudio(this, Engine.bgAudio)
                        showMsg("Background audio " + onOff(Engine.bgAudio))
                    }
                    14 -> enterPip()
                    15 -> Hooks.convert(this, listOf(Engine.cur()), listOf(Engine.title), null)
                    16 -> toggleMargin()
                    17 -> {
                        Prefs.setAutoOrient(this, !Prefs.autoOrient(this))
                        if (Prefs.autoOrient(this)) Orient.setFor(this, Engine.cur()) { videoPortrait = it }
                        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showMsg("Auto-rotate " + onOff(Prefs.autoOrient(this)))
                    }
                    18 -> Power.request(this)
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
            av.excludeSurfacesIn(layout)
        }
        av?.applyPrefs()
        applyRate()
        showMsg("Margin animation " + onOff(Prefs.videoMarginAnim(this)))
    }

    private fun pickSub() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 741 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            Prefs.setWall(this, uri.toString())
            Toast.makeText(this, "Custom wallpaper saved", Toast.LENGTH_SHORT).show()
            return
        }

        if (requestCode != 7 || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        try {
            val nm = Engine.nameOf(this, u)
            val ext = nm.substringAfterLast('.', "srt")
            val f = File(cacheDir, "sub_" + System.currentTimeMillis() + "." + ext)
            contentResolver.openInputStream(u)?.use { ins ->
                f.outputStream().use { out -> ins.copyTo(out) }
            }
            mp.addSlave(0, Uri.fromFile(f), true)
            showMsg("Subtitle loaded")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load subtitle: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun addBookmark() {
        val key = Engine.cur().toString()
        val t = mp.getTime()
        Prefs.setBms(this, key, (Prefs.bms(this, key) + t).sorted())
        showMsg("Bookmark added at " + fmt(t))
    }

    private fun bookmarksDialog() {
        val key = Engine.cur().toString()
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
            abA = mp.getTime()
            showMsg("Point A set at " + fmt(abA))
        } else if (abB < 0) {
            val t = mp.getTime()
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
        if (Engine.mp != null && Engine.isPlaying() && !locked && !Engine.bgAudio) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        atomic?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) hideControls() else showControls()
    }

    @Deprecated("Deprecated in Java")
    private fun showAudioQueue() {
        if (Engine.items.isEmpty()) return
        val rows = Array(Engine.items.size) { i ->
            val name = Engine.names.getOrNull(i) ?: Engine.items[i].lastPathSegment ?: "Video"
            (if (i == Engine.idx) "\u25B6 " else "") + (i + 1) + ". " + name
        }
        AlertDialog.Builder(this).setTitle("Playback Queue")
            .setItems(rows) { _, which -> Engine.loadAt(this, which) }
            .show()
    }

    private fun showStyleCenter() {
        val choices = arrayOf("Default / Current", "RGB", "Pink", "Dark", "Light",
            "Gradient", "None", "Choose custom wallpaper")
        AlertDialog.Builder(this).setTitle("Style Center")
            .setItems(choices) { _, which ->
                if (which == choices.lastIndex) {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    try { startActivityForResult(pick, 741) }
                    catch (_: Exception) {
                        Toast.makeText(this, "Image picker unavailable", Toast.LENGTH_SHORT).show()
                    }
                } else if (which > 0) {
                    val modes = arrayOf("rgb", "pink", "dark", "light", "gradient", "none")
                    Prefs.setThemeMode(this, modes[which - 1], true)
                    recreateAudioModeStyle()
                }
            }.show()
    }

    private fun recreateAudioModeStyle() {
        audioModePanel.setBackgroundColor(Prefs.bg(this))
        val c = Prefs.accent(this)
        audioModeSeek.progressTintList = ColorStateList.valueOf(c)
        audioModeSeek.thumbTintList = ColorStateList.valueOf(c)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(c)
        audioModePlayBtn.background = bg
    }


    override fun onBackPressed() {
        if (audioMode) {
            returnToVideo()
            return
        }
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
                val cx = layout.width / 2f
                val cy = layout.height / 2f
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
                val sMs = (Prefs.seekSec(this@PlayerActivity) * 1000).toLong()
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
                    if (gMode == 3) seekTarget = mp.getTime()
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
                    val len = Math.max(0L, mp.getLength())
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
        val cont = intent.getBooleanExtra("cont", false) && Engine.active && Engine.isVideo
        var uris: List<Uri> = emptyList()
        var names: List<String> = emptyList()
        var index = 0
        if (!cont) {
            val u0 = intent.data
            if (u0 == null) {
                finish()
                return
            }
            val l = intent.getStringArrayListExtra("list")
            uris = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
            val nm = intent.getStringArrayListExtra("names")
            names = if (nm != null && nm.size == uris.size) nm.toList() else emptyList()
            index = intent.getIntExtra("idx", 0)
        }
        val qUris = uris
        val qNames = names
        val qIndex = index
        val firstUri: Uri? = if (cont) Engine.items.getOrNull(Engine.idx) else qUris.getOrNull(qIndex)

        Engine.ensure(this)
        Engine.bgAudio = Prefs.bgAudio(this)
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

        layout = FrameLayout(this)
        root.addView(layout, FrameLayout.LayoutParams(MP, MP))
        atomic?.excludeSurfacesIn(layout)

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

        // ---- top bar: back | title | info | rotate ----
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
        topBar.addView(ib("\uD83C\uDFA7", 20f) {
            enterAudioMode()
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
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) curTv.text = fmt(p.toLong())
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
            if (Engine.idx > 0) Engine.loadAt(this, Engine.idx - 1)
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
            if (Engine.idx + 1 < Engine.items.size) Engine.loadAt(this, Engine.idx + 1)
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
            scaleIdx = (scaleIdx + 1) % scales.size
            mp.setVideoScale(scales[scaleIdx])
            b.text = scaleNames[scaleIdx]
            resetZoom()
            showControls()
        })
        val repBtn = ib("\uD83D\uDD01", 18f) { b ->
            val v = (Prefs.repeatFor(this, true) + 1) % 3
            Prefs.setRepeatFor(this, true, v)
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
            b.alpha = if (v) 1f else 0.35f
            showMsg(if (v) "Shuffle: On" else "Shuffle: Off")
            showControls()
        }
        shufBtn.alpha = if (Prefs.shuffleFor(this, true)) 1f else 0.35f
        exRow.addView(shufBtn)
        exRow.addView(ib("Audio", 14f) {
            pickTrack("Audio Track", mp.getAudioTracks()) { id -> mp.setAudioTrack(id) }
        })
        exRow.addView(ib("Sub", 14f) {
            pickTrack("Subtitles", mp.getSpuTracks()) { id -> mp.setSpuTrack(id) }
        })
        exRow.addView(ib("\u2699\uFE0F", 18f) { showMenu() }.also { playerSettingsGear = it })
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

        // Audio Mode overlay: same Media3 session, no reload or seek reset.
        audioModePanel = LinearLayout(this)
        audioModePanel.orientation = LinearLayout.VERTICAL
        audioModePanel.gravity = Gravity.CENTER
        audioModePanel.setPadding(dp(28), dp(24), dp(28), dp(24))
        audioModePanel.setBackgroundColor(0xFF10131B.toInt())

        val audioHeader = LinearLayout(this)
        audioHeader.gravity = Gravity.CENTER_VERTICAL
        val backVideo = ib("\u2190  Back to Video", 17f) { returnToVideo() }
        audioHeader.addView(backVideo, LinearLayout.LayoutParams(0, WC, 1f))
        audioModePanel.addView(audioHeader, LinearLayout.LayoutParams(MP, WC))

        val audioIcon = TextView(this)
        audioIcon.text = "\uD83C\uDFA7"
        audioIcon.textSize = 76f
        audioIcon.gravity = Gravity.CENTER
        audioIcon.setTextColor(Color.WHITE)
        val iconLp = LinearLayout.LayoutParams(MP, WC)
        iconLp.setMargins(0, dp(55), 0, dp(28))
        audioModePanel.addView(audioIcon, iconLp)

        audioModeTitle = TextView(this)
        audioModeTitle.textSize = 20f
        audioModeTitle.setTextColor(Color.WHITE)
        audioModeTitle.gravity = Gravity.CENTER
        audioModeTitle.maxLines = 2
        audioModeTitle.ellipsize = TextUtils.TruncateAt.END
        audioModePanel.addView(audioModeTitle, LinearLayout.LayoutParams(MP, WC))

        audioModeSeek = SeekBar(this)
        audioModeSeek.progressTintList = ColorStateList.valueOf(ac)
        audioModeSeek.thumbTintList = ColorStateList.valueOf(ac)
        audioModeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(b: SeekBar?) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(b: SeekBar?) {
                Engine.seekTo((b?.progress ?: 0).toLong())
                userSeeking = false
                updateAudioMode()
            }
        })
        val seekLp = LinearLayout.LayoutParams(MP, WC)
        seekLp.setMargins(0, dp(28), 0, dp(12))
        audioModePanel.addView(audioModeSeek, seekLp)

        val transport = LinearLayout(this)
        transport.gravity = Gravity.CENTER
        transport.addView(ib("\u23EE", 27f) { Engine.prev(this) })
        audioModePlayBtn = ib("\u23F8", 34f) { Engine.toggle(this) }
        val audioPlayLp = LinearLayout.LayoutParams(dp(76), dp(76))
        audioPlayLp.gravity = Gravity.CENTER
        audioPlayLp.setMargins(dp(16), dp(10), dp(16), dp(10))
        val playCircle = GradientDrawable()
        playCircle.shape = GradientDrawable.OVAL
        playCircle.setColor(ac)
        audioModePlayBtn.background = playCircle
        transport.addView(audioModePlayBtn, audioPlayLp)
        transport.addView(ib("\u23ED", 27f) { Engine.next(this) })
        audioModePanel.addView(transport, LinearLayout.LayoutParams(MP, WC))

        val skipRow = LinearLayout(this)
        skipRow.gravity = Gravity.CENTER
        skipRow.addView(ib("-" + Prefs.seekSec(this) + "s", 14f) {
            val now = Engine.mp?.getTime() ?: 0L
            Engine.seekTo((now - Prefs.seekSec(this) * 1000L).coerceAtLeast(0L))
        })
        skipRow.addView(ib("+" + Prefs.seekSec(this) + "s", 14f) {
            val now = Engine.mp?.getTime() ?: 0L
            Engine.seekTo(now + Prefs.seekSec(this) * 1000L)
        })
        audioModePanel.addView(skipRow, LinearLayout.LayoutParams(MP, WC))

        val actionRow = LinearLayout(this)
        actionRow.gravity = Gravity.CENTER
        actionRow.addView(ib("EQ", 13f) { Dialogs.eq(this) })
        actionRow.addView(ib("Boost", 13f) { Dialogs.boost(this) })
        actionRow.addView(ib("Sleep", 13f) { Dialogs.sleep(this) })
        actionRow.addView(ib("Queue", 13f) { showAudioQueue() })
        audioModePanel.addView(actionRow, LinearLayout.LayoutParams(MP, WC))

        val featureRow = LinearLayout(this)
        featureRow.gravity = Gravity.CENTER
        featureRow.addView(ib("Convert to Audio", 13f) {
            Hooks.convert(this, listOf(Engine.cur()), listOf(Engine.title), null)
        })
        featureRow.addView(ib("Style Center", 13f) { showStyleCenter() })
        audioModePanel.addView(featureRow, LinearLayout.LayoutParams(MP, WC))

        val settingsGear = ib("\u2699", 24f) { showStyleCenter() }
        audioModePanel.addView(settingsGear, LinearLayout.LayoutParams(WC, WC).apply {
            gravity = Gravity.END
        })
        settingsGear.setOnClickListener {
            audioSettingsAnimator?.cancel()
            audioSettingsAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 650
                interpolator = LinearInterpolator()
                addUpdateListener { settingsGear.rotation = it.animatedValue as Float }
                start()
            }
            showStyleCenter()
        }

        audioModePanel.visibility = View.GONE
        root.addView(audioModePanel, FrameLayout.LayoutParams(MP, MP))

        setContentView(root)
        padForCutout()

        attachVideo()
        Engine.listener = lis
        setupTouch()

        if (!cont) layout.post { Engine.setQueue(this, qUris, qNames, qIndex, true) } else onItem()
        showControls()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        audioChildOpen = false
        Engine.listener = lis
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this) else PlaybackService.stop(this)
        ui.removeCallbacks(controlRgbTick)
        if (controlRgbTargets.isNotEmpty()) ui.post(controlRgbTick)
        playerGearAnimator?.cancel()
        playerSettingsGear?.let { gear ->
            if (Prefs.themeMode(this) == "rgb") {
                playerGearAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 5000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { gear.rotation = it.animatedValue as Float }
                    start()
                }
            }
        }
        hideBars()
    }

    override fun onPause() {
        playerGearAnimator?.cancel()
        playerGearAnimator = null
        ui.removeCallbacks(controlRgbTick)
        super.onPause()
        Engine.saveProgress()
        if (Engine.bgAudio && Engine.isPlaying()) PlaybackService.start(this)
    }

    override fun onStop() {
        super.onStop()
        if (Engine.isPlaying() && !Engine.bgAudio && !audioChildOpen) {
            Engine.pause()
        }
    }

    override fun onDestroy() {
        titleRgbAnimator?.cancel()
        titleRgbAnimator = null
        titleRgbShader = null
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
        if (::layout.isInitialized && current === layout) {
            try {
                Engine.mp?.detachViews()
            } catch (e: Exception) {
            }
            current = null
        }
        ColorGrade.hdrWindow(this, false)
        if (!Engine.bgAudio && Engine.listener == null) {
            Engine.stopAll(this)
        } else if (Engine.bgAudio && Engine.active && Engine.isPlaying()) {
            PlaybackService.start(this)
        }
    }
}
