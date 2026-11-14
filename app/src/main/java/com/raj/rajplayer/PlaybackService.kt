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
import android.os.Build
import android.os.IBinder

class PlaybackService : Service() {

    companion object {
        var instance: PlaybackService? = null
        const val CH = "rp_play"
        const val NID = 1
        const val A_PREV = "com.raj.rajplayer.PREV"
        const val A_NEXT = "com.raj.rajplayer.NEXT"
        const val A_TOGGLE = "com.raj.rajplayer.TOGGLE"
        const val A_CLOSE = "com.raj.rajplayer.CLOSE"
        const val A_UPDATE = "com.raj.rajplayer.UPDATE"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).setAction(A_UPDATE))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        val s = MediaSession(this, "RajPlayer")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (!Engine.isPlaying()) Engine.toggle(this@PlaybackService)
            }
            override fun onPause() {
                if (Engine.isPlaying()) Engine.toggle(this@PlaybackService)
            }
            override fun onSkipToNext() {
                Engine.next(this@PlaybackService)
            }
            override fun onSkipToPrevious() {
                Engine.prev(this@PlaybackService)
            }
            override fun onSeekTo(pos: Long) {
                Engine.seekTo(pos)
                refresh()
            }
            override fun onStop() {
                closeAll()
            }
        })
        s.isActive = true
        session = s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action
        if (a == A_PREV) Engine.prev(this)
        else if (a == A_NEXT) Engine.next(this)
        else if (a == A_TOGGLE) Engine.toggle(this)
        else if (a == A_CLOSE) {
            closeAll()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun closeAll() {
        Engine.stopAll(this)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    fun refresh() {
        updateSession()
        Power.wake(this, Engine.active && Engine.isPlaying())
        Bg.wake(this, Engine.active && Engine.isPlaying())
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NID, build())
            }
        } catch (e: Exception) {
        }
        if (!Engine.active) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
            }
            stopSelf()
        }
    }

    private fun updateSession() {
        val s = session ?: return
        val p = Engine.mp
        val playing = Engine.isPlaying()
        val pos = p?.getTime() ?: 0L
        val len = p?.getLength() ?: 0L
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, Engine.title)
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
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1f)
                .build()
        )
    }

    private fun openPi(): PendingIntent {
        val cls: Class<*> = if (Engine.isVideo) Orient.videoClass(this, Engine.items.getOrNull(Engine.idx)) else AudioActivity::class.java
        val i = Intent(this, cls)
        i.data = Engine.items.getOrNull(Engine.idx)
        i.putExtra("cont", true)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun act(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun build(): Notification {
        val playing = Engine.isPlaying()
        val b = Notification.Builder(this, CH)
        b.setSmallIcon(android.R.drawable.ic_media_play)
        b.setContentTitle(if (Engine.title.isEmpty()) "RajPlayer" else Engine.title)
        b.setContentText(if (Engine.items.isNotEmpty()) (Engine.idx + 1).toString() + " / " + Engine.items.size else "")
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
        Bg.wake(this, false)
        try {
            session?.release()
        } catch (e: Exception) {
        }
        session = null
    }
}
