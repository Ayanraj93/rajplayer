
package com.raj.rajplayer

import android.net.Uri
import android.view.SurfaceView
import android.graphics.PixelFormat
import androidx.media3.common.VideoSize
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import android.media.audiofx.Equalizer as AndroidEqualizer

/** Compatibility facade for the existing RajPlayer UI. */
@OptIn(UnstableApi::class)
class MediaPlayer(val player: ExoPlayer) {

    data class TrackDescription(val id: Int, val name: String?)

    enum class ScaleType {
        SURFACE_BEST_FIT,
        SURFACE_FIT_SCREEN,
        SURFACE_FILL,
        SURFACE_16_9,
        SURFACE_4_3,
        SURFACE_ORIGINAL
    }

    private var host: FrameLayout? = null
    private var surface: SurfaceView? = null
    private var texture: TextureView? = null
    private var equalizer: AndroidEqualizer? = null
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var scaleType: ScaleType = ScaleType.SURFACE_BEST_FIT

    init {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                adjustSurface()
            }
        })
    }

    val isPlaying: Boolean
        get() = player.isPlaying

    // Keep the old Kotlin property name used by AudioActivity.
    // Custom JVM getter name avoids clash with fun getTime().
    @get:JvmName("getTimeProperty")
    val time: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    fun play() {
        player.playWhenReady = true
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun getTime(): Long {
        return player.currentPosition.coerceAtLeast(0L)
    }

    fun getLength(): Long {
        return player.duration.takeIf { it > 0L } ?: 0L
    }

    fun setTime(ms: Long) {
        player.setSeekParameters(
            androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
        )
        player.seekTo(ms.coerceAtLeast(0L))
    }

    fun setRate(rate: Float) {
        val safe = rate.coerceIn(0.25f, 4f)
        player.setPlaybackParameters(PlaybackParameters(safe, 1f))
    }

    fun setAudioDelay(@Suppress("UNUSED_PARAMETER") delayUs: Long) {
        // Media3 does not expose a general per-item audio delay setter.
    }

    fun setVolume(percent: Int) {
        player.volume =
            (percent.coerceAtMost(100) / 100f).coerceIn(0f, 1f)

        try {
            if (percent > 100) {
                if (loudness == null) {
                    loudness = android.media.audiofx.LoudnessEnhancer(
                        player.audioSessionId
                    )
                }

                loudness?.setTargetGain(
                    ((percent - 100) * 6.0f).toInt()
                )
                loudness?.enabled = true
            } else {
                loudness?.enabled = false
            }
        } catch (_: Exception) {
        }
    }

    fun attachViews(
        container: FrameLayout,
        @Suppress("UNUSED_PARAMETER") subtitles: Any?,
        @Suppress("UNUSED_PARAMETER") useTexture: Boolean,
        grading: Boolean
    ) {
        detachViews()

        host = container
        container.removeAllViews()

        container.addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->
            adjustSurface()
        }

        if (grading) {
            val tv = TextureView(container.context)
            texture = tv

            container.addView(
                tv,
                FrameLayout.LayoutParams(
                    -1,
                    -1,
                    android.view.Gravity.CENTER
                )
            )

            player.setVideoTextureView(tv)
            adjustVideoView(tv)
        } else {
            val sv = SurfaceView(container.context)
            surface = sv

            sv.keepScreenOn = true
            sv.holder.setFormat(PixelFormat.OPAQUE)

            container.addView(
                sv,
                FrameLayout.LayoutParams(
                    -1,
                    -1,
                    android.view.Gravity.CENTER
                )
            )

            player.setVideoSurfaceView(sv)
            adjustSurface()
        }
    }

    fun detachViews() {
        try {
            player.clearVideoSurface()
        } catch (_: Exception) {
        }

        try {
            host?.removeAllViews()
        } catch (_: Exception) {
        }

        surface = null
        texture = null
        host = null
    }

    fun setVideoScale(scale: ScaleType) {
        scaleType = scale

        try {
            player.setVideoScalingMode(
                if (scale == ScaleType.SURFACE_FILL) {
                    C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                } else {
                    C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                }
            )
        } catch (_: Exception) {
        }

        adjustSurface()
    }

    private fun adjustSurface() {
        surface?.let { adjustVideoView(it) }
        texture?.let { adjustVideoView(it) }
    }

    private fun adjustVideoView(view: android.view.View) {
        val parent = host ?: return

        val vw = player.videoSize.width
        val vh = player.videoSize.height

        if (parent.width <= 0 || parent.height <= 0) {
            parent.post {
                if (view.parent === parent) {
                    adjustVideoView(view)
                }
            }
            return
        }

        if (vw <= 0 || vh <= 0) return

        val frameW = parent.width
        val frameH = parent.height

        val aspect = vw.toFloat() / vh.toFloat()
        val frameAspect = frameW.toFloat() / frameH.toFloat()

        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return

        when (scaleType) {
            ScaleType.SURFACE_FILL -> {
                lp.width = frameW
                lp.height = frameH
            }

            ScaleType.SURFACE_16_9 -> {
                val a = 16f / 9f

                if (frameAspect > a) {
                    lp.height = frameH
                    lp.width = (frameH * a).toInt()
                } else {
                    lp.width = frameW
                    lp.height = (frameW / a).toInt()
                }
            }

            ScaleType.SURFACE_4_3 -> {
                val a = 4f / 3f

                if (frameAspect > a) {
                    lp.height = frameH
                    lp.width = (frameH * a).toInt()
                } else {
                    lp.width = frameW
                    lp.height = (frameW / a).toInt()
                }
            }

            ScaleType.SURFACE_ORIGINAL -> {
                lp.width = vw.coerceAtMost(frameW)
                lp.height = vh.coerceAtMost(frameH)
            }

            else -> {
                if (frameAspect > aspect) {
                    lp.height = frameH
                    lp.width = (frameH * aspect).toInt()
                } else {
                    lp.width = frameW
                    lp.height = (frameW / aspect).toInt()
                }
            }
        }

        lp.gravity = android.view.Gravity.CENTER
        view.layoutParams = lp
    }

    private fun tracks(type: Int): Array<TrackDescription> {
        val result = ArrayList<TrackDescription>()

        player.currentTracks.groups.forEachIndexed { gi, group ->
            if (group.type == type) {
                for (ti in 0 until group.length) {
                    val f = group.getTrackFormat(ti)
                    val label = f.label ?: f.language ?: "Track ${ti + 1}"

                    result.add(
                        TrackDescription(gi * 1000 + ti, label)
                    )
                }
            }
        }

        return result.toTypedArray()
    }

    fun getAudioTracks(): Array<TrackDescription> {
        return tracks(C.TRACK_TYPE_AUDIO)
    }

    fun getSpuTracks(): Array<TrackDescription> {
        return tracks(C.TRACK_TYPE_TEXT)
    }

    private fun selectTrack(type: Int, id: Int) {
        val groups = player.currentTracks.groups

        if (id < 0) {
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, true)
                    .build()
            return
        }

        val gi = id / 1000
        val ti = id % 1000

        val group = groups.getOrNull(gi) ?: return

        if (group.type != type || ti !in 0 until group.length) {
            return
        }

        val override = TrackSelectionOverride(
            group.mediaTrackGroup,
            listOf(ti)
        )

        player.trackSelectionParameters =
            player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .clearOverridesOfType(type)
                .addOverride(override)
                .build()
    }

    fun setAudioTrack(id: Int) {
        selectTrack(C.TRACK_TYPE_AUDIO, id)
    }

    fun setSpuTrack(id: Int) {
        selectTrack(C.TRACK_TYPE_TEXT, id)
    }

    fun getVideoTracksCount(): Int {
        return player.currentTracks.groups.count {
            it.type == C.TRACK_TYPE_VIDEO
        }
    }

    fun getVideoTrack(): Int {
        return if (
            player.currentTracks.groups.any { g ->
                g.type == C.TRACK_TYPE_VIDEO &&
                    (0 until g.length).any { g.isTrackSelected(it) }
            }
        ) 0 else -1
    }

    fun setVideoTrack(id: Int) {
        player.trackSelectionParameters =
            player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, id < 0)
                .build()
    }

    fun addSlave(type: Int, uri: Uri, select: Boolean) {
        val old = player.currentMediaItem ?: return

        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "srt")
            ?.lowercase() ?: "srt"

        val mime = when (ext) {
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "application/x-subrip"
        }

        val cfg = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(
                if (select) C.SELECTION_FLAG_DEFAULT else 0
            )
            .build()

        val pos = player.currentPosition
        val wasPlaying = player.isPlaying

        val existing =
            old.localConfiguration?.subtitleConfigurations.orEmpty()

        val updated = old.buildUpon()
            .setSubtitleConfigurations(existing + cfg)
            .build()

        player.setMediaItem(updated, pos)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    fun setEqualizer(preset: Equalizer?) {
        try {
            equalizer?.release()
            equalizer = null

            if (preset != null) {
                val eq = AndroidEqualizer(0, player.audioSessionId)
                eq.enabled = true
                eq.usePreset(preset.index.toShort())
                equalizer = eq
            }
        } catch (_: Exception) {
        }
    }

    data class Equalizer(val index: Int) {
        companion object {
            private val names = arrayOf(
                "Flat",
                "Classical",
                "Dance",
                "Folk",
                "Heavy Metal",
                "Hip Hop",
                "Jazz",
                "Pop",
                "Rock"
            )

            fun getPresetCount(): Int = names.size

            fun getPresetName(i: Int): String {
                return names.getOrElse(i) { "Preset $i" }
            }

            fun createFromPreset(i: Int): Equalizer {
                return Equalizer(i.coerceIn(0, names.lastIndex))
            }
        }
    }
}
