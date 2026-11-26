package com.raj.rajplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder

class WebDownloaderActivity : Activity() {

    companion object {
        const val EXTRA_URL = "media_url"
        const val EXTRA_DOWNLOAD_MODE = "download_mode"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val AD_HOSTS = arrayOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
            "adsterra.com", "mgid.com", "taboola.com", "outbrain.com",
            "monetag.com", "onclickalgo.com", "highperformanceformat.com",
            "profitablegate.com", "adnxs.com", "trafficjunky", "juicyads",
            "bet365", "1xbet", "parimatch", "mostbet", "pushwoosh"
        )

        fun extractFirstUrl(raw: String): String {
            val m = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(raw.trim())
            return m?.value?.trimEnd('.', ',', ')', ']') ?: raw.trim()
        }

        fun isDirectStreamUrl(url: String): Boolean {
            val low = url.lowercase()
            if (low.startsWith("rtsp://")) return true
            val p = low.substringBefore('?').substringBefore('#')
            return p.endsWith(".mp4") || p.endsWith(".mkv") || p.endsWith(".webm") ||
                p.endsWith(".m3u8") || p.endsWith(".mpd") || p.endsWith(".mp3") ||
                p.endsWith(".m4a") || p.endsWith(".aac") || p.endsWith(".ts")
        }

        fun detectPlatform(url: String): String {
            val low = url.lowercase()
            return when {
                low.contains("instagram.com") || low.contains("instagr.am") -> "instagram"
                low.contains("facebook.com") || low.contains("fb.watch") || low.contains("fb.com") -> "facebook"
                low.contains("youtube.com") || low.contains("youtu.be") -> "youtube"
                else -> "web"
            }
        }
    }

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var mediaUrl = ""
    private var platform = "web"
    private var downloadMode = true
    private var filledOnce = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        mediaUrl = extractFirstUrl(intent?.getStringExtra(EXTRA_URL) ?: intent?.dataString ?: "")
        downloadMode = intent?.getBooleanExtra(EXTRA_DOWNLOAD_MODE, true) ?: true
        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Valid link paste karein", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        platform = detectPlatform(mediaUrl)

        if (downloadMode) {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("media_url", mediaUrl))
            } catch (_: Exception) {}
        }

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#11161F"))
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }
        val titleTv = TextView(this).apply {
            text = if (downloadMode) {
                when (platform) {
                    "instagram" -> "IG Download (indown.io)"
                    "facebook" -> "FB Download (fdown.net)"
                    "youtube" -> "YT Download (y2mate)"
                    else -> "Video Downloader"
                }
            } else {
                when (platform) {
                    "instagram" -> "Instagram Watch"
                    "facebook" -> "Facebook Watch"
                    "youtube" -> "YouTube Watch"
                    else -> "Online Watch"
                }
            }
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
        }
        topBar.addView(topBtn("\u2190") { finish() })
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (downloadMode) {
            topBar.addView(topBtn("Auto-Fill") { injectUrlToInput() })
        }
        topBar.addView(topBtn("\u21BB") { filledOnce = false; webView.reload() })
        mainCol.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        mainCol.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = MOBILE_UA

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)

            setDownloadListener { url, ua, disp, mime, _ ->
                if (downloadMode) startSystemDownload(url, ua, disp, mime)
                else Toast.makeText(this@WebDownloaderActivity, "Watch mode active", Toast.LENGTH_SHORT).show()
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val h = request?.url?.host?.lowercase() ?: ""
                    if (AD_HOSTS.any { h.contains(it) }) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    val low = u.lowercase()
                    if (!low.startsWith("http://") && !low.startsWith("https://")) return true
                    val h = request.url?.host?.lowercase() ?: ""
                    if (low.contains("play.google.com/store") || AD_HOSTS.any { h.contains(it) }) return true
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (downloadMode && !filledOnce) {
                        filledOnce = true
                        Handler(Looper.getMainLooper()).postDelayed({ injectUrlToInput() }, 600L)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customView != null) { callback?.onCustomViewHidden(); return }
                    customView = view
                    customCallback = callback
                    fullscreenBox.removeAllViews()
                    fullscreenBox.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    fullscreenBox.visibility = View.VISIBLE
                    mainCol.visibility = View.GONE
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    setSystemBarsVisible(false)
                }

                override fun onHideCustomView() { exitFullscreen() }
            }
        }

        mainCol.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(mainCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        rootFrame.addView(fullscreenBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        val startUrl = if (downloadMode) {
            when (platform) {
                "instagram" -> "https://indown.io/en5"
                "facebook" -> "https://fdown.net/"
                "youtube" -> "https://v42.www-y2mate.com/"
                else -> "https://v42.www-y2mate.com/"
            }
        } else {
            when (platform) {
                "facebook" -> "https://www.facebook.com/plugins/video.php?href=" + URLEncoder.encode(mediaUrl, "UTF-8") + "&show_text=false&t=0"
                else -> mediaUrl
            }
        }
        webView.loadUrl(startUrl)
    }

    private fun topBtn(t: String, f: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(Color.WHITE)
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { f() }
    }

    private fun injectUrlToInput() {
        val q = JSONObject.quote(mediaUrl)
        val js = """
            (function(){
              var u = $q;
              var els = document.querySelectorAll('input[type="url"], input[type="text"], input[name*="url"], input[name*="link"], #txt-url, #link, input.form-control');
              for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (el.offsetParent === null || el.disabled || el.readOnly) continue;
                el.focus();
                el.value = u;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return 'OK';
              }
              return 'NO_INPUT';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (res?.contains("OK") == true) {
                Toast.makeText(this, "Link auto-fill ho gaya, ab Download dabaiye", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Link clipboard par copy hai — box mein Paste karein", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startSystemDownload(url: String, ua: String?, disp: String?, mime: String?) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            Toast.makeText(this, "Direct download link nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            var guessed = URLUtil.guessFileName(url, disp, mime).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (guessed.isBlank() || guessed.endsWith(".bin", true)) {
                guessed = "RajPlayer_${System.currentTimeMillis()}.mp4"
            } else if (!guessed.contains(".")) {
                guessed += if (mime?.startsWith("audio/", true) == true) ".mp3" else ".mp4"
            }
            val fileName = guessed.take(70)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading to RajPlayer")
                .addRequestHeader("User-Agent", ua?.takeIf { it.isNotBlank() } ?: MOBILE_UA)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RajPlayer/$fileName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) req.addRequestHeader("Cookie", cookies)
            webView.url?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("Referer", it) }

            val appCtx = applicationContext
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val dlId = dm.enqueue(req)
            Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()

            val outFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RajPlayer/$fileName")
            val rx = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == dlId) {
                        try { appCtx.unregisterReceiver(this) } catch (_: Exception) {}
                        MediaScannerConnection.scanFile(appCtx, arrayOf(outFile.absolutePath), null, null)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(appCtx, "Saved: Download/RajPlayer/$fileName", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) appCtx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") appCtx.registerReceiver(rx, f)
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenBox.removeAllViews(); fullscreenBox.visibility = View.GONE; mainCol.visibility = View.VISIBLE
        customView = null
        try { customCallback?.onCustomViewHidden() } catch (_: Exception) {}
        customCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setSystemBarsVisible(true)
    }

    private fun setSystemBarsVisible(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (show) c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            else {
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_VISIBLE
            else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() {
        try { webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
