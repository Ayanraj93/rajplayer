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
import android.widget.ImageView
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
    private lateinit var lockBtn: ImageView
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
    private var audioModeSpeedBtn: TextView? = null
    private val audioModeSpeeds = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
    private var audioModeSubTv: TextView? = null
    private var audioModeCurTv: TextView? = null
    private var audioModeDurTv: TextView? = null
    private var audioModeShufBtn: TextView? = null
    private var audioModeRepBtn: TextView? = null
    private var audioModeArtBox: FrameLayout? = null
    private var audioModeArtImg: android.widget.ImageView? = null
    private var audioModeArtNote: TextView? = null
    private var audioModeArtKey: String = ""
    private var audioModeArtCacheKey: String = ""
    private var audioModeArtCacheBmp: android.graphics.Bitmap? = null
    private var audioMode = false
    private var savedOrientationBeforeAudio: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var audioChildOpen = false
    private var systemBarsVisible = false
    private var controlAnimsRunning = false
    private var keepControlsHiddenOnPause = false
    private var resumeBarRef: LinearLayout? = null
    private fun updateResumeBarPosition() {
        val bar = resumeBarRef ?: return
        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        bar.layoutParams = if (isLand) {
            FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(44)
                bottomMargin = dp(100)
            }
        } else {
            FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(86)
            }
        }
    }
    private var hideResumeBannerRef: Runnable? = null
    private var lastPlayingState: Boolean? = null
    private var audioSettingsAnimator: ValueAnimator? = null
    private var playerGearAnimator: ValueAnimator? = null
    private var playerSettingsGear: View? = null
    private var audioSettingsGear: TextView? = null
    private var playerGearMotion: GearMotion? = null
    private var audioGearMotion: GearMotion? = null
    private var headphoneButton: ImageView? = null
    private var headphoneRgbAnimator: ValueAnimator? = null
    private var headphoneBlinkAnimator: ValueAnimator? = null
    private var infoGlowAnimator: ValueAnimator? = null
    private var rotateGlowAnimator: ValueAnimator? = null
    private var lockRgbAnimator: ValueAnimator? = null
    private var lockBlinkAnimator: ValueAnimator? = null
    private var infoButtonView: TextView? = null
    private var rotateButtonView: ImageView? = null
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

    // speed: 0.25x .. 5x (ruler) + smart auto-fix
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
                if (lastPlayingState != p.isPlaying) {
                    lastPlayingState = p.isPlaying
                    applyPlaybackUi(p.isPlaying)
                }
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
                if (Tuning.autoFix(this@PlayerActivity)) {
                    lastDropCountSeen = dropsNow
                }
            }
            if (audioMode) updateAudioMode()
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
        controlRgbTargets.add { c -> t.setTextColor(c) }
        return t
    }

    private fun startHeadphoneAnimations() {
        val icon = headphoneButton ?: return
        headphoneRgbAnimator?.cancel()
        headphoneBlinkAnimator?.cancel()

        headphoneRgbAnimator = ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE,
            Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                icon.setColorFilter(it.animatedValue as Int)
            }
            start()
        }

        headphoneBlinkAnimator = ValueAnimator.ofFloat(0.82f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                icon.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopHeadphoneAnimations() {
        headphoneRgbAnimator?.cancel()
        headphoneRgbAnimator = null
        headphoneBlinkAnimator?.cancel()
        headphoneBlinkAnimator = null
        headphoneButton?.apply { alpha = 1f; clearColorFilter() }
    }

    private fun makeSoftIconGlow(view: TextView): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val f = it.animatedValue as Float
                val color = android.animation.ArgbEvaluator().evaluate(
                    f, Color.rgb(120, 220, 255), Color.WHITE
                ) as Int
                view.setShadowLayer(
                    dp(3).toFloat() + dp(5) * f,
                    0f, 0f, color
                )
                view.alpha = 0.88f + 0.12f * f
            }
        }
    }

    private fun makeInfoRgbAnimation(view: TextView): ValueAnimator {
        return ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE,
            Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val color = it.animatedValue as Int
                view.setTextColor(color)
                view.setShadowLayer(dp(5).toFloat(), 0f, 0f, color)
                view.alpha = 0.82f + 0.18f *
                    ((kotlin.math.sin(it.animatedFraction * Math.PI * 2) + 1.0) / 2.0).toFloat()
            }
        }
    }

    private fun startTopIconGlow() {
        stopTopIconGlow()
        infoButtonView?.let {
            infoGlowAnimator = makeInfoRgbAnimation(it).also { a -> a.start() }
        }
        rotateButtonView?.let { view ->
            view.rotation = 0f
            view.alpha = 1f
            rotateGlowAnimator = ValueAnimator.ofArgb(
                Color.RED, Color.MAGENTA, Color.BLUE,
                Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
            ).apply {
                duration = 4200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { view.setColorFilter(it.animatedValue as Int) }
                start()
            }
        }
        if (::lockBtn.isInitialized) {
            lockRgbAnimator = ValueAnimator.ofArgb(
                Color.RED, Color.MAGENTA, Color.BLUE,
                Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
            ).apply {
                duration = 4200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { lockBtn.setColorFilter(it.animatedValue as Int) }
                start()
            }
            lockBlinkAnimator = ValueAnimator.ofFloat(0.35f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { lockBtn.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun stopTopIconGlow() {
        infoGlowAnimator?.cancel()
        infoGlowAnimator = null
        rotateGlowAnimator?.cancel()
        rotateGlowAnimator = null
        lockRgbAnimator?.cancel()
        lockRgbAnimator = null
        lockBlinkAnimator?.cancel()
        lockBlinkAnimator = null
        infoButtonView?.apply {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            alpha = 1f
        }
        rotateButtonView?.apply {
            clearColorFilter()
            rotation = 0f
            alpha = 1f
        }
        if (::lockBtn.isInitialized) {
            lockBtn.clearColorFilter()
            lockBtn.alpha = 1f
        }
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        systemBarsVisible = false
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (systemBarsVisible) showBars() else hideBars()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (audioMode) {
            if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            audioModePanel.post { updateAudioModeArtSize() }
        }
        updateResumeBarPosition()
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
        systemBarsVisible = true
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun startControlAnimations() {
        if (controlAnimsRunning) return
        controlAnimsRunning = true
        ui.removeCallbacks(controlRgbTick)
        if (controlRgbTargets.isNotEmpty()) ui.post(controlRgbTick)
        playerGearMotion?.stop()
        audioGearMotion?.stop()
        playerGearMotion = playerSettingsGear?.let { GearMotion(it).also { motion -> motion.start() } }
        audioGearMotion = audioSettingsGear?.let { GearMotion(it).also { motion -> motion.start() } }
        startHeadphoneAnimations()
        startTopIconGlow()
    }

    private fun stopControlAnimations() {
        controlAnimsRunning = false
        stopTopIconGlow()
        stopHeadphoneAnimations()
        playerGearMotion?.stop()
        playerGearMotion = null
        audioGearMotion?.stop()
        audioGearMotion = null
        ui.removeCallbacks(controlRgbTick)
    }

    private fun showPausedControls() {
        hideBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        startControlAnimations()
        PerfBoost.setControlsVisible(this, true)
        ui.removeCallbacks(hideRun)
    }

    private fun showControls() {
        if (!Engine.isPlaying()) {
            showPausedControls()
            return
        }
        hideBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        startControlAnimations()
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 5000L)
    }

    private fun hideControls() {
        hideBars()
        topBar.visibility = View.GONE
        controls.visibility = View.GONE
        lockBtn.visibility = View.GONE
        stopControlAnimations()
        PerfBoost.setControlsVisible(this, false)
        ui.removeCallbacks(hideRun)
    }

    private fun applyPlaybackUi(playing: Boolean) {
        if (playing) {
            keepControlsHiddenOnPause = false
            hideControls()
        } else if (keepControlsHiddenOnPause) {
            hideControls()
        } else {
            showPausedControls()
        }
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
    override fun onRestart() {
        super.onRestart()
        if (!audioMode && Engine.active) {
            Bg.restoreVideo()
            Engine.mp?.resumeSurface()
        }
    }

    private fun attachVideo() {
        mp.attachViews(layout, null, true, ColorGrade.on(this))
        atomic?.excludeSurfacesIn(layout)
        current = layout
        layout.post { ColorGrade.apply(this, layout) }
    }

    private var lastModeSwitchAt = 0L

    private fun enterAudioMode() {
        if (audioMode || !Engine.active) return
        savedOrientationBeforeAudio = requestedOrientation
        audioMode = true
        audioChildOpen = false
        Engine.modeSwitching = false
        AudioActivity.audioModeSession = true
        Prefs.setAudioModeSession(this, true)
        ui.removeCallbacks(hideRun)
        resumeBarRef?.visibility = View.GONE
        atomic?.setPaused(true)
        syncSpeedFromEngine()
        updateAudioMode()
        audioModePanel.visibility = View.VISIBLE
        audioModePanel.bringToFront()
        if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        audioModePanel.post { updateAudioModeArtSize() }
        if (Engine.isPlaying()) PlaybackService.start(this)
    }

    private fun returnToVideo() {
        if (!audioMode) return
        audioMode = false
        audioChildOpen = false
        Engine.modeSwitching = false
        AudioActivity.audioModeSession = false
        Prefs.setAudioModeSession(this, false)
        Bg.restoreVideo()
        Engine.mp?.resumeSurface()
        audioModePanel.visibility = View.GONE
        layout.visibility = View.VISIBLE
        touchView.visibility = View.VISIBLE
        atomic?.visibility = View.VISIBLE
        atomic?.setPaused(rate > 2f)
        val targetOri = savedOrientationBeforeAudio
        savedOrientationBeforeAudio = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (targetOri != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = targetOri
        } else if (Engine.items.isNotEmpty() && Prefs.autoOrient(this)) {
            Orient.setFor(this, Engine.cur()) { videoPortrait = it }
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        syncSpeedFromEngine()
        showControls()
        hideBars()
    }

    private fun refreshAudioModeButtons() {
        audioModeShufBtn?.alpha = if (Prefs.shuffle(this)) 1f else 0.35f
        val r = Prefs.repeat(this)
        audioModeRepBtn?.text = if (r == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
        audioModeRepBtn?.alpha = if (r == 0) 0.35f else 1f
    }

    private fun updateAudioModeArtSize() {
        val box = audioModeArtBox ?: return
        val dm = resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        val sz = if (portrait) {
            Math.min(dm.widthPixels - dp(64), dp(340))
        } else {
            Math.min(dm.heightPixels / 2, dp(180))
        }
        val lp = box.layoutParams
        if (lp == null || lp.width != sz || lp.height != sz) {
            val nlp = LinearLayout.LayoutParams(sz, sz)
            nlp.setMargins(0, dp(if (portrait) 18 else 8), 0, dp(if (portrait) 18 else 8))
            box.layoutParams = nlp
        }
        audioModeArtNote?.textSize = if (portrait) 84f else 48f
    }

    private fun loadAudioModeArt() {
        val img = audioModeArtImg ?: return
        val note = audioModeArtNote ?: return
        val u = Engine.items.getOrNull(Engine.idx) ?: return
        val key = u.toString()
        if (key == audioModeArtKey) return
        audioModeArtKey = key
        if (audioModeArtCacheKey == key) {
            val cached = audioModeArtCacheBmp
            if (cached != null && !cached.isRecycled) {
                img.setImageBitmap(cached)
                note.visibility = View.GONE
                return
            }
        }
        img.setImageBitmap(null)
        note.visibility = View.VISIBLE
        Thread {
            var bmp: android.graphics.Bitmap? = null
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    bmp = contentResolver.loadThumbnail(u, android.util.Size(360, 360), null)
                }
            } catch (_: Exception) { }
            if (bmp == null) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(this, u)
                    val pic = mmr.embeddedPicture
                    if (pic != null) {
                        bmp = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size)
                    } else {
                        bmp = mmr.getFrameAtTime(1_000_000L)
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Exception) { }
                }
            }
            if (bmp != null) {
                audioModeArtCacheKey = key
                audioModeArtCacheBmp = bmp
                val ready = bmp
                runOnUiThread {
                    if (audioModeArtKey == key) {
                        img.setImageBitmap(ready)
                        note.visibility = View.GONE
                    }
                }
            }
        }.start()
    }

    private fun showAudioModeMenu() {
        val opts = arrayOf("Playback Speed", "Equalizer", "Audio Boost", "Sleep Timer", "Queue", "Convert to Audio", "Style Center")
        AlertDialog.Builder(this)
            .setTitle("Audio Options")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> openSpeed()
                    1 -> Dialogs.eq(this)
                    2 -> Dialogs.boost(this)
                    3 -> Dialogs.sleep(this)
                    4 -> showAudioQueue()
                    5 -> Hooks.convert(this, listOf(Engine.cur()), listOf(Engine.title), null)
                    6 -> showStyleCenter()
                }
            }
            .show()
    }

    private fun updateAudioMode() {
        val p = Engine.mp ?: return
        updateAudioModeArtSize()
        audioModeTitle.text = Engine.title
        audioModeSubTv?.text = (Engine.idx + 1).toString() + " / " + Engine.items.size
        val len = p.getLength()
        val pos = p.getTime()
        if (!userSeeking && len > 0) {
            audioModeSeek.max = len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            audioModeSeek.progress = pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            audioModeCurTv?.text = fmt(pos)
        }
        audioModeDurTv?.text = fmt(len)
        audioModePlayBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
        audioModeSpeedBtn?.text = rateLabel(rate)
        refreshAudioModeButtons()
        loadAudioModeArt()
    }

    private fun applyHdrWindow() {
        if (Engine.items.isEmpty()) return
        val inf = VideoInfo.cached(Engine.cur())
        val isHdrVideo = inf != null && inf.hdr != "SDR"
        val enableHdr = (isHdrVideo && !ColorGrade.on(this)) || ColorGrade.hdrPeak(this)
        ColorGrade.hdrWindow(this, enableHdr)
    }

    private fun openGrade() {
        val inf = if (Engine.items.isEmpty()) null else VideoInfo.cached(Engine.cur())
        val hdr = inf?.hdr ?: "SDR"
        val note = "Video: " + hdr + "   |   Display HDR: " +
                (if (ColorGrade.hdrSupported(this)) "Supported" else "Standard") +
                "\nChoose Natural/Vivid/Ultra HDR+ presets or keep Engine OFF for Native HDR Passthrough."
        ColorGrade.dialog(this, note, {
            attachVideo()
            applyHdrWindow()
        }, {
            ColorGrade.apply(this, layout)
            applyHdrWindow()
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
        audioModeSpeedBtn?.text = rateLabel(rate)
    }

    private fun syncSpeedFromEngine() {
        val cur = Engine.mp?.getRate() ?: return
        rate = Math.round(cur.coerceIn(0.25f, 5f) * 20f) / 20f
        atomic?.setPaused(rate > 2f)
        if (::speedBtn.isInitialized) speedBtn.text = rateLabel(rate)
        audioModeSpeedBtn?.text = rateLabel(rate)
    }

    private fun setRateTo(r: Float) {
        rate = Math.round(r.coerceIn(0.25f, 5f) * 20f) / 20f
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

    private fun togglePlay(fromDoubleTap: Boolean = false) {
        val wasHidden = !controlsVisible()
        keepControlsHiddenOnPause = fromDoubleTap && wasHidden
        Engine.toggle(this)
        ui.post {
            val playing = Engine.isPlaying()
            lastPlayingState = playing
            if (fromDoubleTap && wasHidden) {
                showMsg(if (playing) "Playing" else "Paused")
                hideControls()
            } else {
                applyPlaybackUi(playing)
            }
        }
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
        if (!audioMode && Prefs.autoOrient(this)) Orient.setFor(this, cu) { videoPortrait = it }
        resetZoom()
        resumeBarRef?.let { bar ->
            hideResumeBannerRef?.let { ui.removeCallbacks(it) }
            val savedPos = Prefs.pos(this, cu.toString())
            if (savedPos > 5000L) {
                updateResumeBarPosition()
                bar.alpha = 1f
                bar.visibility = View.VISIBLE
                bar.bringToFront()
                hideResumeBannerRef?.let { ui.postDelayed(it, 8000L) }
            } else {
                bar.visibility = View.GONE
            }
        }
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
            "Smart auto-fix: " + onOff(Tuning.autoFix(this)),
            "Equalizer Presets",
            "Audio Boost (up to 200%)",
            "Sleep Timer",
            "HDR & Colour Grading",
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
        val dlg = AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(opts) { _, i ->
                when (if (i >= 1) i + 1 else i) {
                    0 -> openSpeed()
                    1 -> decoderDialog()
                    2 -> {
                        Tuning.setAutoFix(this@PlayerActivity, !Tuning.autoFix(this@PlayerActivity))
                    }
                    3 -> Dialogs.eq(this@PlayerActivity)
                    4 -> Dialogs.boost(this@PlayerActivity)
                    5 -> Dialogs.sleep(this@PlayerActivity)
                    6 -> openGrade()
                    7 -> audioSyncDialog()
                    8 -> showInfo()
                    9 -> pickSub()
                    10 -> addBookmark()
                    11 -> bookmarksDialog()
                    12 -> abStep()
                    13 -> {
                        Engine.bgAudio = !Engine.bgAudio
                        Prefs.setBgAudio(this@PlayerActivity, Engine.bgAudio)
                        showMsg("Background audio " + onOff(Engine.bgAudio))
                    }
                    14 -> enterPip()
                    15 -> Hooks.convert(this@PlayerActivity, listOf(Engine.cur()), listOf(Engine.title), null)
                    16 -> toggleMargin()
                    17 -> {
                        Prefs.setAutoOrient(this@PlayerActivity, !Prefs.autoOrient(this@PlayerActivity))
                        if (Prefs.autoOrient(this@PlayerActivity)) Orient.setFor(this@PlayerActivity, Engine.cur()) { videoPortrait = it }
                        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showMsg("Auto-rotate " + onOff(Prefs.autoOrient(this@PlayerActivity)))
                    }
                    18 -> Power.request(this@PlayerActivity)
                    else -> {}
                }
            }
            .show()
        val cardBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF50D131F.toInt(), 0xF7141D2E.toInt())
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(2), 0x8800E5FF.toInt())
        }
        dlg.window?.apply {
            setBackgroundDrawable(cardBg)
            setDimAmount(0.65f)
        }
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) {
            dlg.findViewById<TextView>(titleId)?.apply {
                setTextColor(0xFF00F5D4.toInt())
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
        val titleDividerId = resources.getIdentifier("titleDividerNoCustom", "id", "android")
        if (titleDividerId != 0) {
            dlg.findViewById<View>(titleDividerId)?.setBackgroundColor(0x2600E5FF)
        }
        dlg.listView?.apply {
            divider = android.graphics.drawable.ColorDrawable(0x18FFFFFF)
            dividerHeight = dp(1)
            setPadding(dp(6), dp(4), dp(6), dp(12))
            clipToPadding = false
            setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) {
                    (child as? TextView)?.apply {
                        textSize = 15.5f
                        setPadding(dp(20), dp(13), dp(20), dp(13))
                        val raw = text?.toString() ?: ""
                        val splitIdx = when {
                            raw.contains(": ") -> raw.indexOf(": ") + 2
                            raw.contains("  ") -> raw.indexOf("  ") + 2
                            else -> -1
                        }
                        if (splitIdx in 1 until raw.length) {
                            val sp = android.text.SpannableString(raw)
                            sp.setSpan(
                                android.text.style.ForegroundColorSpan(0xFFF1F5F9.toInt()),
                                0,
                                splitIdx,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            val valColor = if (raw.endsWith("OFF")) 0xFFFF6B9D.toInt() else 0xFF00F5D4.toInt()
                            sp.setSpan(
                                android.text.style.ForegroundColorSpan(valColor),
                                splitIdx,
                                raw.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            sp.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                splitIdx,
                                raw.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = sp
                        } else {
                            setTextColor(0xFFF1F5F9.toInt())
                        }
                    }
                }
                override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
            })
        }
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
        audioModePanel.setBackgroundColor(Prefs.bg(this) or 0xFF000000.toInt())
        val c = Prefs.accent(this)
        audioModeSeek.progressTintList = ColorStateList.valueOf(c)
        audioModeSeek.thumbTintList = ColorStateList.valueOf(c)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(c)
        audioModePlayBtn.background = bg
    }


    private fun stopVideoImmediatelyIfNeeded() {
        if (!Prefs.videoBgPlay(this) && !audioMode && !audioChildOpen) {
            if (Engine.isPlaying()) Engine.pause()
            PlaybackService.stop(this)
        }
    }

    override fun onBackPressed() {
        if (audioMode) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            return
        }
        if (locked) {
            showMsg("Screen is locked. Long-press the lock icon to unlock.")
            return
        }
        stopVideoImmediatelyIfNeeded()
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
                if (controlsVisible()) {
                    hideControls()
                } else {
                    keepControlsHiddenOnPause = false
                    showControls()
                }
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
                val sMs = (Prefs.seekSec(this@PlayerActivity) * 1000L) * 3L
                if (e.x < w / 3f) {
                    seekBy(-sMs)
                } else if (e.x > w * 2f / 3f) {
                    seekBy(sMs)
                } else {
                    togglePlay(fromDoubleTap = true)
                }
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
            PerfBoost.onUserTouch(this)
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

    private fun showGestureGuideIfNeeded() {
        val prefs = getSharedPreferences("rajplayer_gesture_guide", MODE_PRIVATE)
        if (prefs.getBoolean("shown", false)) return
        val message = """
            VIDEO PLAYER GESTURES

            • Single tap: controls + Android bars show
            • Next single tap: only Android bars hide
            • Left double tap: seek backward 3 × interval
            • Right double tap: seek forward 3 × interval
            • Centre double tap: show / hide all controls
            • Audio Player / Audio Mode double tap: Android bars show / hide

            Main screen par RajPlayer title double tap: multiple video conversion selection.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("RajPlayer Gesture Guide")
            .setMessage(message)
            .setPositiveButton("Got it") { _, _ ->
                prefs.edit().putBoolean("shown", true).apply()
            }
            .setNegativeButton("Skip") { _, _ ->
                prefs.edit().putBoolean("shown", true).apply()
            }
            .setOnCancelListener {
                prefs.edit().putBoolean("shown", true).apply()
            }
            .show()
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
        if (intent.getBooleanExtra("returnToVideo", false)) {
            Prefs.setAudioModeSession(this, false)
            AudioActivity.audioModeSession = false
        }
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
        topBar.setBackgroundColor(Color.TRANSPARENT)
        topBar.setPadding(dp(20), dp(8), dp(20), dp(8))
        topBar.addView(ib("\u2190", 22f) {
            stopVideoImmediatelyIfNeeded()
            finish()
        })
        titleTv = TextView(this)
        titleTv.setTextColor(txtCol)
        titleTv.textSize = 15f
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))
        val infoButton = ib("ⓘ", 23f) {
            showInfo()
            showControls()
        }
        infoButton.setOnClickListener {
            infoButton.animate().cancel()
            infoButton.rotation = 0f
            infoButton.animate().rotationBy(360f).setDuration(650L).start()
            showInfo()
            showControls()
        }
        infoButton.setOnLongClickListener {
            decoderDialog()
            true
        }
        infoButtonView = infoButton
        topBar.addView(infoButton)

        val rotateButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_rotate_screen_vector)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(10), dp(11), dp(10))
            minimumWidth = dp(46)
            minimumHeight = dp(46)
            contentDescription = "Rotate screen"
            setOnClickListener {
                requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                showControls()
            }
        }
        rotateButtonView = rotateButton
        topBar.addView(rotateButton)
        val headphone = ImageView(this).apply {
            setImageResource(R.drawable.ic_headphones_outline)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            contentDescription = "Background audio"
            setOnClickListener { enterAudioMode() }
        }
        headphone.minimumWidth = dp(48)
        headphone.minimumHeight = dp(48)
        headphone.setOnLongClickListener {
            Engine.bgAudio = !Engine.bgAudio
            Prefs.setBgAudio(this, Engine.bgAudio)
            if (Engine.bgAudio && Engine.isPlaying()) {
                PlaybackService.start(this)
            } else if (!Engine.bgAudio) {
                PlaybackService.stop(this)
            }
            showMsg("Background audio " + onOff(Engine.bgAudio))
            true
        }
        headphoneButton = headphone
        topBar.addView(headphone)
        root.addView(topBar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        // ---- bottom controls ----
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(Color.TRANSPARENT)
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
        scaleIdx = Prefs.videoScale(this).coerceIn(0, scales.lastIndex)
        mp.setVideoScale(scales[scaleIdx])
        exRow.addView(ib(scaleNames[scaleIdx], 14f) { b ->
            scaleIdx = (scaleIdx + 1) % scales.size
            Prefs.setVideoScale(this, scaleIdx)
            mp.setVideoScale(scales[scaleIdx])
            b.text = scaleNames[scaleIdx]
            showMsg("Screen: " + scaleNames[scaleIdx])
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
        val playerGear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_wheel_vector)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = "Player settings"
            setOnClickListener {
                playerGearMotion?.tap()
                showMenu()
            }
        }
        playerSettingsGear = playerGear
        exRow.addView(playerGear)
        controls.addView(exRow)
        root.addView(controls, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        lockBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock_open_vector)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumWidth = dp(46)
            minimumHeight = dp(46)
            contentDescription = "Lock screen"
            setOnClickListener {
                if (locked) {
                    showMsg("Long-press to unlock")
                } else {
                    locked = true
                    setImageResource(R.drawable.ic_lock_closed_vector)
                    topBar.visibility = View.GONE
                    controls.visibility = View.GONE
                    showMsg("Screen locked")
                }
                showControls()
            }
            setOnLongClickListener {
                if (locked) {
                    locked = false
                    setImageResource(R.drawable.ic_lock_open_vector)
                    showMsg("Screen unlocked")
                    showControls()
                }
                true
            }
        }
        val llp = FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.CENTER_VERTICAL)
        llp.setMargins(dp(20), 0, 0, 0)
        root.addView(lockBtn, llp)

        // Audio Mode overlay: 0ms in-place switch with exact AudioActivity layout.
        val aBg = Prefs.bg(this) or 0xFF000000.toInt()
        val aTc = Prefs.text(this)
        val aIc = Prefs.iconColor(this)
        val aAc = Prefs.accent(this)
        fun aib(sym: String, size: Float, color: Int, f: (TextView) -> Unit): TextView {
            val t = TextView(this)
            t.text = sym
            t.textSize = size
            t.setTextColor(color)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            t.setOnClickListener { f(t) }
            return t
        }

        audioModePanel = LinearLayout(this)
        audioModePanel.orientation = LinearLayout.VERTICAL
        audioModePanel.gravity = Gravity.CENTER_HORIZONTAL
        audioModePanel.setPadding(dp(20), dp(24), dp(20), dp(14))
        audioModePanel.setBackgroundColor(aBg)
        audioModePanel.isClickable = true
        audioModePanel.isFocusable = true
        audioModePanel.elevation = dp(24).toFloat()

        val audioHdr = TextView(this)
        audioHdr.text = "BACKGROUND PLAY"
        audioHdr.textSize = 13f
        audioHdr.setTypeface(null, android.graphics.Typeface.BOLD)
        audioHdr.setTextColor(aTc)
        audioHdr.alpha = 1f
        audioHdr.setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        audioHdr.gravity = Gravity.CENTER
        audioHdr.setSingleLine(true)
        val hdrLp = LinearLayout.LayoutParams(MP, WC)
        hdrLp.bottomMargin = dp(4)
        audioModePanel.addView(audioHdr, hdrLp)

        val audioTop = LinearLayout(this)
        audioTop.gravity = Gravity.CENTER_VERTICAL
        audioTop.setPadding(dp(4), dp(2), dp(4), dp(2))
        audioTop.addView(aib("\u2190", 24f, aIc) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        })
        audioTop.addView(aib("Back to Video", 15f, aIc) { returnToVideo() })
        audioTop.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val settingsGear = aib("\u2699", 22f, aIc) {
            audioGearMotion?.tap()
            showStyleCenter()
        }
        audioSettingsGear = settingsGear
        audioTop.addView(settingsGear)
        audioTop.addView(aib("\u22EF", 24f, aIc) { showAudioModeMenu() })
        audioTop.addView(aib("\u2715", 20f, aIc) {
            Prefs.setAudioModeSession(this, false)
            AudioActivity.audioModeSession = false
            audioMode = false
            Engine.stopAll(this)
            finish()
        })
        audioModePanel.addView(audioTop, LinearLayout.LayoutParams(MP, WC))

        val audioScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val audioCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }

        // ---- album art (exact AudioActivity rounded card) ----
        val artSize = Math.min(resources.displayMetrics.widthPixels - dp(64), dp(340))
        val artBox = FrameLayout(this)
        val artGrad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(aAc, 0xFF000000.toInt()))
        artBox.background = artGrad
        artBox.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
            }
        }
        artBox.clipToOutline = true
        val artNote = TextView(this)
        artNote.text = "\uD83C\uDFB5"
        artNote.textSize = 84f
        artNote.gravity = Gravity.CENTER
        artBox.addView(artNote, FrameLayout.LayoutParams(MP, MP))
        val artImg = android.widget.ImageView(this)
        artImg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        artBox.addView(artImg, FrameLayout.LayoutParams(MP, MP))
        audioModeArtBox = artBox
        audioModeArtNote = artNote
        audioModeArtImg = artImg
        val alp = LinearLayout.LayoutParams(artSize, artSize)
        alp.setMargins(0, dp(18), 0, dp(18))
        audioCol.addView(artBox, alp)

        // ---- title & subtitle ----
        audioModeTitle = TextView(this)
        audioModeTitle.textSize = 20f
        audioModeTitle.setTextColor(aTc)
        audioModeTitle.gravity = Gravity.CENTER
        audioModeTitle.maxLines = 2
        audioModeTitle.ellipsize = TextUtils.TruncateAt.END
        audioModeTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        audioCol.addView(audioModeTitle, LinearLayout.LayoutParams(MP, WC))

        val subTv = TextView(this)
        subTv.textSize = 13f
        subTv.setTextColor((aTc and 0x00FFFFFF) or 0x99000000.toInt())
        subTv.gravity = Gravity.CENTER
        subTv.setPadding(0, dp(4), 0, dp(12))
        audioModeSubTv = subTv
        audioCol.addView(subTv, LinearLayout.LayoutParams(MP, WC))

        // ---- seek row (curTv + SeekBar + durTv) ----
        val aSeekRow = LinearLayout(this)
        aSeekRow.gravity = Gravity.CENTER_VERTICAL
        val aCurTv = TextView(this)
        aCurTv.setTextColor(aTc)
        aCurTv.textSize = 12f
        aCurTv.text = "0:00"
        val aDurTv = TextView(this)
        aDurTv.setTextColor(aTc)
        aDurTv.textSize = 12f
        aDurTv.text = "0:00"
        audioModeCurTv = aCurTv
        audioModeDurTv = aDurTv
        audioModeSeek = SeekBar(this)
        audioModeSeek.progressTintList = ColorStateList.valueOf(aAc)
        audioModeSeek.thumbTintList = ColorStateList.valueOf(aAc)
        audioModeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) aCurTv.text = fmt(progress.toLong())
            }
            override fun onStartTrackingTouch(b: SeekBar?) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(b: SeekBar?) {
                Engine.seekTo((b?.progress ?: 0).toLong())
                userSeeking = false
                updateAudioMode()
            }
        })
        aSeekRow.addView(aCurTv)
        aSeekRow.addView(audioModeSeek, LinearLayout.LayoutParams(0, WC, 1f))
        aSeekRow.addView(aDurTv)
        audioCol.addView(aSeekRow, LinearLayout.LayoutParams(MP, WC))

        // ---- transport row (Shuffle + Prev + Play Circle + Next + Repeat) ----
        val transport = LinearLayout(this)
        transport.gravity = Gravity.CENTER
        val aShuf = aib("\uD83D\uDD00", 22f, aIc) {
            Prefs.setShuffle(this, !Prefs.shuffle(this))
            refreshAudioModeButtons()
        }
        val aRep = aib("\uD83D\uDD01", 22f, aIc) {
            Prefs.setRepeat(this, (Prefs.repeat(this) + 1) % 3)
            refreshAudioModeButtons()
        }
        audioModeShufBtn = aShuf
        audioModeRepBtn = aRep
        transport.addView(aShuf)
        transport.addView(aib("\u23EE", 30f, aIc) { Engine.prev(this) })
        audioModePlayBtn = aib("\u23F8", 30f, contrast(aAc)) { Engine.toggle(this) }
        val playCircle = GradientDrawable()
        playCircle.shape = GradientDrawable.OVAL
        playCircle.setColor(aAc)
        audioModePlayBtn.background = playCircle
        val audioPlayLp = LinearLayout.LayoutParams(dp(72), dp(72))
        audioPlayLp.setMargins(dp(12), 0, dp(12), 0)
        transport.addView(audioModePlayBtn, audioPlayLp)
        transport.addView(aib("\u23ED", 30f, aIc) { Engine.next(this) })
        transport.addView(aRep)
        audioCol.addView(transport, LinearLayout.LayoutParams(MP, WC))

        // ---- seek step row (-10s / +10s) ----
        val skipRow = LinearLayout(this)
        skipRow.gravity = Gravity.CENTER
        skipRow.setPadding(0, dp(4), 0, dp(4))
        skipRow.addView(aib("-" + Prefs.seekSec(this) + "s", 14f, aIc) {
            val now = Engine.mp?.getTime() ?: 0L
            Engine.seekTo((now - Prefs.seekSec(this) * 1000L).coerceAtLeast(0L))
        })
        skipRow.addView(aib("+" + Prefs.seekSec(this) + "s", 14f, aIc) {
            val now = Engine.mp?.getTime() ?: 0L
            Engine.seekTo(now + Prefs.seekSec(this) * 1000L)
        })
        audioCol.addView(skipRow, LinearLayout.LayoutParams(MP, WC))

        // ---- extras row (Speed + EQ + Boost + Sleep + Queue) ----
        val exRowAudio = LinearLayout(this)
        exRowAudio.gravity = Gravity.CENTER
        exRowAudio.setPadding(0, dp(10), 0, 0)
        val aSpeed = aib(rateLabel(rate), 14f, aIc) {
            val nextIdx = audioModeSpeeds.indexOfFirst { it > rate + 0.02f }
            val next = if (nextIdx >= 0) audioModeSpeeds[nextIdx] else audioModeSpeeds[0]
            setRateTo(next)
        }
        aSpeed.setOnLongClickListener {
            setRateTo(1f)
            Toast.makeText(this, "Speed 1x", Toast.LENGTH_SHORT).show()
            true
        }
        audioModeSpeedBtn = aSpeed
        exRowAudio.addView(aSpeed)
        exRowAudio.addView(aib("EQ", 14f, aIc) { Dialogs.eq(this) })
        exRowAudio.addView(aib("Boost", 14f, aIc) { Dialogs.boost(this) })
        exRowAudio.addView(aib("Sleep", 14f, aIc) { Dialogs.sleep(this) })
        exRowAudio.addView(aib("Queue", 14f, aIc) { showAudioQueue() })
        audioCol.addView(exRowAudio, LinearLayout.LayoutParams(MP, WC))

        // ---- action row (Convert to Audio + Style Center) ----
        val featureRow = LinearLayout(this)
        featureRow.gravity = Gravity.CENTER
        featureRow.setPadding(0, dp(8), 0, 0)
        featureRow.addView(aib("Convert to Audio", 14f, aIc) {
            Hooks.convert(this, listOf(Engine.cur()), listOf(Engine.title), null)
        })
        featureRow.addView(aib("Style Center", 14f, aIc) { showStyleCenter() })
        audioCol.addView(featureRow, LinearLayout.LayoutParams(MP, WC))

        audioScroll.addView(audioCol, FrameLayout.LayoutParams(MP, WC))
        audioModePanel.addView(audioScroll, LinearLayout.LayoutParams(MP, 0, 1f))

        audioModePanel.visibility = View.GONE
        root.addView(audioModePanel, FrameLayout.LayoutParams(MP, MP))

        val resumeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(16), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC202020.toInt())
                cornerRadius = dp(6).toFloat()
            }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        val resumeCloseBtn = TextView(this).apply {
            text = "\u2715"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(2), dp(2), dp(12), dp(2))
        }
        val resumeMsgTv = TextView(this).apply {
            text = "Continue from where you stopped."
            textSize = 13.5f
            setTextColor(0xFFF2F2F2.toInt())
        }
        val startOverBtn = TextView(this).apply {
            text = "START OVER"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF2A7A7.toInt())
            setPadding(dp(16), dp(2), dp(2), dp(2))
        }
        resumeBar.addView(resumeCloseBtn, LinearLayout.LayoutParams(WC, WC))
        resumeBar.addView(resumeMsgTv, LinearLayout.LayoutParams(WC, WC))
        resumeBar.addView(startOverBtn, LinearLayout.LayoutParams(WC, WC))

        val hideResumeBanner = Runnable {
            resumeBar.animate().alpha(0f).setDuration(180L).withEndAction {
                resumeBar.visibility = View.GONE
                resumeBar.alpha = 1f
            }.start()
        }
        resumeCloseBtn.setOnClickListener {
            ui.removeCallbacks(hideResumeBanner)
            resumeBar.visibility = View.GONE
        }
        startOverBtn.setOnClickListener {
            ui.removeCallbacks(hideResumeBanner)
            resumeBar.visibility = View.GONE
            val curUriStr = if (Engine.items.isNotEmpty()) Engine.cur().toString() else ""
            if (curUriStr.isNotEmpty()) {
                Prefs.setPos(this@PlayerActivity, curUriStr, 0L)
            }
            Engine.seekTo(0L)
            showMsg("Started from beginning")
        }

        val isLandNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val resumeLp = if (isLandNow) {
            FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(44)
                bottomMargin = dp(100)
            }
        } else {
            FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(86)
            }
        }
        root.addView(resumeBar, resumeLp)
        resumeBarRef = resumeBar
        hideResumeBannerRef = hideResumeBanner

        setContentView(root)
        padForCutout()

        attachVideo()
        Engine.listener = lis
        setupTouch()
        showGestureGuideIfNeeded()

        if (!cont) layout.post { Engine.setQueue(this, qUris, qNames, qIndex, true) } else onItem()
        showControls()
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("returnToVideo", false)) {
            Prefs.setAudioModeSession(this, false)
            AudioActivity.audioModeSession = false
            Bg.restoreVideo()
            if (::layout.isInitialized) {
                audioMode = false
                audioModePanel.visibility = View.GONE
                layout.visibility = View.VISIBLE
                touchView.visibility = View.VISIBLE
                atomic?.visibility = View.VISIBLE
                attachVideo()
                syncSpeedFromEngine()
                showControls()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!audioMode && Engine.active) {
            Engine.mp?.resumeSurface()
        }
    }

    override fun onResume() {
        super.onResume()
        PerfBoost.attachActivity(this)
        Engine.playerForeground = true
        Engine.modeSwitching = false
        if (audioMode) {
            if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            audioModePanel.post { updateAudioModeArtSize() }
        } else {
            Prefs.setAudioModeSession(this, false)
            AudioActivity.audioModeSession = false
            Bg.restoreVideo()
            if (::layout.isInitialized && Engine.active) attachVideo()
        }
        audioChildOpen = false
        Engine.listener = lis
        syncSpeedFromEngine()
        lastPlayingState = null
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this) else PlaybackService.stop(this)
        if (controlsVisible()) startControlAnimations() else stopControlAnimations()
        hideBars()
    }

    override fun onPause() {
        Engine.playerForeground = false
        stopControlAnimations()
        super.onPause()
        Engine.saveProgress()
        if (isFinishing) {
            stopVideoImmediatelyIfNeeded()
        } else {
            val allowVideoBg = Prefs.videoBgPlay(this)
            if ((allowVideoBg || audioMode || audioChildOpen || AudioActivity.audioModeSession || Engine.modeSwitching) && Engine.isPlaying()) {
                PlaybackService.start(this)
            }
        }
    }

    override fun onStop() {
        if (!isInPictureInPictureMode && !isChangingConfigurations) Engine.mp?.pauseSurface()
        super.onStop()
        val allowVideoBg = Prefs.videoBgPlay(this)
        val switchingToAudio = audioMode || audioChildOpen || AudioActivity.audioModeSession ||
            AudioActivity.visible || Engine.modeSwitching || Prefs.audioModeSession(this)
        if (Engine.isPlaying() && !allowVideoBg && !switchingToAudio) {
            Engine.pause()
            if (isFinishing) PlaybackService.stop(this)
        }
    }

    override fun onDestroy() {
        stopTopIconGlow()
        stopHeadphoneAnimations()
        playerGearMotion?.stop()
        audioGearMotion?.stop()
        playerGearMotion = null
        audioGearMotion = null
        titleRgbAnimator?.cancel()
        titleRgbAnimator = null
        titleRgbShader = null
        super.onDestroy()
        AdManager.markVideoExited()
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
        val keepAudioSession = Prefs.audioModeSession(this) || AudioActivity.audioModeSession || AudioActivity.visible || audioChildOpen || Engine.modeSwitching
        if (!Engine.bgAudio && Engine.listener == null && !keepAudioSession) {
            Engine.stopAll(this)
        } else if (Engine.active && Engine.isPlaying() && (Engine.bgAudio || keepAudioSession)) {
            PlaybackService.start(this)
        }
    }
}
