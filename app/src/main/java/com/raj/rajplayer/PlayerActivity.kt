package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeTv: TextView
    private lateinit var playBtn: Button
    private var pfd: ParcelFileDescriptor? = null
    private val h = Handler(Looper.getMainLooper())
    private var userSeeking = false

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
    private val scaleNames = listOf("Best fit", "Screen fit", "Fill", "16:9", "4:3", "Original")
    private var scaleIdx = 0

    private val tick = object : Runnable {
        override fun run() {
            val len = mp.getLength()
            val t = mp.getTime()
            if (len > 0 && !userSeeking) {
                seek.max = len.toInt()
                seek.progress = t.toInt()
            }
            timeTv.text = fmt(t) + " / " + fmt(len)
            playBtn.text = if (mp.isPlaying) "\u275A\u275A" else "\u25B6"
            h.postDelayed(this, 500)
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

    private fun pb(t: String, ac: Int, f: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(4), 0, dp(4), 0)
        b.setBackgroundColor(ac)
        b.setTextColor(if (Color.luminance(ac) > 0.5f) Color.BLACK else Color.WHITE)
        b.setOnClickListener { f(b) }
        return b
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideBars()

        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        layout = VLCVideoLayout(this)
        root.addView(layout, FrameLayout.LayoutParams(MP, MP))

        // ---- controls ----
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(0xAA000000.toInt())
        controls.setPadding(dp(12), dp(8), dp(12), dp(8))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ac)
        seek.thumbTintList = ColorStateList.valueOf(ac)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                mp.setTime((s?.progress ?: 0).toLong())
                userSeeking = false
            }
        })
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        timeTv = TextView(this)
        timeTv.setTextColor(tc)
        timeTv.text = "0:00 / 0:00"
        timeTv.setPadding(dp(8), 0, 0, 0)
        seekRow.addView(timeTv)
        controls.addView(seekRow)

        val btnRow = LinearLayout(this)
        btnRow.gravity = Gravity.CENTER
        val lp = { LinearLayout.LayoutParams(0, WC, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) } }

        btnRow.addView(pb("-10s", ac) {
            mp.setTime(Math.max(0L, mp.getTime() - 10000L))
        }, lp())
        playBtn = pb("\u275A\u275A", ac) {
            if (mp.isPlaying) mp.pause() else mp.play()
        }
        btnRow.addView(playBtn, lp())
        btnRow.addView(pb("+10s", ac) {
            mp.setTime(mp.getTime() + 10000L)
        }, lp())
        btnRow.addView(pb("1x", ac) { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            mp.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
        }, lp())
        btnRow.addView(pb("Fit", ac) { b ->
            scaleIdx = (scaleIdx + 1) % scales.size
            mp.setVideoScale(scales[scaleIdx])
            b.text = scaleNames[scaleIdx]
        }, lp())
        btnRow.addView(pb("Audio", ac) {
            pickTrack("Audio track", mp.getAudioTracks()) { id -> mp.setAudioTrack(id) }
        }, lp())
        btnRow.addView(pb("Sub", ac) {
            pickTrack("Subtitle", mp.getSpuTracks()) { id -> mp.setSpuTrack(id) }
        }, lp())
        controls.addView(btnRow)

        root.addView(controls, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))
        layout.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        setContentView(root)

        // ---- player ----
        vlc = LibVLC(this)
        mp = MediaPlayer(vlc)
        mp.attachViews(layout, null, true, false)

        try {
            val media: Media
            if (uri.scheme == "content") {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                media = Media(vlc, pfd!!.fileDescriptor)
            } else {
                media = Media(vlc, uri)
            }
            media.setHWDecoderEnabled(true, false)
            mp.setMedia(media)
            media.release()
            mp.play()
        } catch (e: Exception) {
            Toast.makeText(this, "File nahi khuli: " + e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        if (::mp.isInitialized && mp.isPlaying) mp.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
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
