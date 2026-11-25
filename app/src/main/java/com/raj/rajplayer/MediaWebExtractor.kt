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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.media3.datasource.DefaultHttpDataSource
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
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
    val sourceType: String
)

data class ResolvedStream(
    val title: String,
    val streamUrl: String,
    val audioUrl: String? = null,
    val isAudioOnly: Boolean = false,
    val userAgent: String = MediaWebExtractor.MOBILE_CHROME_UA,
    val referer: String = ""
)

object MediaWebExtractor {
    const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_YT_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; en_IN)"

    val sharedHttpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(MOBILE_CHROME_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(25_000)

    fun setActiveStreamHeaders(userAgent: String, referer: String = "") {
        sharedHttpFactory.setUserAgent(userAgent.ifBlank { MOBILE_CHROME_UA })
        if (referer.isNotBlank()) {
            sharedHttpFactory.setDefaultRequestProperties(mapOf("Referer" to referer))
        } else {
            sharedHttpFactory.setDefaultRequestProperties(emptyMap())
        }
    }

    private var initialized = false
    @Volatile
    private var cachedVisitorData: String? = null

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
                val mediaType = (request.headers()["Content-Type"]?.firstOrNull()
                    ?: "application/json; charset=utf-8").toMediaTypeOrNull()
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
                val headerMap = LinkedHashMap<String, MutableList<String>>()
                for (name in resp.headers.names()) {
                    headerMap[name] = resp.headers(name).toMutableList()
                }
                return NpResponse(
                    resp.code,
                    resp.message,
                    headerMap,
                    respBody,
                    resp.request.url.toString()
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

    fun extractYouTubeVideoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(trimmed)) return trimmed
        return try {
            val uri = Uri.parse(trimmed)
            val host = (uri.host ?: "").lowercase()
            if (host.contains("youtu.be")) {
                uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            } else {
                val vParam = uri.getQueryParameter("v")
                if (!vParam.isNullOrBlank()) return vParam
                val segs = uri.pathSegments
                val markerIdx = segs.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
                if (markerIdx >= 0 && markerIdx + 1 < segs.size) segs[markerIdx + 1] else null
            }
        } catch (_: Exception) {
            null
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

    private fun fetchVisitorData(): String? {
        cachedVisitorData?.let { if (it.isNotBlank()) return it }
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", MOBILE_CHROME_UA)
                .header("Accept-Language", "en-IN,en;q=0.9")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val m = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(text)
                val vd = m?.groupValues?.getOrNull(1)
                if (!vd.isNullOrBlank()) {
                    cachedVisitorData = vd
                    return vd
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun queryInnerTubePlayer(
        videoId: String,
        clientId: String,
        clientVersion: String,
        userAgent: String,
        clientObj: JSONObject,
        preferAudio: Boolean
    ): ResolvedStream? {
        val visitorData = fetchVisitorData()
        if (!visitorData.isNullOrBlank()) {
            clientObj.put("visitorData", visitorData)
        }
        val contextObj = JSONObject().apply {
            put("client", clientObj)
        }
        val playbackCtx = JSONObject().apply {
            put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS"))
        }
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", contextObj)
            put("playbackContext", playbackCtx)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/watch?v=$videoId")
        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        val req = reqBuilder
            .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val json = JSONObject(bodyStr)
            val status = json.optJSONObject("playabilityStatus")?.optString("status") ?: ""
            if (status != "OK") return null

            val title = json.optJSONObject("videoDetails")?.optString("title")
                ?.takeIf { it.isNotBlank() } ?: "YouTube Video"
            val streaming = json.optJSONObject("streamingData") ?: return null

            var bestProgUrl: String? = null
            var bestProgHeight = -1
            val formats = streaming.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val u = f.optString("url")
                    val mime = f.optString("mimeType")
                    val h = f.optInt("height", 0)
                    if (u.isNotBlank() && mime.contains("video/mp4") && h >= bestProgHeight) {
                        bestProgHeight = h
                        bestProgUrl = u
                    }
                }
            }

            var bestAudioUrl: String? = null
            var bestAudioBitrate = -1
            var bestAdaptiveVideoUrl: String? = null
            var bestAdaptiveHeight = -1
            val adaptive = streaming.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val u = f.optString("url")
                    if (u.isBlank()) continue
                    val mime = f.optString("mimeType")
                    if (mime.startsWith("audio/mp4") || mime.startsWith("audio/")) {
                        val br = f.optInt("bitrate", 0)
                        if (br >= bestAudioBitrate) {
                            bestAudioBitrate = br
                            bestAudioUrl = u
                        }
                    } else if (mime.startsWith("video/mp4")) {
                        val h = f.optInt("height", 0)
                        if (h >= bestAdaptiveHeight) {
                            bestAdaptiveHeight = h
                            bestAdaptiveVideoUrl = u
                        }
                    }
                }
            }

            if (preferAudio && !bestAudioUrl.isNullOrBlank()) {
                return ResolvedStream(title, bestAudioUrl, bestAudioUrl, true, userAgent, "https://www.youtube.com/")
            }
            if (!bestProgUrl.isNullOrBlank()) {
                return ResolvedStream(title, bestProgUrl, bestAudioUrl, false, userAgent, "https://www.youtube.com/")
            }
            val hlsUrl = streaming.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank()) {
                return ResolvedStream(title, hlsUrl, bestAudioUrl, false, userAgent, "https://www.youtube.com/")
            }
            if (!bestAdaptiveVideoUrl.isNullOrBlank()) {
                return ResolvedStream(title, bestAdaptiveVideoUrl, bestAudioUrl, false, userAgent, "https://www.youtube.com/")
            }
            return null
        }
    }

    fun resolveYouTubeViaInnerTube(pageUrl: String, preferAudio: Boolean = false): ResolvedStream? {
        val videoId = extractYouTubeVideoId(pageUrl) ?: return null

        // 1. VISIONOS client (returns full-length progressive MP4 URLs without PO token)
        try {
            val visionClient = JSONObject().apply {
                put("clientName", "VISIONOS")
                put("clientVersion", "0.1")
                put("deviceMake", "Apple")
                put("deviceModel", "RealityDevice14,1")
                put("osName", "visionOS")
                put("osVersion", "2.0")
                put("hl", "en")
                put("gl", "IN")
            }
            val r0 = queryInnerTubePlayer(videoId, "101", "0.1", VISIONOS_UA, visionClient, preferAudio)
            if (r0 != null) return r0
        } catch (_: Exception) {
        }

        // 2. ANDROID_VR 1.65.10 client
        try {
            val vrClient = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.65.10")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32)
                put("osName", "Android")
                put("osVersion", "12L")
                put("hl", "en")
                put("gl", "IN")
            }
            val r1 = queryInnerTubePlayer(videoId, "28", "1.65.10", ANDROID_VR_UA, vrClient, preferAudio)
            if (r1 != null) return r1
        } catch (_: Exception) {
        }

        // 3. IOS client
        try {
            val iosClient = JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "19.45.4")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.1.0.22B83")
                put("hl", "en")
                put("gl", "IN")
            }
            val r2 = queryInnerTubePlayer(videoId, "5", "19.45.4", IOS_YT_UA, iosClient, preferAudio)
            if (r2 != null) return r2
        } catch (_: Exception) {
        }

        // 4. MWEB client
        try {
            val mwebClient = JSONObject().apply {
                put("clientName", "MWEB")
                put("clientVersion", "2.20260205.04.01")
                put("hl", "en")
                put("gl", "IN")
            }
            val r3 = queryInnerTubePlayer(videoId, "2", "2.20260205.04.01", MOBILE_CHROME_UA, mwebClient, preferAudio)
            if (r3 != null) return r3
        } catch (_: Exception) {
        }

        return null
    }

    private fun stripRangeParams(url: String): String {
        return url
            .replace(Regex("([?&])range=[^&]*"), "$1")
            .replace(Regex("([?&])bytestart=[^&]*"), "$1")
            .replace(Regex("([?&])byteend=[^&]*"), "$1")
            .replace("?&", "?")
            .replace("&&", "&")
            .trimEnd('?', '&')
    }

    fun sniffWebMedia(
        context: Context,
        pageUrl: String,
        isYouTubeFallback: Boolean = false,
        timeoutMs: Long = 16_000L,
        onResult: (ResolvedStream?, String?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var finished = false
            var foundVideoUrl: String? = null
            var foundAudioUrl: String? = null
            var foundTitle = if (isYouTubeFallback) "YouTube Video" else "Social Video"
            var switchedToMobileWatch = false

            val webView = WebView(context.applicationContext)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = MOBILE_CHROME_UA

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

            fun evaluateCandidate(rawUrl: String, viewTitle: String?) {
                if (finished || rawUrl.isBlank()) return
                val low = rawUrl.lowercase()
                if (low.startsWith("blob:") || low.startsWith("data:")) return
                if (low.contains(".jpg") || low.contains(".jpeg") || low.contains(".png") ||
                    low.contains(".webp") || low.contains("thumbnail") || low.contains("poster") ||
                    low.contains("sprite") || low.contains("init.mp4")
                ) return

                if (!viewTitle.isNullOrBlank() && viewTitle != "YouTube" && viewTitle != "Instagram" && viewTitle != "Facebook") {
                    foundTitle = viewTitle.trim()
                }

                if (low.contains("googlevideo.com/videoplayback")) {
                    val clean = stripRangeParams(rawUrl)
                    if (low.contains("mime=audio") || low.contains("mime%3daudio")) {
                        foundAudioUrl = clean
                    } else {
                        foundVideoUrl = clean
                        if (low.contains("itag=18") || low.contains("itag=22") || low.contains("mime=video%2fmp4") || low.contains("mime=video/mp4")) {
                            finishOnce(
                                ResolvedStream(foundTitle, clean, foundAudioUrl, false, MOBILE_CHROME_UA, "https://www.youtube.com/"),
                                null
                            )
                        }
                    }
                    return
                }

                val isSocialCdn = low.contains("fbcdn.net") || low.contains("cdninstagram.com")
                val isDirectMedia = low.contains(".mp4") || low.contains(".m4a") || low.contains(".m3u8")
                if (isDirectMedia || (isSocialCdn && (low.contains("/v/") || low.contains("video")))) {
                    val clean = stripRangeParams(rawUrl)
                    foundVideoUrl = clean
                    finishOnce(
                        ResolvedStream(foundTitle, clean, null, false, MOBILE_CHROME_UA, pageUrl),
                        null
                    )
                }
            }

            val pollJsRunnable = object : Runnable {
                override fun run() {
                    if (finished) return
                    val currentTitle = webView.title
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
                            if (bodyTxt.indexOf('Video unavailable') >= 0 || bodyTxt.indexOf('Playback on other websites') >= 0) {
                                return 'EMBED_BLOCKED';
                            }
                            var btns = document.querySelectorAll('button, [role="button"], .ytp-large-play-button');
                            for (var b = 0; b < btns.length; b++) {
                                var txt = (btns[b].innerText || btns[b].getAttribute('aria-label') || '').toLowerCase();
                                if (btns[b].classList.contains('ytp-large-play-button') || txt === 'play' || txt.indexOf('play video') >= 0) {
                                    try { btns[b].click(); } catch(e) {}
                                }
                            }
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                try { vids[i].muted = true; vids[i].play(); } catch(e) {}
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
                        val cleaned = (res ?: "").trim('"').replace("\\/", "/")
                        if (cleaned == "EMBED_BLOCKED" && isYouTubeFallback && !switchedToMobileWatch) {
                            switchedToMobileWatch = true
                            val vidId = extractYouTubeVideoId(pageUrl)
                            if (vidId != null) {
                                webView.loadUrl("https://m.youtube.com/watch?v=$vidId")
                            }
                            return@evaluateJavascript
                        }
                        if (cleaned.isNotEmpty() && cleaned != "EMBED_BLOCKED" && !cleaned.startsWith("blob:")) {
                            evaluateCandidate(cleaned, currentTitle)
                        }
                    }
                    mainHandler.postDelayed(this, 850L)
                }
            }

            val timeoutRunnable = Runnable {
                val vid = foundVideoUrl
                val aud = foundAudioUrl
                when {
                    !vid.isNullOrBlank() -> finishOnce(
                        ResolvedStream(foundTitle, vid, aud, false, MOBILE_CHROME_UA, pageUrl),
                        null
                    )
                    !aud.isNullOrBlank() -> finishOnce(
                        ResolvedStream(foundTitle, aud, aud, true, MOBILE_CHROME_UA, pageUrl),
                        null
                    )
                    else -> finishOnce(
                        null,
                        if (isYouTubeFallback) "YouTube video could not be loaded (check internet or try another video)"
                        else "Could not extract public video (ensure Instagram/Facebook post or reel is public)"
                    )
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onLoadResource(view: WebView?, url: String?) {
                    super.onLoadResource(view, url)
                    if (!finished && !url.isNullOrBlank()) {
                        evaluateCandidate(url, view?.title)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (!finished && reqUrl.isNotEmpty()) {
                        mainHandler.post {
                            evaluateCandidate(reqUrl, view?.title)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            mainHandler.postDelayed(pollJsRunnable, 1000L)
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
            try {
                val targetUrl = if (isYouTubeFallback) {
                    val vidId = extractYouTubeVideoId(pageUrl)
                    if (vidId != null) "https://www.youtube.com/embed/$vidId?autoplay=1&mute=1&playsinline=1"
                    else pageUrl.trim()
                } else {
                    pageUrl.trim()
                }
                webView.loadUrl(targetUrl)
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
                    val innerTubeResult = resolveYouTubeViaInnerTube(trimmed, preferAudio)
                    if (innerTubeResult != null) {
                        activity.runOnUiThread { onResult(innerTubeResult, null) }
                    } else {
                        sniffWebMedia(activity, trimmed, isYouTubeFallback = true, timeoutMs = 16_000L, onResult = onResult)
                    }
                }.start()
            }
            "instagram", "facebook" -> {
                sniffWebMedia(activity, trimmed, isYouTubeFallback = false, timeoutMs = 16_000L, onResult = onResult)
            }
            else -> {
                onResult(
                    ResolvedStream(
                        trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "Stream" },
                        trimmed
                    ),
                    null
                )
            }
        }
    }

    fun startDownload(activity: Activity, resolved: ResolvedStream) {
        try {
            val badChars = "\\/:*?\"<>|"
            val cleanBase = buildString {
                for (ch in resolved.title) {
                    if (ch.code >= 32 && badChars.indexOf(ch) < 0) append(ch)
                }
            }.trim().take(60).ifBlank { "RajPlayer_Media" }
            val ext = when {
                resolved.isAudioOnly || resolved.streamUrl.contains(".m4a", ignoreCase = true) -> ".m4a"
                resolved.streamUrl.contains(".webm", ignoreCase = true) -> ".webm"
                else -> ".mp4"
            }
            val fileName = cleanBase + ext
            val subDir = Environment.DIRECTORY_DOWNLOADS + File.separator + "RajPlayer"

            val request = DownloadManager.Request(Uri.parse(resolved.streamUrl))
                .setTitle(fileName)
                .setDescription("Downloading via RajPlayer")
                .addRequestHeader("User-Agent", resolved.userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "RajPlayer" + File.separator + fileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            if (resolved.referer.isNotBlank()) {
                request.addRequestHeader("Referer", resolved.referer)
            }

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
