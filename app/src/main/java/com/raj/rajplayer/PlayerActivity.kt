package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerActivity : Activity(), SurfaceHolder.Callback, IVLCVout.OnNewVideoLayoutListener {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var marginAtomic: AtomicView
    private lateinit var uiOverlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var centerToast: TextView
    private lateinit var titleTv: TextView
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var ratioBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var marginBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager

    private var videoW = 0
    private var videoH = 0
    private var attached = false
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false

    // Aspect Ratio & Sizing Modes:
    // 0 = Native Default Fit, 1 = Fill Screen, 2 = 16:9, 3 = 9:16, 4 = 4:3, 5 = Small 75% (shows margin animation), 6 = Mini 55% (shows margin animation)
    private var ratioMode = 0
    private val ratioNames = arrayOf("Native", "Fill", "16:9", "9:16", "4:3", "Small 75%", "Mini 55%")

    // Playback Speeds up to 6.0x
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 6.0f)
    private var speedIdx = 2

    // Zoom & Touch Gesture Isolation State
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoomScale = 1.0f
    private var isZooming = false
    private var zoomCooldownUntil = 0L
    private var touchMode = 0 // 0 = none, 1 = seek, 2 = volume, 3 = brightness
    private var downX = 0f
    private var downY = 0f
    private var startVol = 0
    private var maxVol = 15
    private var startBright = 0.5f
    private var startSeekPos = 0L
    private var lastTapTime = 0L
    private var lastTapX = 0f

    private val hideControlsRun = Runnable { hideControls() }
    private val hideToastRun = Runnable { centerToast.visibility = View.GONE }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    private val lis = object : Engine.Listener {
        override fun onItemChanged() {
            runOnUiThread { onTrackLoaded() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun ib(txt: String, size: Float, color: Int, fn: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = txt
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        t.setOnClickListener {
            resetHideTimer()
            fn(t)
        }
        return t
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingUri(): Uri? {
        if (intent?.action == Intent.ACTION_SEND) {
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) return stream
            val cd = intent.clipData
            if (cd != null && cd.itemCount > 0) return cd.getItemAt(0).uri
        }
        return intent?.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Default unlocked user orientation so 16:9 or 9:16 opens without forced sensor rotation bugs
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

        val cont = intent.getBooleanExtra("cont", false) && Engine.active && Engine.isVideo
        var uris: List<Uri> = emptyList()
        var names: List<String> = emptyList()
        var index = 0

        if (!cont) {
            val u0 = extractIncomingUri()
            if (u0 == null) {
                finish()
                return
            }
            Prefs.setLastPlayed(this, u0.toString())
            val l = intent.getStringArrayListExtra("list")
            uris = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
            val nm = intent.getStringArrayListExtra("names")
            names = if (nm != null && nm.size == uris.size) nm.toList() else emptyList()
            index = intent.getIntExtra("idx", 0)
        }

        val ic = Prefs.iconColor(this)
        val ac = Prefs.accent(this)
        val vBar = Prefs.videoBarColor(this)
        val vTxt = Prefs.videoTextColor(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // 1. Margin Atomic Animation View (Clips out the active video surface so it only renders in black margins)
        marginAtomic = AtomicView(this, isVideoMarginMode = true)
        root.addView(marginAtomic, FrameLayout.LayoutParams(MP, MP))

        // 2. Video Container & SurfaceView
        videoContainer = FrameLayout(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        videoContainer.addView(surface, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))
        root.addView(videoContainer, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))

        // Exclude actual video SurfaceView from atomic animation so animation only draws in the blank black margins
        marginAtomic.excludeView(surface)

        // 3. Touch Gesture Layer (Pinch-to-Zoom strictly isolated from Volume / Brightness / Seek)
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isZooming = true
                touchMode = 0
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                isZooming = true
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                surface.scaleX = zoomScale
                surface.scaleY = zoomScale
                showCenterToast("Zoom: " + (zoomScale * 100).roundToInt() + "%")
                marginAtomic.invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isZooming = false
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
            }
        })

        val touchLayer = View(this)
        touchLayer.setOnTouchListener { _, ev -> handleTouch(ev) }
        root.addView(touchLayer, FrameLayout.LayoutParams(MP, MP))

        // 4. UI Controls Overlay
        uiOverlay = FrameLayout(this)

        // Top Bar
        topBar = LinearLayout(this)
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(vBar)
        topBar.setPadding(dp(8), dp(20), dp(8), dp(8))
        topBar.addView(ib("\u2190", 22f, ic) { finish() })

        titleTv = TextView(this)
        titleTv.textSize = 15f
        titleTv.setTextColor(vTxt)
        titleTv.setTypeface(null, Typeface.BOLD)
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))

        topBar.addView(ib("\uD83D\uDD16", 18f, ic) { bookmarksDialog() })
        topBar.addView(ib("\uD83D\uDCF1", 18f, ic) { toggleOrientation() })
        if (Build.VERSION.SDK_INT >= 26) {
            topBar.addView(ib("PiP", 13f, ic) { enterPip() })
        }
        uiOverlay.addView(topBar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        // Lock Button
        lockBtn = ib("\uD83D\uDD13", 22f, ic) { toggleLock() }
        val lockLp = FrameLayout.LayoutParams(WC, WC, Gravity.CENTER_VERTICAL or Gravity.START)
        lockLp.setMargins(dp(14), 0, 0, 0)
        uiOverlay.addView(lockBtn, lockLp)

        // Bottom Bar
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setBackgroundColor(vBar)
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(16))

        // Seek Row
        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(vTxt)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(vTxt)
        durTv.textSize = 12f
        durTv.text = "0:00"
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ac)
        seek.thumbTintList = ColorStateList.valueOf(ac)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetHideTimer()
                    curTv.text = fmt(p.toLong())
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
                resetHideTimer()
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                Engine.seekTo((s?.progress ?: 0).toLong())
                userSeeking = false
                resetHideTimer()
            }
        })
        seekRow.addView(curTv)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        seekRow.addView(durTv)
        bottomBar.addView(seekRow, LinearLayout.LayoutParams(MP, WC))

        // Main Transport Row
        val tr = LinearLayout(this)
        tr.gravity = Gravity.CENTER
        val seekDelta = Prefs.seekSec(this)
        tr.addView(ib("\u23EE", 24f, ic) { Engine.prev(this) })
        tr.addView(ib("-" + seekDelta + "s", 14f, ic) { skipBy(-seekDelta * 1000L) })

        playBtn = ib("\u23F8", 26f, contrast(ac)) {
            Engine.toggle(this)
            updateProgress()
        }
        val playCircle = GradientDrawable()
        playCircle.shape = GradientDrawable.OVAL
        playCircle.setColor(ac)
        playBtn.background = playCircle
        val plp = LinearLayout.LayoutParams(dp(58), dp(58))
        plp.setMargins(dp(16), dp(4), dp(16), dp(4))
        tr.addView(playBtn, plp)

        tr.addView(ib("+" + seekDelta + "s", 14f, ic) { skipBy(seekDelta * 1000L) })
        tr.addView(ib("\u23ED", 24f, ic) { Engine.next(this) })
        bottomBar.addView(tr, LinearLayout.LayoutParams(MP, WC))

        // Bottom Extra Options Row (Speed up to 6x, Aspect Ratio, Margin Animation Toggle, Audio/Sub)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val ex = LinearLayout(this)
        ex.gravity = Gravity.CENTER_VERTICAL

        speedBtn = ib("1.0x", 13f, ic) { speedDialog() }
        ex.addView(speedBtn)

        ratioBtn = ib("Ratio: Native", 13f, ic) { cycleRatio() }
        ex.addView(ratioBtn)

        marginBtn = ib(
            if (Prefs.playerMarginAnim(this)) "\u2728 Margin: ON" else "\u2728 Margin: OFF",
            13f, ic
        ) { b ->
            val nextState = !Prefs.playerMarginAnim(this)
            Prefs.setPlayerMarginAnim(this, nextState)
            b.text = if (nextState) "\u2728 Margin: ON" else "\u2728 Margin: OFF"
            marginAtomic.applyPrefs()
            showCenterToast(if (nextState) "Margin Animation: ON" else "Margin Animation: OFF (Clean Black)")
        }
        ex.addView(marginBtn)

        ex.addView(ib("100% Zoom", 13f, ic) {
            zoomScale = 1.0f
            surface.scaleX = 1.0f
            surface.scaleY = 1.0f
            marginAtomic.invalidate()
            showCenterToast("Zoom Reset: 100%")
        })

        ex.addView(ib("Audio", 13f, ic) { Dialogs.audioTracks(this) })
        ex.addView(ib("Sub", 13f, ic) { Dialogs.subTracks(this) })
        ex.addView(ib("EQ", 13f, ic) { Dialogs.eq(this) })
        hs.addView(ex)
        bottomBar.addView(hs, LinearLayout.LayoutParams(MP, WC))

        uiOverlay.addView(bottomBar, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))
        root.addView(uiOverlay, FrameLayout.LayoutParams(MP, MP))

        // Center Status Toast
        centerToast = TextView(this)
        centerToast.textSize = 16f
        centerToast.setTextColor(Color.WHITE)
        centerToast.setTypeface(null, Typeface.BOLD)
        centerToast.setPadding(dp(18), dp(10), dp(18), dp(10))
        val toastBg = GradientDrawable()
        toastBg.setColor(0xCC000000.toInt())
        toastBg.cornerRadius = dp(12).toFloat()
        centerToast.background = toastBg
        centerToast.visibility = View.GONE
        root.addView(centerToast, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))

        if (Prefs.rgbBorder(this)) root.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        hideSystemBars()

        Engine.listener = lis
        if (!cont) {
            Engine.setQueue(this, uris, names, index, true)
        }
        applySmoothAvSyncSettings()
        onTrackLoaded()
        resetHideTimer()
    }

    // Ensures smooth Audio-Video sync at 1.5x, 2x, 2.5x, 3x, 4x, 6x without stuttering
    private fun applySmoothAvSyncSettings() {
        try {
            val p = Engine.mp ?: return
            p.media?.let { m ->
                m.addOption(":audio-time-stretch")
                m.addOption(":avcodec-fast")
                m.addOption(":avcodec-skiploopfilter=4")
                m.addOption(":file-caching=1500")
                m.addOption(":network-caching=1500")
                m.addOption(":clock-jitter=0")
                m.addOption(":clock-synchro=0")
            }
        } catch (e: Exception) {
        }
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        // Always feed ScaleGestureDetector first
        scaleDetector.onTouchEvent(ev)

        val now = SystemClock.uptimeMillis()

        // Strictly isolate multi-touch / zooming from volume, brightness, and seeking
        if (ev.pointerCount > 1 || isZooming || scaleDetector.isInProgress || now < zoomCooldownUntil) {
            touchMode = 0
            if (ev.pointerCount > 1) zoomCooldownUntil = now + 220L
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                touchMode = 0
                startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val lp = window.attributes
                startBright = if (lp.screenBrightness > 0f) lp.screenBrightness else 0.5f
                startSeekPos = Engine.mp?.time ?: 0L
            }

            MotionEvent.ACTION_MOVE -> {
                if (locked) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                val slop = dp(18).toFloat()

                if (touchMode == 0) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        touchMode = 1 // Horizontal Seek
                    } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) {
                        touchMode = if (downX < root.width * 0.5f) 3 else 2 // Left = Brightness, Right = Volume
                    }
                }

                when (touchMode) {
                    1 -> {
                        val len = Engine.mp?.length ?: 0L
                        if (len > 0L) {
                            val deltaMs = ((dx / root.width.coerceAtLeast(1)) * 90_000f).toLong()
                            val target = (startSeekPos + deltaMs).coerceIn(0L, len)
                            Engine.seekTo(target)
                            showCenterToast(fmt(target) + " / " + fmt(len))
                        }
                    }
                    2 -> {
                        val deltaV = ((-dy / (root.height.coerceAtLeast(1) * 0.65f)) * maxVol).roundToInt()
                        val newVol = (startVol + deltaV).coerceIn(0, maxVol)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        val pct = ((newVol.toFloat() / maxVol) * 100).roundToInt()
                        showCenterToast("\uD83D\uDD0A Volume: $pct%")
                    }
                    3 -> {
                        val deltaB = -dy / (root.height.coerceAtLeast(1) * 0.65f)
                        val newB = (startBright + deltaB).coerceIn(0.05f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = newB
                        window.attributes = lp
                        val pct = (newB * 100).roundToInt()
                        showCenterToast("\u2600\uFE0F Brightness: $pct%")
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (touchMode == 0) {
                    val tapTime = SystemClock.uptimeMillis()
                    if (!locked && tapTime - lastTapTime < 280L && abs(ev.x - lastTapX) < dp(80)) {
                        val seekMs = Prefs.seekSec(this) * 1000L
                        if (ev.x < root.width * 0.4f) {
                            skipBy(-seekMs)
                        } else if (ev.x > root.width * 0.6f) {
                            skipBy(seekMs)
                        } else {
                            Engine.toggle(this)
                            updateProgress()
                        }
                        lastTapTime = 0L
                    } else {
                        lastTapTime = tapTime
                        lastTapX = ev.x
                        if (controlsVisible) hideControls() else showControls()
                    }
                }
                touchMode = 0
            }

            MotionEvent.ACTION_CANCEL -> {
                touchMode = 0
            }
        }
        return true
    }

    private fun skipBy(deltaMs: Long) {
        val p = Engine.mp ?: return
        val len = p.length
        if (len <= 0L) return
        val next = (p.time + deltaMs).coerceIn(0L, len)
        Engine.seekTo(next)
        showCenterToast((if (deltaMs > 0) "+" else "") + (deltaMs / 1000) + "s (" + fmt(next) + ")")
        updateProgress()
    }

    private fun showCenterToast(msg: String) {
        centerToast.text = msg
        centerToast.visibility = View.VISIBLE
        ui.removeCallbacks(hideToastRun)
        ui.postDelayed(hideToastRun, 900)
    }

    private fun toggleLock() {
        locked = !locked
        lockBtn.text = if (locked) "\uD83D\uDD12" else "\uD83D\uDD13"
        topBar.visibility = if (locked) View.GONE else View.VISIBLE
        bottomBar.visibility = if (locked) View.GONE else View.VISIBLE
        showCenterToast(if (locked) "Controls Locked" else "Controls Unlocked")
        resetHideTimer()
    }

    private fun showControls() {
        controlsVisible = true
        lockBtn.visibility = View.VISIBLE
        if (!locked) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
        }
        resetHideTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        lockBtn.visibility = View.GONE
        hideSystemBars()
    }

    private fun resetHideTimer() {
        ui.removeCallbacks(hideControlsRun)
        if (controlsVisible) ui.postDelayed(hideControlsRun, 4000)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun toggleOrientation() {
        val cur = resources.configuration.orientation
        requestedOrientation = if (cur == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun cycleRatio() {
        ratioMode = (ratioMode + 1) % ratioNames.size
        ratioBtn.text = "Ratio: " + ratioNames[ratioMode]
        applyVideoLayout()
        showCenterToast("Aspect Ratio: " + ratioNames[ratioMode])
    }

    // Fits video cleanly into its native aspect ratio (16:9, 9:16, etc.) or user-selected size
    private fun applyVideoLayout() {
        val parentW = root.width
        val parentH = root.height
        if (parentW <= 0 || parentH <= 0) return

        val natW = if (videoW > 0) videoW else 16
        val natH = if (videoH > 0) videoH else 9
        val nativeRatio = natW.toFloat() / natH.toFloat()
        val screenRatio = parentW.toFloat() / parentH.toFloat()

        var targetW = parentW
        var targetH = parentH

        when (ratioMode) {
            0 -> { // Native Default Aspect Ratio (16:9 stays 16:9, 9:16 stays 9:16)
                if (nativeRatio > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / nativeRatio).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * nativeRatio).roundToInt()
                }
            }
            1 -> { // Fill Screen
                targetW = parentW
                targetH = parentH
            }
            2 -> { // 16:9
                val r = 16f / 9f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            3 -> { // 9:16
                val r = 9f / 16f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            4 -> { // 4:3
                val r = 4f / 3f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            5 -> { // Small 75% (Reveals Diamond Bonding Animation in surrounding black area)
                if (nativeRatio > screenRatio) {
                    targetW = (parentW * 0.75f).roundToInt()
                    targetH = (targetW / nativeRatio).roundToInt()
                } else {
                    targetH = (parentH * 0.75f).roundToInt()
                    targetW = (targetH * nativeRatio).roundToInt()
                }
            }
            6 -> { // Mini 55% (Reveals wide Diamond Bonding Animation margin around video)
                if (nativeRatio > screenRatio) {
                    targetW = (parentW * 0.55f).roundToInt()
                    targetH = (targetW / nativeRatio).roundToInt()
                } else {
                    targetH = (parentH * 0.55f).roundToInt()
                    targetW = (targetH * nativeRatio).roundToInt()
                }
            }
        }

        val lp = FrameLayout.LayoutParams(targetW.coerceAtLeast(100), targetH.coerceAtLeast(100), Gravity.CENTER)
        surface.layoutParams = lp
        try {
            Engine.mp?.vlcVout?.setWindowSize(targetW.coerceAtLeast(100), targetH.coerceAtLeast(100))
            Engine.mp?.aspectRatio = null
            Engine.mp?.scale = 0f
        } catch (e: Exception) {
        }
        surface.post { marginAtomic.invalidate() }
    }

    private fun speedDialog() {
        val labels = Array<CharSequence>(speeds.size) { i ->
            String.format("%.2fx", speeds[i]).replace(".00x", ".0x").replace("50x", "5x")
        }
        AlertDialog.Builder(this)
            .setTitle("Playback Speed (Smooth A/V Sync)")
            .setItems(labels) { _, which ->
                speedIdx = which
                val sp = speeds[which]
                applySmoothAvSyncSettings()
                Engine.mp?.rate = sp
                speedBtn.text = String.format("%.1fx", sp)
                showCenterToast("Speed: " + String.format("%.2fx", sp))
            }
            .show()
    }

    private fun bookmarksDialog() {
        val key = Engine.cur().toString()
        val list = Prefs.bms(this, key)
        val items = ArrayList<CharSequence>()
        items.add("\u2795 Add Bookmark at " + fmt(Engine.mp?.time ?: 0L))
        for (ms in list) items.add("\uD83D\uDD16 Jump to " + fmt(ms))
        if (list.isNotEmpty()) items.add("\uD83D\uDDD1 Clear All Bookmarks")

        val arr = items.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Video Bookmarks")
            .setItems(arr) { _, idx ->
                if (idx == 0) {
                    val now = Engine.mp?.time ?: 0L
                    Prefs.setBms(this, key, (list + now).distinct().sorted())
                    Toast.makeText(this, "Bookmark saved at " + fmt(now), Toast.LENGTH_SHORT).show()
                } else if (list.isNotEmpty() && idx == arr.size - 1) {
                    Prefs.setBms(this, key, emptyList())
                    Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                } else {
                    val target = list.getOrNull(idx - 1) ?: return@setItems
                    Engine.seekTo(target)
                }
            }
            .show()
    }

    private fun onTrackLoaded() {
        if (!Engine.active) {
            finish()
            return
        }
        val curUri = Engine.items.getOrNull(Engine.idx)
        if (curUri != null) Prefs.setLastPlayed(this, curUri.toString())
        titleTv.text = Engine.title
        applySmoothAvSyncSettings()
        updateProgress()
    }

    private fun updateProgress() {
        val p = Engine.mp ?: return
        val len = p.length
        val t = p.time
        if (len > 0) {
            if (!userSeeking) {
                seek.max = len.toInt()
                seek.progress = t.toInt()
            }
            durTv.text = fmt(len)
        }
        if (!userSeeking) curTv.text = fmt(t)
        playBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
    }

    private fun attachSurface() {
        val p = Engine.mp ?: return
        if (attached) return
        val vout = p.vlcVout
        vout.setVideoView(surface)
        vout.attachViews(this)
        attached = true
        root.post { applyVideoLayout() }
    }

    private fun detachSurface() {
        if (!attached) return
        try {
            Engine.mp?.vlcVout?.detachViews()
        } catch (e: Exception) {
        }
        attached = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            Engine.mp?.vlcVout?.setWindowSize(width, height)
        } catch (e: Exception) {
        }
        marginAtomic.invalidate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun onNewVideoLayout(
        vout: IVLCVout?,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        if (width > 0 && height > 0) {
            videoW = if (visibleWidth > 0) visibleWidth else width
            videoH = if (visibleHeight > 0) visibleHeight else height
            runOnUiThread { applyVideoLayout() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.postDelayed({ applyVideoLayout() }, 80)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val r = Rational(
                    (if (videoW > 0) videoW else 16).coerceIn(1, 239),
                    (if (videoH > 0) videoH else 9).coerceIn(1, 100)
                )
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build())
            } catch (e: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        marginAtomic.applyPrefs()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        val curKey = Engine.cur().toString()
        val pos = Engine.mp?.time ?: 0L
        if (curKey.isNotEmpty() && pos > 0L) Prefs.setPos(this, curKey, pos)
    }

    override fun onDestroy() {
        super.onDestroy()
        detachSurface()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
