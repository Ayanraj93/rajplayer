package com.raj.rajplayer

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

class AudioActivity : Activity() {

    companion object {
        @Volatile var visible: Boolean = false
        @Volatile var audioModeSession: Boolean = false
    }

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var shufBtn: TextView
    private lateinit var repBtn: TextView
    private lateinit var artImg: ImageView
    private lateinit var artNote: TextView
    private lateinit var rootView: FrameLayout
    private lateinit var gearButton: TextView
    private lateinit var topBarView: LinearLayout
    private lateinit var playCircle: GradientDrawable
    private var gearAnimator: ValueAnimator? = null
    private var settingsGearMotion: GearMotion? = null
    private var backgroundTitleView: TextView? = null
    private var backgroundTitleRgbAnimator: ValueAnimator? = null
    private var backgroundTitleBlinkAnimator: ValueAnimator? = null
    private var wallView: ImageView? = null
    private var wallDim: View? = null

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var userSeeking = false
    private var switchingToVideo = false
    private var audioSystemBarsHidden = false
    private var artKey = ""
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
    private var speedIdx = 0
    private val rgbTargets = ArrayList<(Int) -> Unit>()

    private val lis = object : Engine.Listener {
        override fun onItemChanged() {
            runOnUiThread { onItem() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, 500)
        }
    }

    private val rgbTick = object : Runnable {
        override fun run() {
            if (rgbTargets.isNotEmpty()) {
                val col = Theme.rgbNow(0f)
                for (t in rgbTargets) t(col)
                ui.postDelayed(this, 120)
            }
        }
    }

    private fun startBackgroundTitleAnimation() {
        val title = backgroundTitleView ?: return
        if (title.text != "BACKGROUND PLAY") return
        backgroundTitleRgbAnimator?.cancel()
        backgroundTitleBlinkAnimator?.cancel()

        backgroundTitleRgbAnimator = ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE,
            Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                title.setTextColor(it.animatedValue as Int)
            }
            start()
        }

        backgroundTitleBlinkAnimator = ValueAnimator.ofFloat(0.82f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                title.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopBackgroundTitleAnimation() {
        backgroundTitleRgbAnimator?.cancel()
        backgroundTitleRgbAnimator = null
        backgroundTitleBlinkAnimator?.cancel()
        backgroundTitleBlinkAnimator = null
        backgroundTitleView?.alpha = 1f
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

    private fun ib(sym: String, size: Float, color: Int, f: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setOnClickListener { f(t) }
        if (!sym.contains("\u2699")) rgbTargets.add { c -> t.setTextColor(c) }
        return t
    }

    @Suppress("DEPRECATION")
    private fun hideAudioSystemBars() {
        audioSystemBarsHidden = true
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    @Suppress("DEPRECATION")
    private fun showAudioSystemBars() {
        audioSystemBarsHidden = false
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (audioSystemBarsHidden) hideAudioSystemBars() else showAudioSystemBars()
        }
    }

    private fun goToVideoList() {
        // Keep the video/audio session alive; only remove Player/Audio screens from foreground.
        if (Prefs.audioModeSession(this)) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        } else {
            finish()
        }
    }

    private fun backToVideoPlayer() {
        if (switchingToVideo) return
        val uri = Engine.items.getOrNull(Engine.idx) ?: return
        switchingToVideo = true
        Engine.modeSwitching = true
        Prefs.setAudioModeSession(this, false)
        audioModeSession = false
        Bg.restoreVideo()
        val i = Intent(this, Orient.videoClass(this, uri))
        i.data = uri
        i.putExtra("cont", true)
        i.putExtra("returnToVideo", true)
        i.addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_NO_ANIMATION or
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        startActivity(i)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(audioPalette(Prefs.audioTheme(this))[0]))
        } catch (_: Exception) { }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (intent.getBooleanExtra("fromVideo", false)) {
            audioModeSession = true
            Prefs.setAudioModeSession(this, true)
        }
        val cont = intent.getBooleanExtra("cont", false) && Engine.active
        if (!cont && Engine.isVideo) {
            audioModeSession = false
            Prefs.setAudioModeSession(this, false)
        }
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

        val tc = audioTextColor()
        val ic = audioIconColor()
        val ac = audioAccent()
        val bar = audioBarColor()
        val rgb = audioRgbEnabled()
        val rgbAcc = rgb
        val rgbTxt = rgb

        val root = FrameLayout(this)
        rootView = root
        root.setBackgroundColor(audioPalette(Prefs.audioTheme(this))[0])
        val audioBackgroundGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (audioSystemBarsHidden) showAudioSystemBars() else hideAudioSystemBars()
                return true
            }
        })
        root.setOnTouchListener { _, event -> audioBackgroundGestures.onTouchEvent(event) }
        val w = Prefs.audioWall(this)
        if (w != null) {
            try {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageURI(Uri.parse(w))
                root.addView(iv, FrameLayout.LayoutParams(MP, MP))
                val dim = View(this)
                dim.setBackgroundColor(0xBB000000.toInt())
                root.addView(dim, FrameLayout.LayoutParams(MP, MP))
                wallView = iv
                wallDim = dim
            } catch (e: Exception) {
            }
        }

        // atomic animation: background me, touch nahi pakadta
        var atomic: AtomicView? = null
        if (Prefs.atomicOn(this) && Prefs.themeMode(this) != "none") {
            val av = AtomicView(this)
            atomic = av
            root.addView(av, FrameLayout.LayoutParams(MP, MP))
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(36), dp(24), dp(16))

        // ---- top row (audio title bar) ----
        val top = LinearLayout(this)
        topBarView = top
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(bar)
        top.setPadding(dp(4), dp(2), dp(4), dp(2))
        val videoSession = intent.getBooleanExtra("fromVideo", false) || Prefs.audioModeSession(this)
        top.addView(ib("←", 24f, ic) {
            goToVideoList()
        })
        if (videoSession) {
            top.addView(ib("Back to Video", 15f, ic) {
                backToVideoPlayer()
            })
        }
        val hdr = TextView(this)
        hdr.text = if (videoSession) "BACKGROUND PLAY" else "NOW PLAYING"
        hdr.textSize = 13f
        hdr.setTypeface(null, android.graphics.Typeface.BOLD)
        hdr.setTextColor(tc)
        hdr.alpha = 1f
        hdr.setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        hdr.gravity = Gravity.CENTER
        hdr.setSingleLine(true)
        backgroundTitleView = hdr
        val hdrLp = LinearLayout.LayoutParams(MP, WC)
        hdrLp.bottomMargin = dp(4)
        col.addView(hdr, hdrLp)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val settingsGear = ib("\u2699", 22f, ic) { showStyleCenter() }.also { gearButton = it }
        settingsGear.setOnClickListener {
            settingsGearMotion?.tap()
            showStyleCenter()
        }
        top.addView(settingsGear)
        top.addView(ib("\u22EF", 24f, ic) { menu() })
        top.addView(ib("\u2715", 20f, ic) {
            Prefs.setAudioModeSession(this, false)
            audioModeSession = false
            Engine.stopAll(this)
            finish()
        })
        col.addView(top, LinearLayout.LayoutParams(MP, WC))

        // ---- album art ----
        val artSize = Math.min(resources.displayMetrics.widthPixels - dp(64), dp(340))
        val artBox = FrameLayout(this)
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ac, 0xFF000000.toInt()))
        artBox.background = g
        artBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
            }
        }
        artBox.clipToOutline = true
        artNote = TextView(this)
        artNote.text = "\uD83C\uDFB5"
        artNote.textSize = 84f
        artNote.gravity = Gravity.CENTER
        artBox.addView(artNote, FrameLayout.LayoutParams(MP, MP))
        artImg = ImageView(this)
        artImg.scaleType = ImageView.ScaleType.CENTER_CROP
        artBox.addView(artImg, FrameLayout.LayoutParams(MP, MP))
        val alp = LinearLayout.LayoutParams(artSize, artSize)
        alp.setMargins(0, dp(20), 0, dp(20))
        col.addView(artBox, alp)
        atomic?.excludeView(artBox)

        // ---- title ----
        titleTv = TextView(this)
        titleTv.textSize = 20f
        titleTv.setTextColor(tc)
        titleTv.gravity = Gravity.CENTER
        titleTv.maxLines = 2
        titleTv.ellipsize = TextUtils.TruncateAt.END
        titleTv.setTypeface(null, Typeface.BOLD)
        col.addView(titleTv, LinearLayout.LayoutParams(MP, WC))
        subTv = TextView(this)
        subTv.textSize = 13f
        subTv.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
        subTv.gravity = Gravity.CENTER
        subTv.setPadding(0, dp(4), 0, dp(12))
        col.addView(subTv, LinearLayout.LayoutParams(MP, WC))

        // ---- seek ----
        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(tc)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(tc)
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
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                Engine.seekTo((s?.progress ?: 0).toLong())
                userSeeking = false
            }
        })
        seekRow.addView(curTv)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        seekRow.addView(durTv)
        col.addView(seekRow, LinearLayout.LayoutParams(MP, WC))

        // ---- transport ----
        val tr = LinearLayout(this)
        tr.gravity = Gravity.CENTER
        shufBtn = ib("\uD83D\uDD00", 22f, ic) {
            Prefs.setShuffle(this, !Prefs.shuffle(this))
            refreshModes()
        }
        repBtn = ib("\uD83D\uDD01", 22f, ic) {
            Prefs.setRepeat(this, (Prefs.repeat(this) + 1) % 3)
            refreshModes()
        }
        tr.addView(shufBtn)
        tr.addView(ib("\u23EE", 30f, ic) { Engine.prev(this) })
        playBtn = ib("\u23F8", 30f, contrast(ac)) { Engine.toggle(this) }
        playCircle = GradientDrawable()
        playCircle.shape = GradientDrawable.OVAL
        playCircle.setColor(ac)
        playBtn.background = playCircle
        val plp = LinearLayout.LayoutParams(dp(72), dp(72))
        plp.setMargins(dp(12), 0, dp(12), 0)
        tr.addView(playBtn, plp)
        tr.addView(ib("\u23ED", 30f, ic) { Engine.next(this) })
        tr.addView(repBtn)
        col.addView(tr, LinearLayout.LayoutParams(MP, WC))

        // Audio seek step uses the same configurable interval as video.
        val audioSeekRow = LinearLayout(this)
        audioSeekRow.gravity = Gravity.CENTER
        audioSeekRow.setPadding(0, dp(4), 0, dp(4))
        audioSeekRow.addView(ib("-" + Prefs.seekSec(this) + "s", 14f, ic) {
            val now = Engine.mp?.time ?: 0L
            Engine.seekTo((now - Prefs.seekSec(this) * 1000L).coerceAtLeast(0L))
        })
        audioSeekRow.addView(ib("+" + Prefs.seekSec(this) + "s", 14f, ic) {
            val now = Engine.mp?.time ?: 0L
            Engine.seekTo(now + Prefs.seekSec(this) * 1000L)
        })
        col.addView(audioSeekRow, LinearLayout.LayoutParams(MP, WC))

        // ---- extras ----
        val ex = LinearLayout(this)
        ex.gravity = Gravity.CENTER
        ex.setPadding(0, dp(12), 0, 0)
        speedBtn = ib("1x", 14f, ic) { b ->
            val cur = Engine.mp?.getRate() ?: speeds[speedIdx]
            val nextIdx = speeds.indexOfFirst { it > cur + 0.02f }
            speedIdx = if (nextIdx >= 0) nextIdx else 0
            val next = speeds[speedIdx]
            Engine.mp?.setRate(next)
            b.text = rateLabel(next)
        }
        ex.addView(speedBtn)
        ex.addView(ib("EQ", 14f, ic) { Dialogs.eq(this) })
        ex.addView(ib("Boost", 14f, ic) { Dialogs.boost(this) })
        ex.addView(ib("Sleep", 14f, ic) { Dialogs.sleep(this) })
        ex.addView(ib("Queue", 14f, ic) { queueDialog() })
        col.addView(ex, LinearLayout.LayoutParams(MP, WC))

        val actionRow = LinearLayout(this)
        actionRow.gravity = Gravity.CENTER
        actionRow.setPadding(0, dp(8), 0, 0)
        actionRow.addView(ib("Convert to Audio", 14f, ic) {
            val u = Engine.items.getOrNull(Engine.idx) ?: return@ib
            Hooks.convert(this, listOf(u), listOf(Engine.title), null)
        })
        actionRow.addView(ib("Style Center", 14f, ic) { showStyleCenter() })
        col.addView(actionRow, LinearLayout.LayoutParams(MP, WC))

        root.addView(col, FrameLayout.LayoutParams(MP, MP))
        if (Prefs.rgbBorder(this)) root.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        val audioBarsGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (audioSystemBarsHidden) showAudioSystemBars() else hideAudioSystemBars()
                return true
            }
        })
        root.setOnTouchListener { _, event -> audioBarsGesture.onTouchEvent(event) }
        applyAudioStyle()

        // RGB theme: kuch cheezein rang badalti rehti hain
        rgbTargets.add { c ->
            seek.progressTintList = ColorStateList.valueOf(c)
            seek.thumbTintList = ColorStateList.valueOf(c)
        }
        rgbTargets.add { c -> g.setColors(intArrayOf(c, 0xFF000000.toInt())) }
        rgbTargets.add { c -> titleTv.setTextColor(c) }

        Engine.listener = lis
        if (!cont) Engine.setQueue(this, uris, names, index, false)
        onItem()
        refreshModes()
    }

    private fun refreshModes() {
        shufBtn.alpha = if (Prefs.shuffle(this)) 1f else 0.35f
        val r = Prefs.repeat(this)
        repBtn.text = if (r == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
        repBtn.alpha = if (r == 0) 0.35f else 1f
    }

    private fun rateLabel(r: Float): String =
        String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.') + "x"

    private fun syncSpeedUi() {
        val cur = Engine.mp?.getRate() ?: speeds[speedIdx]
        val exact = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.02f }
        if (exact >= 0) speedIdx = exact
        if (::speedBtn.isInitialized) speedBtn.text = rateLabel(cur)
    }

    private fun onItem() {
        if (!Engine.active || Engine.items.isEmpty()) {
            finish()
            return
        }
        Prefs.setLastPlayedUri(this, Engine.cur().toString())
        titleTv.text = Engine.title
        syncSpeedUi()
        subTv.text = (Engine.idx + 1).toString() + " / " + Engine.items.size
        loadArt()
        update()
    }

    private fun loadArt() {
        val u = Engine.items.getOrNull(Engine.idx) ?: return
        val key = u.toString()
        if (key == artKey) return
        artKey = key
        artImg.setImageBitmap(null)
        artNote.visibility = View.VISIBLE
        pool.execute {
            val b: Bitmap? = try {
                contentResolver.loadThumbnail(u, Size(600, 600), null)
            } catch (e: Exception) {
                null
            }
            if (b != null) {
                runOnUiThread {
                    if (artKey == key) {
                        artImg.setImageBitmap(b)
                        artNote.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun update() {
        val p = Engine.mp ?: return
        val len = p.getLength()
        val t = p.getTime()
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

    private fun audioPalette(theme: String): IntArray = when (theme) {
        "nature" -> intArrayOf(0xFF071B12.toInt(), 0xFF19C37D.toInt(), 0xFFE9FFF4.toInt(), 0xFF0B3828.toInt())
        "neon" -> intArrayOf(0xFF090817.toInt(), 0xFF00E5FF.toInt(), 0xFFF5F0FF.toInt(), 0xFF21113F.toInt())
        "vinyl" -> intArrayOf(0xFF21090B.toInt(), 0xFFFF5A36.toInt(), 0xFFFFF0E8.toInt(), 0xFF3B1015.toInt())
        "synthwave" -> intArrayOf(0xFF100820.toInt(), 0xFFFF36B7.toInt(), 0xFFFFF1FF.toInt(), 0xFF281044.toInt())
        "aurora" -> intArrayOf(0xFF061522.toInt(), 0xFF45E0C1.toInt(), 0xFFE8FFFF.toInt(), 0xFF0A3040.toInt())
        else -> intArrayOf(Prefs.bg(this), Prefs.accent(this), Prefs.audioTextColor(this), Prefs.audioBarColor(this))
    }

    private fun audioAccent() = audioPalette(Prefs.audioTheme(this))[1]
    private fun audioTextColor() = audioPalette(Prefs.audioTheme(this))[2]
    private fun audioIconColor() = audioTextColor()
    private fun audioBarColor() = audioPalette(Prefs.audioTheme(this))[3]
    private fun audioRgbEnabled() = Prefs.audioTheme(this) == "neon" || Prefs.audioTheme(this) == "synthwave" || Prefs.themeMode(this) == "rgb"

    private fun showStyleCenter() {
        val labels = arrayOf(
            if (Prefs.audioTheme(this) == "basic") "✓ Basic (Default)" else "Basic (Default)",
            "Nature Glass", "Neon Pulse", "Vinyl Classic", "Synthwave", "Aurora",
            "Choose Audio Wallpaper", "Remove Audio Wallpaper", "Reset Audio Style"
        )
        AlertDialog.Builder(this).setTitle("Audio Style Center").setItems(labels) { _, which ->
            when (which) {
                0 -> { Prefs.setAudioTheme(this, "basic"); applyAudioStyle() }
                1 -> { Prefs.setAudioTheme(this, "nature"); applyAudioStyle() }
                2 -> { Prefs.setAudioTheme(this, "neon"); applyAudioStyle() }
                3 -> { Prefs.setAudioTheme(this, "vinyl"); applyAudioStyle() }
                4 -> { Prefs.setAudioTheme(this, "synthwave"); applyAudioStyle() }
                5 -> { Prefs.setAudioTheme(this, "aurora"); applyAudioStyle() }
                6 -> {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                    try { startActivityForResult(pick, 741) }
                    catch (_: Exception) { android.widget.Toast.makeText(this, "Image picker unavailable", android.widget.Toast.LENGTH_SHORT).show() }
                }
                7 -> { Prefs.setAudioWall(this, null); applyAudioStyle() }
                8 -> { Prefs.setAudioTheme(this, "basic"); Prefs.setAudioWall(this, null); applyAudioStyle() }
            }
        }.show()
    }

    private fun applyAudioStyle() {
        val palette = audioPalette(Prefs.audioTheme(this))
        val accent = palette[1]
        val text = palette[2]
        if (Prefs.audioTheme(this) == "basic") {
            rootView.setBackgroundColor(palette[0])
        } else {
            rootView.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette[0], palette[3], 0xFF05060B.toInt())
            )
        }
        topBarView.setBackgroundColor(palette[3])
        titleTv.setTextColor(text)
        subTv.setTextColor(text)
        curTv.setTextColor(text)
        durTv.setTextColor(text)
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        playCircle.setColor(accent)
        wallView?.let { rootView.removeView(it) }
        wallDim?.let { rootView.removeView(it) }
        wallView = null
        wallDim = null
        ui.removeCallbacks(rgbTick)
        if (audioRgbEnabled() && rgbTargets.isNotEmpty()) ui.post(rgbTick)
        val wall = Prefs.audioWall(this)
        if (!wall.isNullOrEmpty()) try {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageURI(Uri.parse(wall)) }
            val dim = View(this).apply { setBackgroundColor(0x99000000.toInt()) }
            rootView.addView(iv, 0, FrameLayout.LayoutParams(MP, MP))
            rootView.addView(dim, 1, FrameLayout.LayoutParams(MP, MP))
            wallView = iv
            wallDim = dim
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 741 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            Prefs.setAudioWall(this, uri.toString())
            applyAudioStyle()
        }
    }

    private fun menu() {
        val opts = arrayOf(
            "Equalizer",
            "Audio Boost",
            "Sleep Timer",
            "Songs Queue",
            "Allow Background (Battery)",
            "Stop & Close"
        )
        AlertDialog.Builder(this)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> Dialogs.eq(this)
                    1 -> Dialogs.boost(this)
                    2 -> Dialogs.sleep(this)
                    3 -> queueDialog()
                    4 -> Power.request(this)
                    5 -> {
                        Engine.stopAll(this)
                        finish()
                    }
                    else -> {}
                }
            }
            .show()
    }

    private fun queueDialog() {
        val n = Engine.items.size
        val arr = Array(n) { k ->
            val nm = Engine.names.getOrNull(k) ?: Engine.items[k].lastPathSegment ?: "Track"
            (if (k == Engine.idx) "\u25B6 " else "") + (k + 1) + ". " + nm
        }
        AlertDialog.Builder(this)
            .setTitle("Songs Queue")
            .setItems(arr) { _, i -> Engine.loadAt(this, i) }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("fromVideo", false)) {
            audioModeSession = true
            Prefs.setAudioModeSession(this, true)
        }
        switchingToVideo = false
        Engine.modeSwitching = false
        if (Engine.active) onItem()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        switchingToVideo = false
        Engine.modeSwitching = false
        syncSpeedUi()
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this)
        settingsGearMotion?.stop()
        settingsGearMotion = GearMotion(gearButton).also { it.start() }
        startBackgroundTitleAnimation()
        ui.post(tick)
        ui.removeCallbacks(rgbTick)
        if (rgbTargets.isNotEmpty() && audioRgbEnabled()) ui.post(rgbTick)
        if (Engine.active) onItem()
    }

    override fun onPause() {
        visible = false
        settingsGearMotion?.stop()
        settingsGearMotion = null
        stopBackgroundTitleAnimation()
        super.onPause()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(rgbTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Prefs.audioModeSession(this)) {
            goToVideoList()
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (!switchingToVideo && !isFinishing && !Engine.playerForeground && !Engine.modeSwitching &&
            Prefs.audioModeSession(this) && Engine.active && Engine.isPlaying() && Engine.isVideo) {
            Engine.saveProgress()
            Bg.toAudioOnly()
        }
    }

    override fun onDestroy() {
        visible = false
        // Keep the persisted session flag while background audio continues; Back to Video clears it.
        settingsGearMotion?.stop()
        settingsGearMotion = null
        stopBackgroundTitleAnimation()
        backgroundTitleView = null
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
