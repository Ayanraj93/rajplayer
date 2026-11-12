package com.raj.rajplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

// Media3 player yahan rehta hai, taaki activity band hone par bhi (background) chalta rahe
@androidx.annotation.OptIn(UnstableApi::class)
object M3Holder {
    enum class Dec { HW, HW_PLUS, SW }

    var player: ExoPlayer? = null
    var uris: List<Uri> = emptyList()
    var names: List<String> = emptyList()
    var title = ""
    val swFiles = HashSet<String>()
    val hwFiles = HashSet<String>()
    private var app: Context? = null

    fun currentUri(): Uri? {
        val p = player ?: return null
        return uris.getOrNull(p.currentMediaItemIndex)
    }

    fun clearOverrides() {
        swFiles.clear()
        hwFiles.clear()
    }

    fun decoderFor(c: Context, key: String): Dec {
        if (swFiles.contains(key)) return Dec.SW
        if (hwFiles.contains(key)) return Dec.HW
        return when (Tuning.decMode(c)) {
            "hwplus" -> Dec.HW_PLUS
            "sw" -> Dec.SW
            else -> Dec.HW
        }
    }

    // HW = hardware pehle (fail ho to software), HW+ = sirf hardware, SW = software pehle
    private fun selector(d: Dec): MediaCodecSelector = when (d) {
        Dec.HW -> MediaCodecSelector.DEFAULT
        Dec.HW_PLUS -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).filter { it.hardwareAccelerated }
        }
        Dec.SW -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
        }
    }

    // service / notification ko khabar dene wala listener (activity band ho tab bhi chalta hai)
    private val keep = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            M3Service.instance?.refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val i = p.currentMediaItemIndex
            title = names.getOrNull(i) ?: ""
            val a = app
            val u = uris.getOrNull(i)
            if (a != null && u != null) Prefs.setLastPlayedUri(a, u.toString())
            M3Service.instance?.refresh()
        }
    }

    private fun build(c: Context, d: Dec): ExoPlayer {
        val rf = DefaultRenderersFactory(c)
            .setMediaCodecSelector(selector(d))
            .setEnableDecoderFallback(d != Dec.HW_PLUS)
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 30000, 300, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(c, rf).setLoadControl(lc).build()
        val aa = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        p.setAudioAttributes(aa, true)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(keep)
        return p
    }

    fun open(c: Context, u: List<Uri>, n: List<String>, index: Int, startMs: Long) {
        release(c)
        val a = c.applicationContext
        app = a
        uris = u
        names = n
        val key = u.getOrNull(index)?.toString() ?: ""
        val p = build(a, decoderFor(a, key))
        p.setMediaItems(u.map { MediaItem.fromUri(it) }, index, startMs)
        p.prepare()
        p.playWhenReady = true
        player = p
        title = n.getOrNull(index) ?: ""
    }

    // decoder badalne par naya player, wahi position se
    fun rebuild(c: Context) {
        val old = player ?: return
        val a = c.applicationContext
        val idx = old.currentMediaItemIndex
        val pos = old.currentPosition
        val rep = old.repeatMode
        val shuf = old.shuffleModeEnabled
        val params = old.playbackParameters
        val play = old.playWhenReady
        val key = uris.getOrNull(idx)?.toString() ?: ""
        val p = build(a, decoderFor(a, key))
        old.release()
        p.setMediaItems(uris.map { MediaItem.fromUri(it) }, idx, pos)
        p.repeatMode = rep
        p.shuffleModeEnabled = shuf
        p.playbackParameters = params
        p.prepare()
        p.playWhenReady = play
        player = p
    }

    fun release(c: Context) {
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        M3Service.stop(c)
        Power.wake(c, false)
    }
}
