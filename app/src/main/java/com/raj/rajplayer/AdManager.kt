package com.raj.rajplayer

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {
    // Server-verified premium status yahan update hoga
    @Volatile
    var isPremium: Boolean = false

    // Google official Test Ad Unit IDs (release ke waqt apni real AdMob IDs se replace karein)
    private const val BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    private const val INTERSTITIAL_COOLDOWN_MS = 180_000L

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var lastInterstitialShownAt = 0L
    private var pendingVideoExit = false

    fun init(ctx: Context) {
        if (isPremium || initialized) return
        initialized = true
        try {
            MobileAds.initialize(ctx.applicationContext) {
                loadInterstitial(ctx.applicationContext)
            }
        } catch (_: Exception) { }
    }

    fun attachBanner(activity: Activity, container: FrameLayout) {
        if (isPremium) return
        try {
            if (container.findViewWithTag<View>("raj_banner_ad") != null) return
            val adView = AdView(activity).apply {
                tag = "raj_banner_ad"
                setAdSize(AdSize.BANNER)
                adUnitId = BANNER_ID
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            container.addView(adView, lp)
            adView.bringToFront()
            adView.loadAd(AdRequest.Builder().build())
        } catch (_: Exception) { }
    }

    private fun loadInterstitial(ctx: Context) {
        if (isPremium) return
        try {
            InterstitialAd.load(
                ctx,
                INTERSTITIAL_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                    }
                }
            )
        } catch (_: Exception) { }
    }

    fun markVideoExited() {
        if (!isPremium) pendingVideoExit = true
    }

    fun showPendingInterstitial(activity: Activity) {
        if (isPremium || !pendingVideoExit) return
        pendingVideoExit = false
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShownAt < INTERSTITIAL_COOLDOWN_MS) return
        val ad = interstitial ?: run {
            loadInterstitial(activity.applicationContext)
            return
        }
        lastInterstitialShownAt = now
        interstitial = null
        try {
            ad.show(activity)
        } catch (_: Exception) { }
        loadInterstitial(activity.applicationContext)
    }
}
