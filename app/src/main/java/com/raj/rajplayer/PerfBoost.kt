package com.raj.rajplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.view.Surface
import java.lang.ref.WeakReference

object PerfBoost {
    private val main = Handler(Looper.getMainLooper())
    private var uiTid: Int = 0
    @Volatile private var playbackTid: Int = 0
    @Volatile private var playbackActive: Boolean = false
    @Volatile private var isAv1: Boolean = false
    @Volatile private var is4k: Boolean = false
    @Volatile private var videoFps: Float = 0f
    @Volatile private var controlsVisible: Boolean = false
    @Volatile private var touchActive: Boolean = false

    private var activityRef: WeakReference<Activity>? = null
    private var hintSession: Any? = null
    private var sessionTidsKey: String = ""
    private var sessionTargetNs: Long = 0L

    private val touchTimeout = Runnable {
        touchActive = false
        applyNow()
    }

    private val pulseRun = object : Runnable {
        override fun run() {
            val act = activityRef?.get()
            if (act == null || act.isFinishing || act.isDestroyed || !playbackActive) {
                releaseSession()
                return
            }
            if (!isAv1 && !is4k && !touchActive && !controlsVisible) {
                setPrioritySafe(uiTid, Process.THREAD_PRIORITY_DEFAULT)
                releaseSession()
                return
            }
            sendHintPulse(act.applicationContext)
            val delay = if (touchActive || controlsVisible) 220L else if (isAv1) 320L else 450L
            main.postDelayed(this, delay)
        }
    }

    fun setPlaybackThreadId(tid: Int) {
        if (tid > 0) {
            playbackTid = tid
            main.post { applyNow() }
        }
    }

    fun updateVideoProfile(width: Int, height: Int, mime: String?, codecs: String?, fps: Float, decoderName: String?) {
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        is4k = maxDim >= 2160 || minDim >= 1440
        val m = (mime ?: "").lowercase()
        val c = (codecs ?: "").lowercase()
        val d = (decoderName ?: "").lowercase()
        isAv1 = m.contains("av01") || m.contains("av1") || c.startsWith("av01") || d.contains("dav1d") || d.contains("av1")
        if (fps > 1f && fps < 245f) videoFps = fps
        main.post { applyNow() }
    }

    fun updateVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            is4k = maxOf(width, height) >= 2160 || minOf(width, height) >= 1440
            main.post { applyNow() }
        }
    }

    fun updateDecoder(decoderName: String?) {
        val d = (decoderName ?: "").lowercase()
        if (d.contains("dav1d") || d.contains("av1")) {
            isAv1 = true
            main.post { applyNow() }
        }
    }

    fun resetProfile() {
        isAv1 = false
        is4k = false
        videoFps = 0f
        playbackActive = false
        main.post {
            main.removeCallbacks(pulseRun)
            releaseSession()
        }
    }

    fun setPlaying(playing: Boolean) {
        playbackActive = playing
        main.post { applyNow() }
    }

    fun attachActivity(act: Activity) {
        uiTid = Process.myTid()
        activityRef = WeakReference(act)
        applyNow()
    }

    fun detachActivity(act: Activity) {
        if (activityRef?.get() === act) {
            activityRef = null
            touchActive = false
            controlsVisible = false
            main.removeCallbacks(touchTimeout)
            main.removeCallbacks(pulseRun)
            setPrioritySafe(uiTid, Process.THREAD_PRIORITY_DEFAULT)
            resetRefreshRate(act)
            releaseSession()
        }
    }

    fun setControlsVisible(act: Activity, visible: Boolean) {
        uiTid = Process.myTid()
        activityRef = WeakReference(act)
        controlsVisible = visible
        if (visible) {
            touchActive = true
            main.removeCallbacks(touchTimeout)
            main.postDelayed(touchTimeout, 2500L)
        } else {
            touchActive = false
            main.removeCallbacks(touchTimeout)
        }
        applyNow()
    }

    fun onUserTouch(act: Activity) {
        uiTid = Process.myTid()
        activityRef = WeakReference(act)
        touchActive = true
        main.removeCallbacks(touchTimeout)
        main.postDelayed(touchTimeout, 2500L)
        applyNow()
    }

    @SuppressLint("NewApi")
    fun applySurfaceFrameRate(surface: Surface?) {
        if (surface == null || !surface.isValid || Build.VERSION.SDK_INT < 30) return
        val fps = videoFps
        if (fps <= 1f || fps > 240f) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        } catch (_: Throwable) {}
    }

    private fun applyNow() {
        val act = activityRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            main.removeCallbacks(pulseRun)
            releaseSession()
            return
        }
        val activeUi = touchActive || controlsVisible

        // Playback thread priority: strongest for AV1 & 4K, slightly eased when idle on non-AV1
        if (playbackTid > 0) {
            val pbPrio = when {
                isAv1 -> Process.THREAD_PRIORITY_URGENT_AUDIO
                is4k && activeUi -> Process.THREAD_PRIORITY_URGENT_AUDIO
                is4k -> Process.THREAD_PRIORITY_AUDIO
                else -> Process.THREAD_PRIORITY_AUDIO
            }
            setPrioritySafe(playbackTid, pbPrio)
        }

        // UI/Render thread priority: peak on screen touch/controls, eased when controls hide
        val uiPrio = when {
            activeUi && (isAv1 || is4k) -> Process.THREAD_PRIORITY_URGENT_DISPLAY
            activeUi -> Process.THREAD_PRIORITY_DISPLAY
            isAv1 -> Process.THREAD_PRIORITY_DISPLAY
            else -> Process.THREAD_PRIORITY_DEFAULT
        }
        setPrioritySafe(uiTid, uiPrio)

        applyRefreshRate(act, activeUi)

        main.removeCallbacks(pulseRun)
        if (playbackActive && (isAv1 || is4k || activeUi)) {
            sendHintPulse(act.applicationContext)
            main.postDelayed(pulseRun, if (activeUi) 220L else if (isAv1) 320L else 450L)
        } else {
            releaseSession()
        }
    }

    @SuppressLint("NewApi")
    private fun sendHintPulse(appCtx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val phm = appCtx.getSystemService(PerformanceHintManager::class.java) ?: return
            val tids = if (uiTid > 0 && playbackTid > 0 && uiTid != playbackTid) {
                intArrayOf(uiTid, playbackTid)
            } else if (playbackTid > 0) {
                intArrayOf(playbackTid)
            } else if (uiTid > 0) {
                intArrayOf(uiTid)
            } else {
                return
            }
            val activeUi = touchActive || controlsVisible

            // Higher ratio => stronger CPU/GPU governor boost via Android ADPF
            val targetNs: Long
            val actualNs: Long
            when {
                isAv1 && activeUi -> {
                    targetNs = 8_333_333L
                    actualNs = 14_500_000L // Peak boost for AV1 + touch/controls
                }
                isAv1 -> {
                    targetNs = 11_111_111L
                    actualNs = 15_000_000L // Strong sustained boost for AV1 idle watching
                }
                is4k && activeUi -> {
                    targetNs = 8_333_333L
                    actualNs = 12_500_000L // High boost for 4K + touch/controls
                }
                is4k -> {
                    targetNs = 16_666_666L
                    actualNs = 19_800_000L // Moderate-high boost for 4K idle watching
                }
                activeUi -> {
                    targetNs = 8_333_333L
                    actualNs = 10_200_000L // Light UI touch boost for HD/SD
                }
                else -> return
            }

            val key = tids.joinToString(",")
            var sess = hintSession as? PerformanceHintManager.Session
            if (sess == null || key != sessionTidsKey || (Build.VERSION.SDK_INT < 33 && targetNs != sessionTargetNs)) {
                releaseSession()
                sess = phm.createHintSession(tids, targetNs) ?: return
                hintSession = sess
                sessionTidsKey = key
                sessionTargetNs = targetNs
            } else if (Build.VERSION.SDK_INT >= 33 && targetNs != sessionTargetNs) {
                sess.updateTargetWorkDuration(targetNs)
                sessionTargetNs = targetNs
            }
            sess.reportActualWorkDuration(actualNs)
        } catch (_: Throwable) {}
    }

    @SuppressLint("NewApi")
    private fun releaseSession() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (hintSession as? PerformanceHintManager.Session)?.close()
            }
        } catch (_: Throwable) {}
        hintSession = null
        sessionTidsKey = ""
        sessionTargetNs = 0L
    }

    private fun applyRefreshRate(act: Activity, activeUi: Boolean) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val disp = if (Build.VERSION.SDK_INT >= 30) act.display else @Suppress("DEPRECATION") act.windowManager.defaultDisplay
            val cur = disp?.mode ?: return
            val sameRes = disp.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            if (sameRes.isEmpty()) return

            val wantPeak = activeUi || isAv1 || (is4k && videoFps >= 50f)
            val chosen = if (wantPeak) {
                sameRes.maxByOrNull { it.refreshRate }
            } else if (is4k) {
                sameRes.filter { it.refreshRate >= 59f }.minByOrNull { it.refreshRate }
                    ?: sameRes.maxByOrNull { it.refreshRate }
            } else {
                null
            }
            val targetId = chosen?.modeId ?: 0
            val lp = act.window.attributes
            if (lp.preferredDisplayModeId != targetId) {
                lp.preferredDisplayModeId = targetId
                act.window.attributes = lp
            }
        } catch (_: Throwable) {}
    }

    private fun resetRefreshRate(act: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val lp = act.window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                act.window.attributes = lp
            }
        } catch (_: Throwable) {}
    }

    private fun setPrioritySafe(tid: Int, prio: Int) {
        if (tid <= 0) return
        try { Process.setThreadPriority(tid, prio) } catch (_: Throwable) {}
    }
}
