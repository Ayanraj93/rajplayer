package com.raj.rajplayer

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib

/**
 * Dedicated MPV backend.
 * AV1 routing will be connected to Engine.kt in a later step.
 * Existing Media3 playback remains untouched.
 */
class Av1Engine(context: Context) {

    interface Listener {
        fun onFileLoaded() {}
        fun onPlaybackEnded() {}
        fun onPlaybackRestart() {}
        fun onTimeChanged(positionMs: Long) {}
        fun onDurationChanged(durationMs: Long) {}
        fun onPauseChanged(paused: Boolean) {}
        fun onError(message: String) {}
    }

    private val lock = Any()
    private val appContext = context.applicationContext
    private var mpv: MPVLib? = null
    private var initialized = false
    private var released = false
    private var pendingStartMs = 0L

    @Volatile
    var listener: Listener? = null

    val isInitialized: Boolean
        get() = initialized && !released

    private val observer = object : MPVLib.EventObserver {

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    val start = synchronized(lock) {
                        pendingStartMs.also { pendingStartMs = 0L }
                    }
                    if (start > 0L) {
                        command("seek", (start / 1000.0).toString(), "absolute")
                    }
                    listener?.onFileLoaded()
                }

                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    listener?.onPlaybackEnded()
                }

                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    listener?.onPlaybackRestart()
                }
            }
        }

        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            if (property == "time-pos") {
                listener?.onTimeChanged(value * 1000L)
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" ->
                    listener?.onTimeChanged((value * 1000.0).toLong())

                "duration" ->
                    listener?.onDurationChanged((value * 1000.0).toLong())
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            if (property == "pause") {
                listener?.onPauseChanged(value)
            }
        }

        override fun eventProperty(property: String, value: String) = Unit
    }

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (level <= 2) {
                Log.w("RajPlayer-MPV", "$prefix: $text")
            }
        }
    }

    /**
     * Creates the native MPV instance and initializes its playback core.
     */
    fun initialize(): Boolean = synchronized(lock) {
        if (released) return false
        if (initialized) return true

        try {
            val instance = MPVLib.create(appContext)
            if (instance == null) {
                listener?.onError("MPV native instance creation failed")
                return false
            }

            // Configure options before native initialization.
            instance.setOptionString("config", "no")
            instance.setOptionString("vo", "gpu")
            instance.setOptionString("gpu-context", "android")
            instance.setOptionString("hwdec", "mediacodec-copy")
            instance.setOptionString("osd-level", "0")
            instance.setOptionString("audio-display", "no")

            instance.addObserver(observer)
            instance.addLogObserver(logObserver)
            instance.init()

            mpv = instance
            initialized = true

            instance.observeProperty(
                "time-pos",
                MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
            )
            instance.observeProperty(
                "duration",
                MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
            )
            instance.observeProperty(
                "pause",
                MPVLib.MpvFormat.MPV_FORMAT_FLAG
            )

            true
        } catch (e: Exception) {
            Log.e("RajPlayer-MPV", "Initialization failed", e)
            listener?.onError(e.message ?: "MPV initialization failed")
            false
        }
    }

    fun attachSurface(surface: Surface) {
        synchronized(lock) {
            if (!isInitialized) return
            try {
                mpv?.attachSurface(surface)
            } catch (e: Exception) {
                Log.e("RajPlayer-MPV", "Surface attach failed", e)
                listener?.onError("Video surface attach failed")
            }
        }
    }

    fun detachSurface() {
        synchronized(lock) {
            if (!isInitialized) return
            try {
                mpv?.detachSurface()
            } catch (e: Exception) {
                Log.w("RajPlayer-MPV", "Surface detach failed", e)
            }
        }
    }

    fun load(uri: String, startPositionMs: Long = 0L): Boolean {
        if (uri.isBlank() || !isInitialized) return false

        synchronized(lock) {
            pendingStartMs = startPositionMs.coerceAtLeast(0L)
        }

        return command("loadfile", uri, "replace")
    }

    fun play() {
        setBoolean("pause", false)
    }

    fun pause() {
        setBoolean("pause", true)
    }

    fun togglePause() {
        if (!isInitialized) return
        val paused = mpv?.getPropertyBoolean("pause") ?: false
        setBoolean("pause", !paused)
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) return
        command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    fun getTime(): Long {
        if (!isInitialized) return 0L
        return ((mpv?.getPropertyDouble("time-pos") ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)
    }

    fun getDuration(): Long {
        if (!isInitialized) return 0L
        return ((mpv?.getPropertyDouble("duration") ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)
    }

    fun isPlaying(): Boolean {
        if (!isInitialized) return false
        return mpv?.getPropertyBoolean("pause") == false
    }

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        setDouble("speed", speed.coerceIn(0.25f, 6.0f).toDouble())
    }

    fun setVolume(percent: Int) {
        setDouble("volume", percent.coerceIn(0, 150).toDouble())
    }

    fun setAudioDelay(delayMs: Long) {
        setDouble("audio-delay", delayMs.coerceIn(-10000L, 10000L) / 1000.0)
    }

    private fun setBoolean(name: String, value: Boolean) {
        synchronized(lock) {
            if (!isInitialized) return
            try {
                mpv?.setPropertyBoolean(name, value)
            } catch (e: Exception) {
                Log.e("RajPlayer-MPV", "Property $name failed", e)
            }
        }
    }

    private fun setDouble(name: String, value: Double) {
        synchronized(lock) {
            if (!isInitialized) return
            try {
                mpv?.setPropertyDouble(name, value)
            } catch (e: Exception) {
                Log.e("RajPlayer-MPV", "Property $name failed", e)
            }
        }
    }

    private fun command(vararg args: String): Boolean {
        synchronized(lock) {
            if (!isInitialized) return false
            return try {
                mpv?.commandWithResult(args) == 0
            } catch (e: Exception) {
                Log.e("RajPlayer-MPV", "Command failed: ${args.firstOrNull()}", e)
                false
            }
        }
    }

    /**
     * Releases only this MPV instance.
     * Media3 and other RajPlayer components are not affected.
     */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true

            val instance = mpv
            mpv = null
            initialized = false

            if (instance != null) {
                try {
                    instance.removeObserver(observer)
                    instance.removeLogObserver(logObserver)
                    instance.detachSurface()
                    instance.destroy()
                } catch (e: Exception) {
                    Log.w("RajPlayer-MPV", "Release warning", e)
                }
            }
        }
    }
}
