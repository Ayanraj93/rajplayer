package com.raj.rajplayer

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout

object AdManager {
    fun init(ctx: Context) {}
    fun attachBanner(activity: Activity, container: FrameLayout) {}
    fun markVideoExited() {}
    fun showPendingInterstitial(activity: Activity) {}
}
