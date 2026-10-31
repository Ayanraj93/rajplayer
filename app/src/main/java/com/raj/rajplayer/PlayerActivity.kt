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
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var vlc: LibVLC
    private lateinit var mp: MediaPlayer
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
    private lateinit var audioMgr: AudioManager

    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())

    private var items: List<Uri> = emptyList()
    private var idx = 0
    private var locked = false
    private var ended = false
    private var userSeeking = false
    private var resumeTo = 0L
    private var bgAudio = false
    private var videoPortrait = false
    private var abA = -1L
    private var abB = -1L

    private var gMode = 0
    private var seekTarget = 0L
    private var brightCur = 0.5f
    private var volCur = 0.5f

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
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
    private val sleepRun = Runnable {
        if (mp.isPlaying) mp.pause()
        saveProgress()
        showMsg("Sleep timer: video roka")
    }

    private val tick = object : Runnable {
        override fun run() {
            val len = mp.getLength()
            val t = mp.getTime()
            if (len > 0) {
                if (resumeTo > 0) {
                    mp.setTime(resumeTo)
                    resumeTo = 0
                }
                if (!userSeeking) {
                    seek.max = len.toInt()
                    seek.progress = t.toInt()
                }
                durTv.text = fmt(len)
            }
            if (abA >= 0 && abB > abA && t >= abB) mp.setTime(abA)
            if (!userSeeking) curTv.text = fmt(t)
            playBtn.text = if (mp.isPlaying) "\u275A\u275A" else "\u25B6"
            ui.postDelayed(this, 500)
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

    private fun nameOf(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (n != null) return n
                }
            }
        } catch (e: Exception) {
        }
        return u.lastPathSegment ?: "Video"
    }

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

    private fun saveProgress() {
        if (!::mp.isInitialized || items.isEmpty()) return
        val len = mp.getLength()
        val t = mp.getTime()
        val key = items[idx].toString()
        if (len > 0 && t > 5000 && t < len - 5000) Prefs.setPos(this, key, t) else Prefs.setPos(this, key, 0L)
    }

    private fun loadAt(i: Int) {
        saveProgress()
        idx = i
        abA = -1L
        abB = -1L
        val uri = items[i]
        titleTv.text = nameOf(uri)
        resumeTo = Prefs.pos(this, uri.toString())
        applyOrientation(uri)
        val oldPfd = pfd
        try {
            val media: Media
            if (uri.scheme == "content") {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                media = Media(vlc, pfd!!.fileDescriptor)
            } else {
                pfd = null
                media = Media(vlc, uri)
            }
            media.setHWDecoderEnabled(Prefs.hw(this), false)
            mp.setMedia(media)
            media.release()
            mp.play()
            ended = false
        } catch (e: Exception) {
            Toast.makeText(this, "File nahi khuli: " + e.message, Toast.LENGTH_LONG).show()
        }
        try {
            oldPfd?.close()
        } catch (e: Exception) {
        }
    }

    private fun onEnded() {
        Prefs.setPos(this, items[idx].toString(), 0L)
        if (idx + 1 < items.size) {
            loadAt(idx + 1)
        } else {
            ended = true
            showControls()
        }
    }

    private fun togglePlay() {
        if (ended) {
            loadAt(idx)
            return
        }
        if (mp.isPlaying) mp.pause() else mp.play()
        showControls()
    }

    private fun seekBy(ms: Long) {
        mp.setTime(Math.max(0L, mp.getTime() + ms))
        showMsg(if (ms > 0) "+" + (ms / 1000) + "s" else "" + (ms / 1000) + "s")
    }

    private fun pickTrack(title: String, tracks: Array<MediaPlayer.TrackDescription>?, set: (Int) -> Unit) {
        if (tracks == null || tracks.isEmpty()) {
            Toast.makeText(this, "Koi track nahi mila", Toast.LENGTH_SHORT).show()
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

    // ---------- premium features ----------
    private fun showMenu() {
        val abLabel = if (abA < 0) "A-B repeat: A set karo"
        else if (abB < 0) "A-B repeat: B set karo"
        else "A-B repeat band karo"
        val opts = arrayOf(
            "Equalizer",
            "Audio boost",
            "Sleep timer",
            "Subtitle file kholo",
            "Bookmark lagao",
            "Bookmarks dekho",
            abLabel,
            "Background audio: " + (if (bgAudio) "ON" else "OFF"),
            "HW decoder: " + (if (Prefs.hw(this)) "ON" else "OFF"),
            "PiP (chhoti window)"
        )
        AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> eqDialog()
                    1 -> boostDialog()
                    2 -> sleepDialog()
                    3 -> pickSub()
                    4 -> addBookmark()
                    5 -> bookmarksDialog()
                    6 -> abStep()
                    7 -> {
                        bgAudio = !bgAudio
                        showMsg("Background audio " + (if (bgAudio) "ON" else "OFF"))
                    }
                    8 -> {
                        Prefs.setHw(this, !Prefs.hw(this))
                        loadAt(idx)
                        showMsg("HW decoder " + (if (Prefs.hw(this)) "ON" else "OFF"))
                    }
                    9 -> enterPip()
                    else -> {}
                }
            }
            .show()
    }

    private fun eqDialog() {
        val n = MediaPlayer.Equalizer.getPresetCount()
        val names = ArrayList<String>()
        names.add("Off")
        for (k in 0 until n) names.add(MediaPlayer.Equalizer.getPresetName(k))
        AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) mp.setEqualizer(null)
                else mp.setEqualizer(MediaPlayer.Equalizer.createFromPreset(i - 1))
                showMsg("Equalizer: " + names[i])
            }
            .show()
    }

    private fun boostDialog() {
        val vals = intArrayOf(100, 125, 150, 175, 200)
        val names = arrayOf("100% (normal)", "125%", "150%", "175%", "200% (max)")
        AlertDialog.Builder(this)
            .setTitle("Audio boost")
            .setItems(names) { _, i ->
                mp.setVolume(vals[i])
                showMsg("Audio boost " + vals[i] + "%")
            }
            .show()
    }

    private fun sleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60)
        val names = arrayOf("Off", "15 min", "30 min", "45 min", "60 min")
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(names) { _, i ->
                ui.removeCallbacks(sleepRun)
                if (mins[i] > 0) {
                    ui.postDelayed(sleepRun, mins[i] * 60000L)
                    showMsg("Sleep timer: " + names[i])
                } else {
                    showMsg("Sleep timer off")
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
            val nm = nameOf(u)
            val ext = nm.substringAfterLast('.', "srt")
            val f = File(cacheDir, "sub_" + System.currentTimeMillis() + "." + ext)
            contentResolver.openInputStream(u)?.use { ins ->
                f.outputStream().use { out -> ins.copyTo(out) }
            }
            mp.addSlave(0, Uri.fromFile(f), true)
            showMsg("Subtitle lag gaya")
        } catch (e: Exception) {
            Toast.makeText(this, "Subtitle nahi laga: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun addBookmark() {
        val key = items[idx].toString()
        val t = mp.getTime()
        Prefs.setBms(this, key, (Prefs.bms(this, key) + t).sorted())
        showMsg("Bookmark: " + fmt(t))
    }

    private fun bookmarksDialog() {
        val key = items[idx].toString()
        val l = Prefs.bms(this, key)
        if (l.isEmpty()) {
            Toast.makeText(this, "Koi bookmark nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val names = l.map { fmt(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(names) { _, i ->
                mp.setTime(l[i])
                showControls()
            }
            .setNeutralButton("Sab hatao") { _, _ -> Prefs.setBms(this, key, emptyList()) }
            .show()
    }

    private fun abStep() {
        if (abA < 0) {
            abA = mp.getTime()
            showMsg("A: " + fmt(abA))
        } else if (abB < 0) {
            val t = mp.getTime()
            if (t > abA) {
                abB = t
                showMsg("B: " + fmt(abB) + " (loop chalu)")
            } else {
                showMsg("B, A ke baad hona chahiye")
            }
        } else {
            abA = -1L
            abB = -1L
            showMsg("A-B repeat band")
        }
    }

    private fun enterPip() {
        if (locked) return
        try {
            val b = PictureInPictureParams.Builder()
                .setAspectRatio(if (videoPortrait) Rational(9, 16) else Rational(16, 9))
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            Toast.makeText(this, "PiP nahi chal paya", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::mp.isInitialized && mp.isPlaying && !locked && !bgAudio) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) hideControls() else showControls()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (locked) {
            showMsg("Locked: unlock ke liye \uD83D\uDD12 ko dabaye rakho")
            return
        }
        super.onBackPressed()
    }

    // ---------- onCreate ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u0 = intent.data
        if (u0 == null) {
            finish()
            return
        }
        val l = intent.getStringArrayListExtra("list")
        items = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
        idx = intent.getIntExtra("idx", 0).coerceIn(0, items.size - 1)

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
        msgTv.textSize = 22f
        msgTv.setTextColor(Color.WHITE)
        msgTv.setBackgroundColor(0xAA000000.toInt())
        msgTv.setPadding(dp(20), dp(12), dp(20), dp(12))
        msgTv.visibility = View.GONE
        root.addView(msgTv, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))

        // ---- top bar ----
        topBar = LinearLayout(this)
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(0x88000000.toInt())
        topBar.setPadding(dp(24), dp(8), dp(24), dp(8))
        topBar.addView(ib("\u2190", 24f) { finish() })
        titleTv = TextView(this)
        titleTv.setTextColor(Color.WHITE)
        titleTv.textSize = 15f
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))
        topBar.addView(ib("\u27F3", 24f) {
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
        controls.setBackgroundColor(0x88000000.toInt())
        controls.setPadding(dp(24), dp(4), dp(24), dp(8))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(Color.WHITE)
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(Color.WHITE)
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
        trRow.addView(ib("\u23EE", 26f) {
            if (idx > 0) loadAt(idx - 1) else Toast.makeText(this, "Pehla video", Toast.LENGTH_SHORT).show()
        })
        trRow.addView(ib("-10", 18f) { seekBy(-10000L); showControls() })
        playBtn = ib("\u275A\u275A", 26f) { togglePlay() }
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(ac)
        playBtn.background = circle
        playBtn.setTextColor(if (Color.luminance(ac) > 0.5f) Color.BLACK else Color.WHITE)
        val plp = LinearLayout.LayoutParams(dp(60), dp(60))
        plp.setMargins(dp(8), 0, dp(8), 0)
        trRow.addView(playBtn, plp)
        trRow.addView(ib("+10", 18f) { seekBy(10000L); showControls() })
        trRow.addView(ib("\u23ED", 26f) {
            if (idx + 1 < items.size) loadAt(idx + 1) else Toast.makeText(this, "Aakhri video", Toast.LENGTH_SHORT).show()
        })
        controls.addView(trRow)

        val exRow = LinearLayout(this)
        exRow.gravity = Gravity.CENTER
        exRow.addView(ib("1x", 15f) { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            mp.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
            showControls()
        })
        exRow.addView(ib("Fit", 15f) { b ->
            scaleIdx = (scaleIdx + 1) % scales.size
            mp.setVideoScale(scales[scaleIdx])
            b.text = scaleNames[scaleIdx]
            showControls()
        })
        exRow.addView(ib("Audio", 15f) {
            pickTrack("Audio track", mp.getAudioTracks()) { id -> mp.setAudioTrack(id) }
        })
        exRow.addView(ib("Sub", 15f) {
            pickTrack("Subtitle", mp.getSpuTracks()) { id -> mp.setSpuTrack(id) }
        })
        exRow.addView(ib("\u22EF", 22f) { showMenu() })
        controls.addView(exRow)
        root.addView(controls, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        // ---- kids lock ----
        lockBtn = ib("\uD83D\uDD13", 24f) { b ->
            if (locked) {
                showMsg("Unlock ke liye icon ko dabaye rakho")
            } else {
                locked = true
                b.text = "\uD83D\uDD12"
                topBar.visibility = View.GONE
                controls.visibility = View.GONE
                showMsg("Locked (kids lock)")
            }
            showControls()
        }
        lockBtn.setOnLongClickListener {
            if (locked) {
                locked = false
                lockBtn.text = "\uD83D\uDD13"
                showMsg("Unlock")
                showControls()
            }
            true
        }
        val llp = FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.CENTER_VERTICAL)
        llp.setMargins(dp(24), 0, 0, 0)
        root.addView(lockBtn, llp)

        setContentView(root)

        // ---- player ----
        vlc = LibVLC(this)
        mp = MediaPlayer(vlc)
        mp.attachViews(layout, null, true, false)
        mp.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EndReached) {
                runOnUiThread { onEnded() }
            }
        }

        // ---- gestures ----
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible()) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = touchView.width
                if (e.x < w / 3f) seekBy(-10000L)
                else if (e.x > w * 2f / 3f) seekBy(10000L)
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
                    showMsg("\u2600 " + Math.round(brightCur * 100) + "%")
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
            gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }

        loadAt(idx)
        showControls()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, PlaybackService::class.java))
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        if (::mp.isInitialized && bgAudio && mp.isPlaying) {
            try {
                startForegroundService(
                    Intent(this, PlaybackService::class.java).putExtra("title", titleTv.text.toString())
                )
            } catch (e: Exception) {
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mp.isInitialized && !bgAudio && mp.isPlaying) mp.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        stopService(Intent(this, PlaybackService::class.java))
        if (::mp.isInitialized) {
            mp.stop()
            mp.detachViews()
            mp.release()
        }
        if (::vlc.isInitialized) vlc.release()
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
    }
}
