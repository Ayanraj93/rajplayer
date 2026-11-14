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
    private var wallView: ImageView? = null
    private var wallDim: View? = null

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var userSeeking = false
    private var artKey = ""
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f)
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
                ui.postDelayed(this, 70)
            }
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

    private fun ib(sym: String, size: Float, color: Int, f: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setOnClickListener { f(t) }
        rgbTargets.add { c -> t.setTextColor(c) }
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val backLabel = if (intent.getBooleanExtra("fromVideo", false)) "← Back to Video" else "←"
        top.addView(ib(backLabel, if (intent.getBooleanExtra("fromVideo", false) || Prefs.audioModeSession(this)) 16f else 24f, ic) {
            if (Prefs.audioModeSession(this)) {
                Prefs.setAudioModeSession(this, false)
                audioModeSession = false
            }
            finish()
        })
        val hdr = TextView(this)
        hdr.text = "NOW PLAYING"
        hdr.textSize = 13f
        hdr.setTextColor(tc)
        hdr.gravity = Gravity.CENTER
        top.addView(hdr, LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(ib("\u2699", 22f, ic) { showStyleCenter() }.also { gearButton = it })
        top.addView(ib("\u22EF", 24f, ic) { menu() })
        top.addView(ib("\u2715", 20f, ic) {
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
            speedIdx = (speedIdx + 1) % speeds.size
            Engine.mp?.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
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

    private fun onItem() {
        if (!Engine.active || Engine.items.isEmpty()) {
            finish()
            return
        }
        Prefs.setLastPlayedUri(this, Engine.cur().toString())
        titleTv.text = Engine.title
        speedIdx = 0
        Engine.mp?.setRate(1f)
        if (::speedBtn.isInitialized) speedBtn.text = "1x"
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

    override fun onResume() {
        super.onResume()
        visible = true
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this)
        gearAnimator?.cancel()
        gearAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { gearButton.rotation = it.animatedValue as Float }
            start()
        }
        ui.post(tick)
        ui.removeCallbacks(rgbTick)
        if (rgbTargets.isNotEmpty() && audioRgbEnabled()) ui.post(rgbTick)
        if (Engine.active) onItem()
    }

    override fun onPause() {
        visible = false
        gearAnimator?.cancel()
        gearAnimator = null
        super.onPause()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(rgbTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Prefs.audioModeSession(this)) {
            Prefs.setAudioModeSession(this, false)
            audioModeSession = false
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        visible = false
        // Keep the persisted session flag while background audio continues; Back to Video clears it.
        gearAnimator?.cancel()
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
