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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private val mp: MediaPlayer get() = Engine.mp!!

    private lateinit var layout: VLCVideoLayout
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
    private lateinit var fitBtn: TextView
    private lateinit var audioMgr: AudioManager

    private val ui = Handler(Looper.getMainLooper())

    private var locked = false
    private var userSeeking = false
    private var videoPortrait = false
    private var abA = -1L
    private var abB = -1L

    private var gMode = 0
    private var seekTarget = 0L
    private var brightCur = 0.5f
    private var volCur = 0.5f
    private var videoScaleFactor = 1.0f

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3.0f)
    private var speedIdx = 2
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
                if (abA >= 0 && abB > abA && t >= abB) p.setTime(abA)
                if (!userSeeking) curTv.text = fmt(t)
                playBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0 else ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun ib(sym: String, size: Float, f: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = size
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
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

    private fun applyOrientation(uri: Uri) {
        var o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        videoPortrait = false
        try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val hh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                r.release()
                val dw = if (rot == 90 || rot == 270) hh else w
                val dh = if (rot == 90 || rot == 270) w else hh
                if (dw == 0 && dh == 0) {
                    o = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                } else if (dh > dw) {
                    o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    videoPortrait = true
                }
            }
        } catch (e: Exception) {
        }
        requestedOrientation = o
    }

    private fun onItem() {
        if (!Engine.active) {
            finish()
            return
        }
        titleTv.text = Engine.title
        abA = -1L
        abB = -1L
        Orient.setFor(this, Engine.cur()) { videoPortrait = it }
    }

    private fun togglePlay() {
        Engine.toggle(this)
        showControls()
    }

    private fun seekBy(ms: Long) {
        mp.setTime(Math.max(0L, mp.getTime() + ms))
        showMsg(if (ms > 0) "+" + (ms / 1000) + "s" else "" + (ms / 1000) + "s")
    }

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

    private fun endGesture() {
        if (gMode == 3) mp.setTime(seekTarget)
        gMode = 0
        ui.removeCallbacks(hideMsg)
        ui.postDelayed(hideMsg, 600)
    }

    private fun showMenu() {
        val abLabel = if (abA < 0) "A-B Repeat: Set Point A"
        else if (abB < 0) "A-B Repeat: Set Point B"
        else "Disable A-B Repeat"
        val opts = arrayOf(
            "\uD83C\uDF9A\uFE0F Equalizer Presets",
            "\uD83D\uDD0A Audio Boost (Up to 200%)",
            "\u23F1\uFE0F Sleep Timer",
            "\uD83D\uDCC2 Load External Subtitle",
            "\uD83D\uDD16 Add Bookmark",
            "\uD83D\uDCD1 View Bookmarks",
            abLabel,
            "\uD83C\uDFB5 Background Audio: " + (if (Engine.bgAudio) "ON" else "OFF"),
            "\u26A1 Hardware Decoder: " + (if (Prefs.hw(this)) "ON" else "OFF"),
            "\uD83D\uDD32 Picture-in-Picture (PiP)"
        )
        AlertDialog.Builder(this)
            .setTitle("\u26A1 Ultra Options")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> Dialogs.eq(this)
                    1 -> Dialogs.boost(this)
                    2 -> Dialogs.sleep(this)
                    3 -> pickSub()
                    4 -> addBookmark()
                    5 -> bookmarksDialog()
                    6 -> abStep()
                    7 -> {
                        Engine.bgAudio = !Engine.bgAudio
                        Prefs.setBgAudio(this, Engine.bgAudio)
                        showMsg("Background Audio " + (if (Engine.bgAudio) "ON" else "OFF"))
                    }
                    8 -> {
                        Prefs.setHw(this, !Prefs.hw(this))
                        Engine.loadAt(this, Engine.idx)
                        showMsg("HW Decoder " + (if (Prefs.hw(this)) "ON" else "OFF"))
                    }
                    9 -> enterPip()
                    else -> {}
                }
            }
            .show()
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
            showMsg("Subtitle successfully loaded")
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
            Toast.makeText(this, "No bookmarks saved for this media", Toast.LENGTH_SHORT).show()
            return
        }
        val names = l.map { fmt(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved Bookmarks")
            .setItems(names) { _, i ->
                mp.setTime(l[i])
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
                showMsg("Point B set. Loop Active!")
            } else {
                showMsg("Point B must be after Point A")
            }
        } else {
            abA = -1L
            abB = -1L
            showMsg("A-B Repeat Cleared")
        }
    }

    private fun enterPip() {
        if (locked) return
        try {
            val b = PictureInPictureParams.Builder()
                .setAspectRatio(if (videoPortrait) Rational(9, 16) else Rational(16, 9))
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            Toast.makeText(this, "PiP mode not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Engine.mp != null && Engine.isPlaying() && !locked && !Engine.bgAudio) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) hideControls() else showControls()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (locked) {
            showMsg("Screen is locked. Long press lock icon to unlock.")
            return
        }
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cutout.enable(this)
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

        Engine.ensure(this)
        Engine.bgAudio = Prefs.bgAudio(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        audioMgr = getSystemService(AUDIO_SERVICE) as AudioManager

        val ac = Prefs.accent(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        layout = VLCVideoLayout(this)
        root.addView(layout, FrameLayout.LayoutParams(MP, MP))

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

        topBar = LinearLayout(this)
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(0x99000000.toInt())
        topBar.setPadding(dp(20), dp(8), dp(20), dp(8))
        topBar.addView(ib("\u2190", 22f) { finish() })
        titleTv = TextView(this)
        titleTv.setTextColor(Color.WHITE)
        titleTv.textSize = 15f
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))
        topBar.addView(ib("\uD83D\uDD04", 20f) {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            showControls()
        })
        root.addView(topBar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(0x99000000.toInt())
        controls.setPadding(dp(20), dp(4), dp(20), dp(12))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(Color.WHITE)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(Color.WHITE)
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
                mp.setTime((s?.progress ?: 0).toLong())
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
        trRow.addView(ib("-${Prefs.seekSec(this)}s", 15f) {
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
        trRow.addView(ib("+${Prefs.seekSec(this)}s", 15f) {
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
        exRow.addView(ib("1.0x", 14f) { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            mp.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
            showControls()
        })
        fitBtn = ib("Fit", 14f) { b ->
            scaleIdx = (scaleIdx + 1) % scales.size
            mp.setVideoScale(scales[scaleIdx])
            b.text = scaleNames[scaleIdx]
            showControls()
        }
        exRow.addView(fitBtn)
        val repBtn = ib("\uD83D\uDD01", 18f) { b ->
            showMsg(RepeatUi.cycle(this, b))
            showControls()
        }
        repBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        RepeatUi.paint(this, repBtn)
        exRow.addView(repBtn)
        val shufBtn = ib("\uD83D\uDD00", 18f) { b ->
            Modes.setShuffle(this, true, !Modes.shuffle(this, true))
            b.alpha = if (Modes.shuffle(this, true)) 1f else 0.35f
            showMsg(if (Modes.shuffle(this, true)) "Shuffle: On" else "Shuffle: Off")
            showControls()
        }
        shufBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        shufBtn.alpha = if (Modes.shuffle(this, true)) 1f else 0.35f
        exRow.addView(shufBtn)
        exRow.addView(ib("Audio", 14f) {
            pickTrack("Audio Track", mp.getAudioTracks()) { id -> mp.setAudioTrack(id) }
        })
        exRow.addView(ib("Sub", 14f) {
            pickTrack("Subtitles", mp.getSpuTracks()) { id -> mp.setSpuTrack(id) }
        })
        exRow.addView(ib("\u2699\uFE0F", 18f) { showMenu() })
        controls.addView(exRow)
        root.addView(controls, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        lockBtn = ib("\uD83D\uDD13", 22f) { b ->
            if (locked) {
                showMsg("Long press to unlock screen")
            } else {
                locked = true
                b.text = "\uD83D\uDD12"
                topBar.visibility = View.GONE
                controls.visibility = View.GONE
                showMsg("Screen Locked")
            }
            showControls()
        }
        lockBtn.setOnLongClickListener {
            if (locked) {
                locked = false
                lockBtn.text = "\uD83D\uDD13"
                showMsg("Screen Unlocked")
                showControls()
            }
            true
        }
        val llp = FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.CENTER_VERTICAL)
        llp.setMargins(dp(20), 0, 0, 0)
        root.addView(lockBtn, llp)

        setContentView(root)
        Cutout.padControls(root, topBar, controls, lockBtn)

        try {
            mp.detachViews()
        } catch (e: Exception) {
        }
        mp.attachViews(layout, null, true, false)
        Engine.listener = lis

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                videoScaleFactor *= detector.scaleFactor
                videoScaleFactor = videoScaleFactor.coerceIn(0.5f, 3.5f)
                layout.scaleX = videoScaleFactor
                layout.scaleY = videoScaleFactor
                showMsg("Zoom: " + Math.round(videoScaleFactor * 100) + "%")
                return true
            }
        })

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible()) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = touchView.width
                val sMs = (Prefs.seekSec(this@PlayerActivity) * 1000).toLong()
                if (e.x < w / 3f) seekBy(-sMs)
                else if (e.x > w * 2f / 3f) seekBy(sMs)
                else togglePlay()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null) return false
                val w = touchView.width.toFloat()
                val hgt = touchView.height.toFloat()
                if (gMode == 0) {
                    gMode = if (Math.abs(dx) > Math.abs(dy)) 3 else if (e1.x < w / 2f) 1 else 2
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
                    showMsg("\u2600\uFE0F " + Math.round(brightCur * 100) + "%")
                } else if (gMode == 2) {
                    volCur = (volCur + dy / hgt * 1.5f).coerceIn(0f, 1f)
                    val mx = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volCur * mx), 0)
                    showMsg("\uD83D\uDD0A " + Math.round(volCur * 100) + "%")
                }
                return true
            }
        })

        touchView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }

        if (!cont) Engine.setQueue(this, uris, names, index, true) else onItem()
        showControls()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        PlaybackService.stop(this)
    }

    override fun onPause() {
        super.onPause()
        Engine.saveProgress()
        if (Engine.bgAudio && Engine.isPlaying()) PlaybackService.start(this)
    }

    override fun onStop() {
        super.onStop()
        if (!Engine.bgAudio && Engine.isPlaying()) Engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
        try {
            Engine.mp?.detachViews()
        } catch (e: Exception) {
        }
        if (!Engine.bgAudio) {
            Engine.stopAll(this)
        } else if (Engine.active && Engine.isPlaying()) {
            PlaybackService.start(this)
        }
    }
}
