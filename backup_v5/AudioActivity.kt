package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

class AudioActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var shufBtn: TextView
    private lateinit var repBtn: TextView
    private lateinit var artImg: ImageView
    private lateinit var artNote: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var userSeeking = false
    private var artKey = ""
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 1

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0 else ms / 1000
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
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cont = intent.getBooleanExtra("cont", false) && Engine.active && !Engine.isVideo
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

        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Prefs.bg(this))
        val w = Prefs.wall(this)
        if (w != null) {
            try {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageURI(Uri.parse(w))
                root.addView(iv, FrameLayout.LayoutParams(MP, MP))
                val dim = View(this)
                dim.setBackgroundColor(0xBB000000.toInt())
                root.addView(dim, FrameLayout.LayoutParams(MP, MP))
            } catch (e: Exception) {
            }
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(36), dp(24), dp(16))

        // ---- top row ----
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(ib("\u2190", 24f, tc) { finish() })
        val hdr = TextView(this)
        hdr.text = "NOW PLAYING"
        hdr.textSize = 13f
        hdr.setTextColor(tc)
        hdr.gravity = Gravity.CENTER
        top.addView(hdr, LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(ib("\u22EF", 24f, tc) { menu() })
        top.addView(ib("\u2715", 20f, tc) {
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

        // ---- title ----
        titleTv = TextView(this)
        titleTv.textSize = 20f
        titleTv.setTextColor(tc)
        titleTv.gravity = Gravity.CENTER
        titleTv.maxLines = 2
        titleTv.ellipsize = TextUtils.TruncateAt.END
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD)
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
        shufBtn = ib("\uD83D\uDD00", 22f, tc) {
            Prefs.setShuffle(this, !Prefs.shuffle(this))
            refreshModes()
        }
        repBtn = ib("\uD83D\uDD01", 22f, tc) {
            Prefs.setRepeat(this, (Prefs.repeat(this) + 1) % 3)
            refreshModes()
        }
        tr.addView(shufBtn)
        tr.addView(ib("\u23EE", 30f, tc) { Engine.prev(this) })
        playBtn = ib("\u23F8", 30f, contrast(ac)) { Engine.toggle(this) }
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(ac)
        playBtn.background = circle
        val plp = LinearLayout.LayoutParams(dp(72), dp(72))
        plp.setMargins(dp(12), 0, dp(12), 0)
        tr.addView(playBtn, plp)
        tr.addView(ib("\u23ED", 30f, tc) { Engine.next(this) })
        tr.addView(repBtn)
        col.addView(tr, LinearLayout.LayoutParams(MP, WC))

        // ---- extras ----
        val ex = LinearLayout(this)
        ex.gravity = Gravity.CENTER
        ex.setPadding(0, dp(12), 0, 0)
        ex.addView(ib("1x", 14f, tc) { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            Engine.mp?.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
        })
        ex.addView(ib("EQ", 14f, tc) { Dialogs.eq(this) })
        ex.addView(ib("Boost", 14f, tc) { Dialogs.boost(this) })
        ex.addView(ib("Sleep", 14f, tc) { Dialogs.sleep(this) })
        ex.addView(ib("Queue", 14f, tc) { queueDialog() })
        col.addView(ex, LinearLayout.LayoutParams(MP, WC))

        root.addView(col, FrameLayout.LayoutParams(MP, MP))
        setContentView(root)

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
        if (!Engine.active) {
            finish()
            return
        }
        titleTv.text = Engine.title
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

    private fun menu() {
        val opts = arrayOf(
            "\uD83C\uDF9A\uFE0F Equalizer",
            "\uD83D\uDD0A Audio Boost",
            "\u23F1\uFE0F Sleep Timer",
            "\uD83D\uDCC3 Songs Queue",
            "\u23F9 Stop & Close"
        )
        AlertDialog.Builder(this)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> Dialogs.eq(this)
                    1 -> Dialogs.boost(this)
                    2 -> Dialogs.sleep(this)
                    3 -> queueDialog()
                    4 -> {
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
        ui.post(tick)
        if (Engine.active) onItem()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
