package com.raj.rajplayer

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WebMediaItem(
    val title: String,
    val url: String,
    val uploader: String,
    val durationSec: Long,
    val thumbUrl: String,
    val sourceType: String // "youtube", "instagram", "facebook", "direct"
)

data class ResolvedStream(
    val title: String,
    val streamUrl: String,
    val audioUrl: String? = null,
    val isAudioOnly: Boolean = false
)

object MediaWebExtractor {
    private var initialized = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val npDownloader = object : Downloader() {
        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: NpRequest): NpResponse {
            val builder = Request.Builder().url(request.url())
            for ((k, vals) in request.headers()) {
                for (v in vals) {
                    builder.addHeader(k, v)
                }
            }
            val method = request.httpMethod().uppercase()
            val bodyBytes = request.dataToSend()
            if (method == "POST" || bodyBytes != null) {
                val mediaType = (request.headers()["Content-Type"]?.firstOrNull() ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                val reqBody = (bodyBytes ?: ByteArray(0)).toRequestBody(mediaType)
                builder.method(method, reqBody)
            } else {
                builder.method(method, null)
            }

            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) {
                    throw ReCaptchaException("Rate limited (HTTP 429)", request.url())
                }
                val respBody = resp.body?.string() ?: ""
                val respBytes = respBody.toByteArray(Charsets.UTF_8)
                val headerMap = LinkedHashMap<String, MutableList<String>>()
                for (name in resp.headers.names()) {
                    headerMap[name] = resp.headers(name).toMutableList()
                }
                return NpResponse(
                    resp.code,
                    resp.message,
                    headerMap,
                    respBody,
                    respBytes
                )
            }
        }
    }

    @Synchronized
    fun ensureInit() {
        if (!initialized) {
            try {
                NewPipe.init(npDownloader)
                initialized = true
            } catch (_: Exception) {
            }
        }
    }

    fun detectSource(rawUrl: String): String {
        val u = rawUrl.trim().lowercase()
        return when {
            u.contains("youtube.com") || u.contains("youtu.be") -> "youtube"
            u.contains("instagram.com") || u.contains("instagr.am") -> "instagram"
            u.contains("facebook.com") || u.contains("fb.watch") || u.contains("fb.com") -> "facebook"
            else -> "direct"
        }
    }

    fun searchYouTube(query: String): List<WebMediaItem> {
        ensureInit()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val yt = ServiceList.YouTube
        val searchHandler = yt.searchQHFactory.fromQuery(q)
        val extractor = yt.getSearchExtractor(searchHandler)
        extractor.fetchPage()
        val out = ArrayList<WebMediaItem>()
        for (item in extractor.initialPage.items) {
            if (item is StreamInfoItem) {
                val dur = if (item.duration > 0L) item.duration else 0L
                val thumb = item.thumbnails.maxByOrNull { it.width * it.height }?.url ?: ""
                out.add(
                    WebMediaItem(
                        title = item.name ?: "YouTube Video",
                        url = item.url ?: "",
                        uploader = item.uploaderName ?: "YouTube",
                        durationSec = dur,
                        thumbUrl = thumb,
                        sourceType = "youtube"
                    )
                )
            }
            if (out.size >= 25) break
        }
        return out
    }

    fun resolveYouTube(pageUrl: String, preferAudio: Boolean = false): ResolvedStream {
        ensureInit()
        val yt = ServiceList.YouTube
        val info = StreamInfo.getInfo(yt, pageUrl.trim())
        val title = info.name ?: "YouTube Media"

        if (preferAudio) {
            val bestAudio = info.audioStreams
                ?.filter { !it.content.isNullOrBlank() }
                ?.maxByOrNull { it.averageBitrate }
            if (bestAudio != null) {
                return ResolvedStream(title, bestAudio.content, bestAudio.content, isAudioOnly = true)
            }
        }

        // Prefer progressive video streams with audio first so ExoPlayer plays smoothly out-of-the-box
        val progressive = info.videoStreams
            ?.filter { !it.content.isNullOrBlank() }
            ?.sortedByDescending { it.height }
            ?.firstOrNull()
        if (progressive != null) {
            return ResolvedStream(title, progressive.content, null, isAudioOnly = false)
        }

        val videoOnly = info.videoOnlyStreams
            ?.filter { !it.content.isNullOrBlank() }
            ?.sortedByDescending { it.height }
            ?.firstOrNull()
        val bestAudio = info.audioStreams
            ?.filter { !it.content.isNullOrBlank() }
            ?.maxByOrNull { it.averageBitrate }
        if (videoOnly != null) {
            return ResolvedStream(title, videoOnly.content, bestAudio?.content, isAudioOnly = false)
        }
        if (bestAudio != null) {
            return ResolvedStream(title, bestAudio.content, bestAudio.content, isAudioOnly = true)
        }
        throw IllegalStateException("No playable stream found for this link")
    }

    fun sniffSocialMedia(
        context: Context,
        pageUrl: String,
        timeoutMs: Long = 12_000L,
        onResult: (ResolvedStream?, String?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var finished = false
            var foundUrl: String? = null
            var foundTitle = "Social Video"

            val webView = WebView(context.applicationContext)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

            fun finishOnce(stream: ResolvedStream?, err: String?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {
                }
                onResult(stream, err)
            }

            fun looksLikeMediaUrl(url: String): Boolean {
                val low = url.lowercase()
                if (low.startsWith("blob:") || low.startsWith("data:")) return false
                if (low.contains(".mp4") || low.contains(".m4a") || low.contains(".m3u8")) {
                    if (!low.contains("thumbnail") && !low.contains("poster") && !low.contains("sprite")) {
                        return true
                    }
                }
                return false
            }

            val timeoutRunnable = Runnable {
                val candidate = foundUrl
                if (!candidate.isNullOrBlank()) {
                    finishOnce(ResolvedStream(foundTitle, candidate), null)
                } else {
                    finishOnce(null, "Could not extract public video link (check if post/reel is public)")
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onLoadResource(view: WebView?, url: String?) {
                    super.onLoadResource(view, url)
                    if (finished || url.isNullOrBlank()) return
                    if (looksLikeMediaUrl(url)) {
                        foundUrl = url
                        val pageTitle = view?.title?.trim()
                        if (!pageTitle.isNullOrBlank()) foundTitle = pageTitle
                        finishOnce(ResolvedStream(foundTitle, url), null)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (!finished && looksLikeMediaUrl(reqUrl)) {
                        foundUrl = reqUrl
                        mainHandler.post {
                            val t = view?.title?.trim()
                            if (!t.isNullOrBlank()) foundTitle = t
                            finishOnce(ResolvedStream(foundTitle, reqUrl), null)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (finished) return
                    val t = view?.title?.trim()
                    if (!t.isNullOrBlank()) foundTitle = t
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                if (vids[i].currentSrc && vids[i].currentSrc.indexOf('blob:') !== 0) return vids[i].currentSrc;
                                if (vids[i].src && vids[i].src.indexOf('blob:') !== 0) return vids[i].src;
                                var srcs = vids[i].querySelectorAll('source');
                                for (var j = 0; j < srcs.length; j++) {
                                    if (srcs[j].src && srcs[j].src.indexOf('blob:') !== 0) return srcs[j].src;
                                }
                            }
                            return '';
                        })();
                        """.trimIndent()
                    ) { res ->
                        if (finished) return@evaluateJavascript
                        val cleaned = res?.trim('"')?.replace("\/", "/") ?: ""
                        if (cleaned.isNotEmpty() && !cleaned.startsWith("blob:")) {
                            foundUrl = cleaned
                            finishOnce(ResolvedStream(foundTitle, cleaned), null)
                        }
                    }
                }
            }

            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
            try {
                webView.loadUrl(pageUrl.trim())
            } catch (e: Exception) {
                finishOnce(null, e.message ?: "Failed to load URL")
            }
        }
    }

    fun resolveAnyUrl(
        activity: Activity,
        rawUrl: String,
        preferAudio: Boolean = false,
        onResult: (ResolvedStream?, String?) -> Unit
    ) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            onResult(null, "Paste a valid link first")
            return
        }
        when (detectSource(trimmed)) {
            "youtube" -> {
                Thread {
                    try {
                        val r = resolveYouTube(trimmed, preferAudio)
                        activity.runOnUiThread { onResult(r, null) }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            onResult(null, e.message ?: "YouTube link resolution failed")
                        }
                    }
                }.start()
            }
            "instagram", "facebook" -> {
                sniffSocialMedia(activity, trimmed, 12_000L, onResult)
            }
            else -> {
                onResult(ResolvedStream(trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "Stream" }, trimmed), null)
            }
        }
    }

    fun startDownload(activity: Activity, title: String, streamUrl: String, isAudio: Boolean = false) {
        try {
            val cleanBase = buildString {
                for (ch in title) {
                    if (ch.code >= 32 && "\/:*?"<>|".indexOf(ch) < 0) append(ch)
                }
            }.trim().take(60).ifBlank { "RajPlayer_Media" }
            val ext = when {
                isAudio || streamUrl.contains(".m4a", ignoreCase = true) -> ".m4a"
                streamUrl.contains(".webm", ignoreCase = true) -> ".webm"
                else -> ".mp4"
            }
            val fileName = cleanBase + ext
            val subDir = Environment.DIRECTORY_DOWNLOADS + File.separator + "RajPlayer"

            val request = DownloadManager.Request(Uri.parse(streamUrl))
                .setTitle(fileName)
                .setDescription("Downloading via RajPlayer")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RajPlayer" + File.separator + fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            Toast.makeText(activity, "Downloading: $fileName", Toast.LENGTH_SHORT).show()

            val expectedPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "RajPlayer" + File.separator + fileName
            ).absolutePath

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val doneId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (doneId == downloadId) {
                        try { ctx?.unregisterReceiver(this) } catch (_: Exception) {}
                        MediaScannerConnection.scanFile(ctx, arrayOf(expectedPath), null, null)
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Saved to $subDir/$fileName", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) {
                activity.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                activity.applicationContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
