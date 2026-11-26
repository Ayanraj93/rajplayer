package com.raj.rajplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebWatchActivity : Activity() {

    companion object {
        const val EXTRA_URL = "watch_url"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun extractFirstUrl(raw: String): String {
            val m = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(raw.trim())
            return m?.value?.trimEnd('.', ',', ')', ']') ?: raw.trim()
        }

        fun isDirectStreamUrl(url: String): Boolean {
            val low = url.lowercase()
            if (low.startsWith("rtsp://")) return true
            val path = low.substringBefore('?').substringBefore('#')
            return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
                path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp3") ||
                path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".ts")
        }

        fun detectPlatform(url: String): String {
            val low = url.lowercase()
            return when {
                low.contains("youtube.com") || low.contains("youtu.be") -> "youtube"
                low.contains("instagram.com") || low.contains("instagr.am") -> "instagram"
                low.contains("facebook.com") || low.contains("fb.watch") || low.contains("fb.com") -> "facebook"
                else -> "web"
            }
        }

        private fun extractYouTubeId(url: String): String? {
            return try {
                val uri = Uri.parse(url)
                val host = (uri.host ?: "").lowercase()
                if (host.contains("youtu.be")) {
                    uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
                } else {
                    uri.getQueryParameter("v")?.takeIf { it.isNotBlank() } ?: run {
                        val segs = uri.pathSegments
                        val idx = segs.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
                        if (idx >= 0 && idx + 1 < segs.size) segs[idx + 1] else null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun extractInstagramEmbedUrl(url: String): String? {
            return try {
                val uri = Uri.parse(url)
                val segs = uri.pathSegments
                val idx = segs.indexOfFirst { it == "reel" || it == "reels" || it == "p" || it == "tv" }
                if (idx >= 0 && idx + 1 < segs.size) {
                    val kind = if (segs[idx] == "reels") "reel" else segs[idx]
                    val code = segs[idx + 1]
                    "https://www.instagram.com/$kind/$code/embed/"
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var modeBtn: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var originalUrl: String = ""
    private var resolvedUrl: String = ""
    private var platform: String = "web"
    private var useAltMode: Boolean = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        originalUrl = extractFirstUrl(intent?.getStringExtra(EXTRA_URL) ?: intent?.dataString ?: "")
        if (originalUrl.isEmpty()) {
            Toast.makeText(this, "Valid link paste karein", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        resolvedUrl = originalUrl
        platform = detectPlatform(originalUrl)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#11161F"))
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }

        val backBtn = makeTopBtn("\u2190") { finish() }
        titleTv = TextView(this).apply {
            text = when (platform) {
                "youtube" -> "YouTube Watch"
                "facebook" -> "Facebook Watch"
                "instagram" -> "Instagram Watch"
                else -> "Online Watch"
            }
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
        }
        modeBtn = makeTopBtn("Alt View") {
            useAltMode = !useAltMode
            loadCurrentMode()
        }
        val reloadBtn = makeTopBtn("\u21BB") { webView.reload() }

        topBar.addView(backBtn)
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(modeBtn)
        topBar.addView(reloadBtn)
        mainCol.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
        }
        mainCol.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = MOBILE_UA

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)

            // Watch-only: strictly ignore download triggers
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@WebWatchActivity, "Watch only mode", Toast.LENGTH_SHORT).show()
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return false
                    val low = target.lowercase()
                    if (!low.startsWith("http://") && !low.startsWith("https://")) return true
                    if (low.contains("play.google.com/store")) return true
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customCallback = callback
                    fullscreenBox.removeAllViews()
                    fullscreenBox.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenBox.visibility = View.VISIBLE
                    mainCol.visibility = View.GONE
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    setSystemBarsVisible(false)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
        }

        mainCol.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(mainCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fullscreenBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        rootFrame.addView(fullscreenBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        if (platform == "facebook" && (originalUrl.contains("fb.watch", true) || originalUrl.contains("/share/", true))) {
            resolveRedirectThenLoad(originalUrl)
        } else {
            loadCurrentMode()
        }
    }

    private fun makeTopBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun resolveRedirectThenLoad(url: String) {
        Thread {
            var finalUrl = url
            try {
                var cur = url
                repeat(4) {
                    val conn = (URL(cur).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", MOBILE_UA)
                    }
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (code in 300..399 && !loc.isNullOrBlank()) {
                        cur = URL(URL(cur), loc).toString()
                        finalUrl = cur
                    } else {
                        return@repeat
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (!isFinishing) {
                    resolvedUrl = finalUrl
                    loadCurrentMode()
                }
            }
        }.start()
    }

    private fun loadCurrentMode() {
        val target = when (platform) {
            "youtube" -> {
                val id = extractYouTubeId(resolvedUrl)
                if (id != null) {
                    if (!useAltMode) "https://m.youtube.com/watch?v=$id"
                    else "https://www.youtube-nocookie.com/embed/$id?autoplay=1&playsinline=1&rel=0"
                } else {
                    resolvedUrl
                }
            }
            "instagram" -> {
                val embed = extractInstagramEmbedUrl(resolvedUrl)
                if (!useAltMode && embed != null) embed else resolvedUrl
            }
            "facebook" -> {
                if (!useAltMode) {
                    "https://www.facebook.com/plugins/video.php?href=" +
                        URLEncoder.encode(resolvedUrl, "UTF-8") + "&show_text=false&t=0"
                } else {
                    resolvedUrl
                }
            }
            else -> resolvedUrl
        }
        modeBtn.text = if (useAltMode) "Embed" else "Alt View"
        webView.loadUrl(target)
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenBox.removeAllViews()
        fullscreenBox.visibility = View.GONE
        mainCol.visibility = View.VISIBLE
        customView = null
        try { customCallback?.onCustomViewHidden() } catch (_: Exception) {}
        customCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setSystemBarsVisible(true)
    }

    private fun setSystemBarsVisible(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val ctrl = window.insetsController ?: return
            if (show) {
                ctrl.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) {
                View.SYSTEM_UI_FLAG_VISIBLE
            } else {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
