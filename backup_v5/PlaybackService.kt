package com.raj.rajplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("rp_play", "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        val open = Intent(this, PlayerActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "rp_play")
            .setContentTitle("RajPlayer")
            .setContentText(intent?.getStringExtra("title") ?: "Background me chal raha hai")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }
}
