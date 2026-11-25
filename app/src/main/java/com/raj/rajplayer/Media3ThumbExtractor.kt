package com.raj.rajplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
object Media3ThumbExtractor {
    private const val MAX_DIM = 320
    private val exoLock = Any()

    private var ht: HandlerThread? = null
    private var handler: Handler? = null
    private var exo: ExoPlayer? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val idleRelease = Runnable { releaseGlAndExo() }

    private fun cacheFile(ctx: Context, key: String): File {
        val dir = File(ctx.cacheDir, "thumbs_v2")
        if (!dir.exists()) dir.mkdirs()
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.jpg")
    }

    private fun readDisk(ctx: Context, key: String): Bitmap? {
        return try {
            val f = cacheFile(ctx, key)
            if (f.exists() && f.length() > 100L) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (_: Exception) { null }
    }

    private fun writeDisk(ctx: Context, key: String, bmp: Bitmap) {
        try {
            val f = cacheFile(ctx, key)
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        } catch (_: Exception) {}
    }

    fun extract(ctx: Context, uri: Uri, isAudio: Boolean, durMs: Long = 0L): Bitmap? {
        val key = uri.toString()
        readDisk(ctx, key)?.let { return it }

        if (uri.scheme == "content") {
            try {
                val fast = ctx.contentResolver.loadThumbnail(uri, Size(320, 180), null)
                if (fast != null && fast.width > 0 && fast.height > 0) {
                    writeDisk(ctx, key, fast)
                    return fast
                }
            } catch (_: Exception) {}
        }

        val mmr = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) mmr.setDataSource(uri.path)
            else mmr.setDataSource(ctx, uri)

            if (isAudio) {
                val pic = mmr.embeddedPicture
                if (pic != null) {
                    val bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size)
                    if (bmp != null) { writeDisk(ctx, key, bmp); return bmp }
                }
                return null
            }

            val targetUs = if (durMs > 6000L) 2_000_000L else if (durMs > 1500L) (durMs * 1000L) / 3L else 0L
            var bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                try { mmr.getScaledFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) } catch (_: Exception) { null }
                    ?: try { mmr.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) } catch (_: Exception) { null }
            } else null

            if (bmp == null) {
                val raw = try {
                    mmr.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) { null }
                if (raw != null) {
                    val sc = minOf(320f / raw.width.coerceAtLeast(1), 180f / raw.height.coerceAtLeast(1), 1f)
                    val tw = (raw.width * sc).toInt().coerceAtLeast(1)
                    val th = (raw.height * sc).toInt().coerceAtLeast(1)
                    bmp = if (tw < raw.width || th < raw.height) {
                        Bitmap.createScaledBitmap(raw, tw, th, true).also { if (it !== raw) raw.recycle() }
                    } else raw
                }
            }
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                writeDisk(ctx, key, bmp)
                return bmp
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        if (isAudio) return null
        val fallback = extractViaMedia3(ctx.applicationContext, uri, durMs)
        if (fallback != null) writeDisk(ctx, key, fallback)
        return fallback
    }

    private fun extractViaMedia3(appCtx: Context, uri: Uri, durMs: Long): Bitmap? = synchronized(exoLock) {
        if (ht == null) {
            ht = HandlerThread("RajThumbExo").apply { start() }
            handler = Handler(ht!!.looper)
        }
        val h = handler ?: return null
        h.removeCallbacks(idleRelease)

        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val resultRef = AtomicReference<Bitmap?>(null)
        val listenerRef = AtomicReference<Player.Listener?>(null)

        h.post {
            try {
                if (!ensureGlAndExo(appCtx, h)) {
                    done.set(true); latch.countDown(); return@post
                }
                val player = exo ?: run { done.set(true); latch.countDown(); return@post }
                val st = surfaceTexture ?: run { done.set(true); latch.countDown(); return@post }

                fun finish(bmp: Bitmap?) {
                    if (!done.compareAndSet(false, true)) return
                    st.setOnFrameAvailableListener(null)
                    listenerRef.getAndSet(null)?.let { player.removeListener(it) }
                    try { player.stop(); player.clearMediaItems() } catch (_: Exception) {}
                    resultRef.set(bmp)
                    latch.countDown()
                }

                st.setOnFrameAvailableListener({
                    if (done.get()) return@setOnFrameAvailableListener
                    try {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        st.updateTexImage()
                        val vs = player.videoSize
                        val vw = if (vs.width > 0) vs.width else 16
                        val vh = if (vs.height > 0) vs.height else 9
                        val sc = minOf(MAX_DIM.toFloat() / vw, MAX_DIM.toFloat() / vh)
                        val outW = (vw * sc).toInt().coerceIn(32, MAX_DIM)
                        val outH = (vh * sc).toInt().coerceIn(32, MAX_DIM)
                        val bmp = renderOesToBitmap(st, outW, outH)
                        finish(bmp)
                    } catch (_: Exception) {
                        finish(null)
                    }
                }, h)

                val l = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { finish(null) }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) finish(null)
                    }
                }
                listenerRef.set(l)
                player.addListener(l)

                val startPos = if (durMs > 6000L) 2000L else if (durMs > 1500L) durMs / 3L else 0L
                player.setMediaItem(MediaItem.fromUri(uri), startPos)
                player.prepare()
                player.playWhenReady = true
            } catch (_: Exception) {
                if (done.compareAndSet(false, true)) latch.countDown()
            }
        }

        latch.await(5500L, TimeUnit.MILLISECONDS)
        if (!done.get()) {
            h.post {
                if (done.compareAndSet(false, true)) {
                    surfaceTexture?.setOnFrameAvailableListener(null)
                    listenerRef.getAndSet(null)?.let { exo?.removeListener(it) }
                    try { exo?.stop(); exo?.clearMediaItems() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
        }
        h.postDelayed(idleRelease, 6000L)
        return resultRef.get()
    }

    private fun ensureGlAndExo(appCtx: Context, h: Handler): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return false
            val cfg = configs[0] ?: return false
            eglContext = EGL14.eglCreateContext(
                eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, cfg,
                intArrayOf(EGL14.EGL_WIDTH, MAX_DIM, EGL14.EGL_HEIGHT, MAX_DIM, EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            val tids = IntArray(1)
            GLES20.glGenTextures(1, tids, 0)
            texId = tids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val vs = compileShader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 aPos; attribute vec2 aTex; uniform mat4 uMat; varying vec2 vTex; " +
                "void main(){ gl_Position = vec4(aPos,0.0,1.0); vTex = (uMat * vec4(aTex,0.0,1.0)).xy; }")
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; " +
                "varying vec2 vTex; uniform samplerExternalOES sTex; " +
                "void main(){ gl_FragColor = texture2D(sTex, vTex); }")
            glProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(glProgram, vs)
            GLES20.glAttachShader(glProgram, fs)
            GLES20.glLinkProgram(glProgram)

            surfaceTexture = SurfaceTexture(texId).apply { setDefaultBufferSize(MAX_DIM, MAX_DIM) }
            videoSurface = Surface(surfaceTexture)
        }
        if (exo == null) {
            val rf = DefaultRenderersFactory(appCtx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val ts = DefaultTrackSelector(appCtx)
            ts.parameters = ts.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            val lc = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 4000, 100, 200)
                .build()
            exo = ExoPlayer.Builder(appCtx, rf)
                .setTrackSelector(ts)
                .setLoadControl(lc)
                .setLooper(h.looper)
                .build().apply {
                    volume = 0f
                    setVideoSurface(videoSurface)
                }
        }
        return true
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun renderOesToBitmap(st: SurfaceTexture, w: Int, h: Int): Bitmap {
        val mat = FloatArray(16)
        st.getTransformMatrix(mat)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(glProgram)

        val posBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        val texBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
        }
        val aPos = GLES20.glGetAttribLocation(glProgram, "aPos")
        val aTex = GLES20.glGetAttribLocation(glProgram, "aTex")
        val uMat = GLES20.glGetUniformLocation(glProgram, "uMat")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glUniformMatrix4fv(uMat, 1, false, mat, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()

        val raw = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, raw)
        val src = ByteArray(w * h * 4)
        raw.rewind(); raw.get(src)
        val dst = ByteArray(w * h * 4)
        val row = w * 4
        for (y in 0 until h) {
            System.arraycopy(src, (h - 1 - y) * row, dst, y * row, row)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(dst))
        return bmp
    }

    private fun releaseGlAndExo() {
        try { exo?.clearVideoSurface(); exo?.release() } catch (_: Exception) {}
        exo = null
        try { videoSurface?.release() } catch (_: Exception) {}
        videoSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Exception) {}
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        glProgram = 0
        texId = 0
    }
}
