package com.raj.rajplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

// Media3 video background me chale to notification + lock-screen controls
@androidx.annotation.OptIn(UnstableApi::class)
class M3Service : Service() {

    companion object {
        var instance: M3Service? = null
        const val CH = "rp_m3"
        const val NID = 2
        const val A_PREV = "com.raj.rajplayer.M3PREV"
        const val A_NEXT = "com.raj.rajplayer.M3NEXT"
        const val A_TOGGLE = "com.raj.rajplayer.M3TOGGLE"
        const val A_CLOSE = "com.raj.rajplayer.M3CLOSE"
        const val A_UPDATE = "com.raj.rajplayer.M3UPDATE"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, M3Service::class.java).setAction(A_UPDATE))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, M3Service::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toggle() {
        val p = M3Holder.player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun next() {
        val p = M3Holder.player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    private fun prev() {
        val p = M3Holder.player ?: return
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Playback (Media3)", NotificationManager.IMPORTANCE_LOW))
        val s = MediaSession(this, "RajPlayerM3")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { M3Holder.player?.play() }
            override fun onPause() { M3Holder.player?.pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(pos: Long) { M3Holder.player?.seekTo(pos) }
            override fun onStop() { closeAll() }
        })
        s.isActive = true
        session = s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action
        if (a == A_PREV) prev()
        else if (a == A_NEXT) next()
        else if (a == A_TOGGLE) toggle()
        else if (a == A_CLOSE) {
            closeAll()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun closeAll() {
        M3Holder.release(this)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    fun refresh() {
        val p = M3Holder.player
        if (p == null) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
            }
            stopSelf()
            return
        }
        updateSession(p)
        Power.wake(this, p.isPlaying)
        try {
            startForeground(NID, build(p), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
        }
    }

    private fun updateSession(p: Player) {
        val s = session ?: return
        val len = if (p.duration > 0) p.duration else 0L
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, M3Holder.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, len)
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (p.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    p.currentPosition, p.playbackParameters.speed
                )
                .build()
        )
    }

    private fun openPi(): PendingIntent {
        val u = M3Holder.currentUri()
        val i = Intent(this, Router.m3Class(this, u))
        i.data = u
        i.putExtra("cont", true)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun act(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, M3Service::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun build(p: Player): Notification {
        val playing = p.isPlaying
        val b = Notification.Builder(this, CH)
        b.setSmallIcon(android.R.drawable.ic_media_play)
        b.setContentTitle(if (M3Holder.title.isEmpty()) "RajPlayer" else M3Holder.title)
        b.setContentText((p.currentMediaItemIndex + 1).toString() + " / " + p.mediaItemCount)
        b.setContentIntent(openPi())
        b.setOngoing(playing)
        b.setVisibility(Notification.VISIBILITY_PUBLIC)
        b.addAction(act(android.R.drawable.ic_media_previous, "Prev", A_PREV))
        b.addAction(
            act(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", A_TOGGLE
            )
        )
        b.addAction(act(android.R.drawable.ic_media_next, "Next", A_NEXT))
        b.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Close", A_CLOSE))
        val st = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        val s = session
        if (s != null) st.setMediaSession(s.sessionToken)
        b.setStyle(st)
        return b.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Power.wake(this, false)
        try {
            session?.release()
        } catch (e: Exception) {
        }
        session = null
    }
}
