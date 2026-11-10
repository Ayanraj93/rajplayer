package com.raj.rajplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class V(val name: String, val uri: Uri, val dur: Long, val size: Long, val folder: String, val date: Long)
    data class Item(val title: String, val sub: String, val uri: Uri?, val badge: String, val tag: String)
    class H(val check: TextView, val thumb: ImageView, val badge: TextView, val title: TextView, val sub: TextView)

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var frame: FrameLayout
    private lateinit var listHolder: FrameLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: TextView
    private var selCountTv: TextView? = null
    private var curAdapter: BaseAdapter? = null
    private var atomic: AtomicView? = null
    private var settingsSv: ScrollView? = null
    private var settingsY = 0

    @Volatile private var videos: List<V> = emptyList()
    @Volatile private var audios: List<V> = emptyList()
    @Volatile private var loading = false
    private var shown: List<V> = emptyList()
    private var inSettings = false
    private var asked = false
    private var dirty = true
    private var tab = 0
    private var openFolder: String? = null
    private var searchOn = false
    private var query = ""
    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    private val rgbTargets = ArrayList<(Int) -> Unit>()

    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(3)
    private val loader = Executors.newSingleThreadExecutor()

    private val palette = listOf(
        "#000000", "#0B0F14", "#121820", "#1A222D", "#1E1E2E", "#2D1B36", "#0F2A3F", "#FFFFFF",
        "#F5F7FA", "#FFD700", "#FF9800", "#FF5252", "#FF4081", "#E040FB", "#7C4DFF", "#448AFF",
        "#2979FF", "#00E5FF", "#00E676", "#76FF03"
    )

    private val miniTick = object : Runnable {
        override fun run() {
            refreshMini()
            ui.postDelayed(this, 700)
        }
    }

    private val rgbTick = object : Runnable {
        override fun run() {
            if (rgbTargets.isNotEmpty()) {
                val col = Theme.rgbNow(0f)
                for (t in rgbTargets) t(col)
                ui.postDelayed(this, 70)
            }
        }
    }

    // ================= lifecycle =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
        Engine.bgAudio = Prefs.bgAudio(this)
        if (handleIncoming(intent)) finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!hasPerm() && !asked) {
            asked = true
            reqPerms()
        }
        dirty = true
        if (inSettings) refreshSettings() else showHome()
        ui.post(miniTick)
        if (hasPerm()) Power.askOnce(this)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(miniTick)
        ui.removeCallbacks(rgbTick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        dirty = true
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectMode) {
            exitSelect()
        } else if (inSettings) {
            settingsY = 0
            showHome()
        } else if (openFolder != null) {
            openFolder = null
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9) {
            if (resultCode == RESULT_OK) afterDelete() else toast("Delete cancelled")
            return
        }
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 2) openUri(u, null)
        if (requestCode == 3) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Prefs.setWall(this, u.toString())
            refreshSettings()
        }
    }

    // ================= share / open-with =================
    @Suppress("DEPRECATION")
    private fun handleIncoming(i: Intent?): Boolean {
        if (i == null) return false
        var u: Uri? = null
        if (i.action == Intent.ACTION_SEND) {
            u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val cd = i.clipData
            if (u == null && cd != null && cd.itemCount > 0) u = cd.getItemAt(0).uri
        } else if (i.action == Intent.ACTION_VIEW) {
            u = i.data
        }
        if (u == null) return false
        openUri(u, i.type)
        return true
    }

    private fun looksAudio(u: Uri): Boolean {
        val n = (u.lastPathSegment ?: "").lowercase()
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") ||
                n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".aac") || n.endsWith(".opus")
    }

    private fun openUri(u: Uri, mime: String?) {
        val t = mime ?: contentResolver.getType(u) ?: ""
        val aud = t.startsWith("audio/") || (t.isEmpty() && looksAudio(u))
        val cls: Class<*> = if (aud) AudioActivity::class.java else Orient.videoClass(this, u)
        val i = Intent(this, cls)
        i.data = u
        i.clipData = ClipData.newRawUri("media", u)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    // ================= helpers =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun perm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun audioPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm() =
        checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED

    private fun reqPerms() {
        val l = ArrayList<String>()
        l.add(perm())
        if (audioPerm() != perm()) l.add(audioPerm())
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

    private fun openAppSettings() {
        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        i.data = Uri.fromParts("package", packageName, null)
        startActivity(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE
    private fun isAudio(u: Uri) = u.toString().contains("/audio/")

    private fun label(t: String, size: Float, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setPadding(0, dp(8), 0, dp(8))
        return v
    }

    private fun button(t: String, ac: Int, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setBackgroundColor(ac)
        b.setTextColor(contrast(ac))
        b.setOnClickListener { f() }
        return b
    }

    private fun sym(t: String, color: Int, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 22f
        v.setTextColor(color)
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.setOnClickListener { f() }
        return v
    }

    private fun tabBtn(t: String, sel: Boolean, ac: Int, tc: Int, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(10), 0, dp(10))
        if (sel) {
            v.setBackgroundColor(ac)
            v.setTextColor(contrast(ac))
        } else {
            v.setTextColor(tc)
        }
        v.setOnClickListener { f() }
        return v
    }

    private fun addWallpaper() {
        val w = Prefs.wall(this) ?: return
        try {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(Uri.parse(w))
            frame.addView(iv, FrameLayout.LayoutParams(MP, MP))
            val dim = View(this)
            dim.setBackgroundColor(0x99000000.toInt())
            frame.addView(dim, FrameLayout.LayoutParams(MP, MP))
        } catch (e: Exception) {
            toast("Wallpaper could not be opened")
        }
    }

    private fun addAtomic() {
        atomic = null
        if (!Prefs.atomicOn(this) || Prefs.themeMode(this) == "none") return
        val av = AtomicView(this)
        atomic = av
        frame.addView(av, FrameLayout.LayoutParams(MP, MP))
    }

    private fun addBorder() {
        if (Prefs.rgbBorder(this)) frame.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
    }

    private fun startRgb() {
        ui.removeCallbacks(rgbTick)
        if (rgbTargets.isNotEmpty()) ui.post(rgbTick)
    }

    private fun durStr(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun sizeStr(b: Long): String =
        if (b >= 1073741824L) String.format("%.1f GB", b / 1073741824.0)
        else String.format("%.0f MB", b / 1048576.0)

    private fun folderName(p: String): String {
        val s = p.trim('/').split('/').lastOrNull() ?: ""
        return if (s.isEmpty()) "Internal Storage" else s
    }

    private fun itemOf(v: V, badge: String) =
        Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), badge)

    // ================= media load (background thread) =================
    private fun queryMedia(video: Boolean): List<V> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sel = if (video) null else MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val out = ArrayList<V>()
        try {
            contentResolver.query(base, proj, sel, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        V(
                            c.getString(1) ?: "media",
                            ContentUris.withAppendedId(base, c.getLong(0)),
                            c.getLong(2), c.getLong(3), c.getString(4) ?: "", c.getLong(5)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return sortList(out)
    }

    private fun sortList(l: List<V>): List<V> {
        val asc = Prefs.sortAsc(this)
        val sorted = when (Prefs.sortMode(this)) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedBy { it.size }
            3 -> l.sortedBy { it.dur }
            else -> l.sortedBy { it.date }
        }
        return if (asc) sorted else sorted.reversed()
    }

    private fun reloadMedia() {
        if (!hasPerm()) return
        loading = true
        loader.execute {
            videos = queryMedia(true)
            audios = queryMedia(false)
            loading = false
            runOnUiThread {
                if (!isFinishing && !inSettings && ::listHolder.isInitialized) renderList()
            }
        }
    }

    // ================= play =================
    private fun playList(list: List<V>, index: Int) {
        if (index < 0 || index >= list.size) return
        val cur = list[index]
        val aud = isAudio(cur.uri)
        val q = list.filter { isAudio(it.uri) == aud }
        val qi = q.indexOfFirst { it.uri == cur.uri }
        val cls: Class<*> = if (aud) AudioActivity::class.java else Orient.videoClass(this, cur.uri)
        val i = Intent(this, cls)
        i.data = cur.uri
        i.putStringArrayListExtra("list", ArrayList(q.map { it.uri.toString() }))
        i.putStringArrayListExtra("names", ArrayList(q.map { it.name }))
        i.putExtra("idx", qi)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun openNowPlaying() {
        val cls: Class<*> =
            if (Engine.isVideo) Orient.videoClass(this, Engine.items.getOrNull(Engine.idx))
            else AudioActivity::class.java
        val i = Intent(this, cls)
        i.data = Engine.items.getOrNull(Engine.idx)
        i.putExtra("cont", true)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        startActivityForResult(i, 2)
    }

    private fun pickWall() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, 3)
    }

    private fun streamDialog() {
        val et = EditText(this)
        et.hint = "http://... or rtsp://..."
        AlertDialog.Builder(this)
            .setTitle("Network Stream URL")
            .setView(et)
            .setPositiveButton("Play") { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    val i = Intent(this, PlayerActivity::class.java)
                    i.data = Uri.parse(u)
                    startActivity(i)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= select + delete =================
    private fun toggleSel(u: Uri?) {
        if (u == null) return
        val k = u.toString()
        if (selected.contains(k)) selected.remove(k) else selected.add(k)
        updateSelCount()
    }

    private fun updateSelCount() {
        selCountTv?.text = selected.size.toString() + " selected"
        curAdapter?.notifyDataSetChanged()
    }

    private fun selectAll() {
        for (v in shown) selected.add(v.uri.toString())
        updateSelCount()
    }

    private fun exitSelect() {
        selectMode = false
        selected.clear()
        showHome()
    }

    private fun favSelected() {
        if (selected.isEmpty()) {
            toast("Tick some items first")
            return
        }
        var n = 0
        for (k in selected) {
            if (!Prefs.isFav(this, k)) {
                Prefs.toggleFav(this, k)
                n++
            }
        }
        toast(n.toString() + " item(s) added to Playlist")
        exitSelect()
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) {
            toast("Tick some items first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(selected.size.toString() + " file(s) will be deleted permanently.")
            .setPositiveButton("Delete") { _, _ -> doDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete() {
        val uris = selected.map { Uri.parse(it) }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(pi.intentSender, 9, null, 0, 0, 0)
            } else {
                for (u in uris) contentResolver.delete(u, null, null)
                afterDelete()
            }
        } catch (e: Exception) {
            toast("Delete failed: " + e.message)
        }
    }

    private fun afterDelete() {
        if (Engine.active && Engine.items.isNotEmpty() && selected.contains(Engine.cur().toString())) Engine.stopAll(this)
        selected.clear()
        selectMode = false
        dirty = true
        toast("Deleted")
        showHome()
    }

    private fun sortDialog() {
        val items = arrayOf(
            "Date: new to old", "Date: old to new",
            "Name: A to Z", "Name: Z to A",
            "Size: big to small", "Size: small to big",
            "Length: long to short", "Length: short to long"
        )
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(items) { _, i ->
                Prefs.setSortMode(this, i / 2)
                Prefs.setSortAsc(this, i % 2 == 1)
                dirty = true
                showHome()
            }
            .show()
    }

    // ================= list rows =================
    private fun makeRow(): Pair<View, H> {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(6), dp(6), dp(6))

        val check = TextView(this)
        check.textSize = 22f
        check.setPadding(dp(2), 0, dp(10), 0)
        check.visibility = View.GONE
        row.addView(check)

        val box = FrameLayout(this)
        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(0xFF1E1E2E.toInt())
        box.addView(thumb, FrameLayout.LayoutParams(MP, MP))

        val badge = TextView(this)
        badge.textSize = 11f
        badge.setTextColor(Color.WHITE)
        badge.setBackgroundColor(0xCC000000.toInt())
        badge.setPadding(dp(6), dp(2), dp(6), dp(2))
        val blp = FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END)
        blp.setMargins(0, 0, dp(4), dp(4))
        box.addView(badge, blp)

        val rowWidth = if (Prefs.gridView(this)) dp(140) else dp(130)
        val rowHeight = if (Prefs.gridView(this)) dp(180) else dp(74)
        row.addView(box, LinearLayout.LayoutParams(rowWidth, rowHeight))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val title = TextView(this)
        title.textSize = 14f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val sub = TextView(this)
        sub.textSize = 12f
        col.addView(title)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        return Pair(row, H(check, thumb, badge, title, sub))
    }

    private fun makeAdapter(items: List<Item>, tc: Int, ac: Int): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: H
            val v: View
            if (convertView == null) {
                val pr = makeRow()
                v = pr.first
                holder = pr.second
                v.tag = holder
            } else {
                v = convertView
                holder = convertView.tag as H
            }
            val item = items[position]
            val isFolder = item.tag.startsWith("F:")
            val u = item.uri
            // last chala hua item highlight
            val isLast = !isFolder && u != null && u.toString() == Prefs.lastPlayedUri(this@MainActivity)
            v.setBackgroundColor(if (isLast) (ac and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            holder.title.text = (if (isLast) "\u25B6 " else "") + item.title
            holder.title.setTextColor(if (isLast) ac else tc)
            holder.sub.text = item.sub
            holder.sub.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
            holder.badge.text = item.badge
            holder.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE

            holder.check.visibility = if (selectMode && !isFolder) View.VISIBLE else View.GONE
            holder.check.setTextColor(ac)
            holder.check.text = if (u != null && selected.contains(u.toString())) "\u2611" else "\u2610"

            holder.thumb.setImageBitmap(null)
            if (u != null) {
                val key = u.toString()
                holder.thumb.tag = key
                val cached = cache.get(key)
                if (cached != null) {
                    holder.thumb.setImageBitmap(cached)
                } else {
                    pool.execute {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        val b: Bitmap? = try {
                            contentResolver.loadThumbnail(u, Size(256, 144), null)
                        } catch (e: Exception) {
                            null
                        }
                        if (b != null) {
                            cache.put(key, b)
                            runOnUiThread {
                                if (holder.thumb.tag == key) holder.thumb.setImageBitmap(b)
                            }
                        }
                    }
                }
            }
            return v
        }
    }

    // ================= mini player =================
    private fun refreshMini() {
        if (!::miniBar.isInitialized) return
        if (!Engine.active || inSettings) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = (if (Engine.isVideo) "\uD83C\uDFAC " else "\uD83C\uDFB5 ") + Engine.title
        miniPlay.text = if (Engine.isPlaying()) "\u23F8" else "\u25B6"
    }

    private fun buildMini(ac: Int): LinearLayout {
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(4), dp(4), dp(4))
        val g = GradientDrawable()
        g.setColor(0xEE1A222D.toInt())
        g.setStroke(dp(1), ac)
        g.cornerRadius = dp(14).toFloat()
        bar.background = g
        miniTitle = TextView(this)
        miniTitle.setTextColor(Color.WHITE)
        miniTitle.textSize = 13f
        miniTitle.maxLines = 1
        miniTitle.ellipsize = TextUtils.TruncateAt.END
        bar.addView(miniTitle, LinearLayout.LayoutParams(0, WC, 1f))
        bar.addView(sym("\u23EE", Color.WHITE) { Engine.prev(this) })
        miniPlay = sym("\u23F8", Color.WHITE) { Engine.toggle(this) }
        bar.addView(miniPlay)
        bar.addView(sym("\u23ED", Color.WHITE) { Engine.next(this) })
        bar.addView(sym("\u2715", Color.WHITE) { Engine.stopAll(this) })
        bar.setOnClickListener { openNowPlaying() }
        bar.visibility = View.GONE
        return bar
    }

    // ================= Home =================
    private fun showHome() {
        inSettings = false
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        val ic = Prefs.iconColor(this)
        val rgbAcc = Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ACCENT)
        rgbTargets.clear()
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()
        addAtomic()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(32), dp(12), dp(8))

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        if (selectMode) {
            head.addView(sym("\u2715", ic) { exitSelect() })
            val cnt = TextView(this)
            cnt.text = selected.size.toString() + " selected"
            cnt.textSize = 17f
            cnt.setTextColor(tc)
            selCountTv = cnt
            head.addView(cnt, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\u2611", ic) { selectAll() })
            head.addView(sym("\u2B50", ic) { favSelected() })
            head.addView(sym("\uD83C\uDFB5", ic) {
                val vs = videos.filter { selected.contains(it.uri.toString()) }
                Hooks.convert(this, vs.map { it.uri }, vs.map { it.name }) {
                    dirty = true
                    exitSelect()
                }
            })
            head.addView(sym("\uD83D\uDDD1", Color.parseColor("#FF5252")) { confirmDelete() })
        } else {
            selCountTv = null
            val brand = TextView(this)
            brand.text = "\u26A1 RajPlayer Pro"
            brand.textSize = 22f
            brand.setTextColor(ac)
            brand.setTypeface(null, Typeface.BOLD)
            if (rgbAcc) rgbTargets.add { c -> brand.setTextColor(c) }
            head.addView(brand, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\uD83D\uDD0D", ic) {
                searchOn = !searchOn
                if (!searchOn) query = ""
                showHome()
            })
            head.addView(sym("\uD83C\uDF10", ic) { streamDialog() })
            head.addView(sym("\uD83D\uDCC2", ic) { openFile() })
            head.addView(sym("\u2699", ic) {
                settingsY = 0
                showSettings()
            })
        }
        root.addView(head)

        val line = View(this)
        line.setBackgroundColor(ac)
        if (rgbAcc) rgbTargets.add { c -> line.setBackgroundColor(c) }
        val llp = LinearLayout.LayoutParams(MP, dp(2))
        llp.setMargins(0, dp(6), 0, 0)
        root.addView(line, llp)

        val navRow = LinearLayout(this)
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(0, dp(8), 0, dp(8))
        val tabs = LinearLayout(this)
        val tabNames = arrayOf("Videos", "Folders", "Music", "Playlist")
        for (i in tabNames.indices) {
            tabs.addView(tabBtn(tabNames[i], tab == i, ac, tc) {
                tab = i
                openFolder = null
                showHome()
            }, LinearLayout.LayoutParams(0, WC, 1f))
        }
        navRow.addView(tabs, LinearLayout.LayoutParams(0, WC, 1f))
        navRow.addView(sym("\u21C5", ic) { sortDialog() })
        navRow.addView(sym(if (Prefs.gridView(this)) "\u2637" else "\u2638", ic) {
            Prefs.setGridView(this, !Prefs.gridView(this))
            showHome()
        })
        root.addView(navRow)

        if (searchOn) {
            val se = EditText(this)
            se.hint = "Search media..."
            se.setTextColor(tc)
            se.setHintTextColor((tc and 0x00FFFFFF) or 0x77000000)
            se.setText(query)
            se.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    renderList()
                }

                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            root.addView(se)
        }

        listHolder = FrameLayout(this)
        root.addView(listHolder, LinearLayout.LayoutParams(MP, 0, 1f))

        miniBar = buildMini(ac)
        val mlp = LinearLayout.LayoutParams(MP, WC)
        mlp.setMargins(0, dp(6), 0, 0)
        root.addView(miniBar, mlp)

        renderList()
        refreshMini()
        frame.addView(root, FrameLayout.LayoutParams(MP, MP))
        addBorder()
        startRgb()

        if (hasPerm() && dirty) {
            dirty = false
            reloadMedia()
        }
    }

    private fun renderList() {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        listHolder.removeAllViews()

        if (!hasPerm()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(16), dp(24), dp(16), 0)
            col.addView(label("Storage permissions are required.", 16f, tc))
            col.addView(button("Grant Permission", ac) { reqPerms() })
            col.addView(button("Open App Settings", ac) { openAppSettings() })
            col.addView(label("Settings > Permissions: allow 'Music and audio' and 'Photos and videos', then come back.", 13f, tc))
            listHolder.addView(col)
            return
        }

        val items = ArrayList<Item>()
        val of = openFolder
        val q = query.trim().lowercase()

        if (q.isNotEmpty()) {
            shown = (videos + audios).filter { it.name.lowercase().contains(q) }
            for (v in shown) items.add(itemOf(v, if (isAudio(v.uri)) "\uD83C\uDFB5" else "V"))
        } else if (tab == 3) {
            val favSet = Prefs.favs(this)
            shown = videos.filter { favSet.contains(it.uri.toString()) } + audios.filter { favSet.contains(it.uri.toString()) }
            for (v in shown) items.add(itemOf(v, "\u2B50"))
        } else if (tab == 2) {
            shown = audios
            for (v in shown) items.add(itemOf(v, "\uD83C\uDFB5"))
        } else if (tab == 0 || of != null) {
            shown = if (of != null) videos.filter { it.folder == of } else videos
            for (v in shown) items.add(itemOf(v, "V"))
        } else {
            val groups = videos.groupBy { it.folder }
            val keys = groups.keys.sortedBy { folderName(it).lowercase() }
            for (k in keys) {
                val l = groups[k] ?: continue
                items.add(Item(folderName(k), l.size.toString() + " videos", l[0].uri, l.size.toString(), "F:" + k))
            }
        }

        if (items.isEmpty()) {
            val emptyMsg = when {
                loading -> "Loading..."
                tab == 3 -> "Playlist is empty. Long-press an item, tick it, then tap the star."
                else -> "No items found."
            }
            listHolder.addView(label(emptyMsg, 15f, tc))
            return
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (of != null && q.isEmpty() && tab != 2 && tab != 3) col.addView(label("\uD83D\uDCC1 " + folderName(of), 15f, tc))

        val lv = ListView(this)
        lv.divider = null
        val ad = makeAdapter(items, tc, ac)
        curAdapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, i, _ ->
            val chosen = items[i]
            if (chosen.tag.startsWith("F:")) {
                if (!selectMode) {
                    openFolder = chosen.tag.substring(2)
                    showHome()
                }
            } else if (selectMode) {
                toggleSel(chosen.uri)
            } else {
                playList(shown, shown.indexOfFirst { it.uri == chosen.uri })
            }
        }
        lv.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, i, _ ->
            val chosen = items[i]
            if (!chosen.tag.startsWith("F:") && chosen.uri != null) {
                if (!selectMode) {
                    selectMode = true
                    selected.add(chosen.uri.toString())
                    showHome()
                } else {
                    toggleSel(chosen.uri)
                }
            }
            true
        }
        col.addView(lv, LinearLayout.LayoutParams(MP, 0, 1f))
        listHolder.addView(col, FrameLayout.LayoutParams(MP, MP))
    }

    // ================= Settings =================
    private fun refreshSettings() {
        settingsY = settingsSv?.scrollY ?: 0
        showSettings()
    }

    private fun toggle(root: LinearLayout, name: String, on: Boolean, ac: Int, f: () -> Unit) {
        root.addView(button(name + ": " + (if (on) "ON" else "OFF"), ac) {
            f()
            refreshSettings()
        })
    }

    private fun colorPicker(root: LinearLayout, title: String, key: String) {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(12), 0, dp(2))
        val sw = View(this)
        val g = GradientDrawable()
        g.setColor(Prefs.color(this, key))
        g.setStroke(dp(1), 0xFF888888.toInt())
        g.cornerRadius = dp(6).toFloat()
        sw.background = g
        head.addView(sw, LinearLayout.LayoutParams(dp(26), dp(26)))
        val state = if (Prefs.hasOverride(this, key)) "custom" else "theme"
        val t = TextView(this)
        t.text = "  " + title + "  (" + state + ")"
        t.textSize = 14f
        t.setTextColor(tc)
        head.addView(t)
        root.addView(head)

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        for (hex in palette) {
            val c = Color.parseColor(hex)
            val b = View(this)
            val gd = GradientDrawable()
            gd.setColor(c)
            gd.setStroke(dp(1), 0xFF555555.toInt())
            gd.cornerRadius = dp(8).toFloat()
            b.background = gd
            val lp = LinearLayout.LayoutParams(dp(34), dp(34))
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setColor(this, key, c)
                refreshSettings()
            }
            row.addView(b)
        }
        hs.addView(row)
        root.addView(hs)

        val er = LinearLayout(this)
        er.gravity = Gravity.CENTER_VERTICAL
        val et = EditText(this)
        et.hint = "#RRGGBB or #AARRGGBB"
        et.setTextColor(tc)
        et.setHintTextColor((tc and 0x00FFFFFF) or 0x77000000)
        et.textSize = 13f
        et.setSingleLine(true)
        er.addView(et, LinearLayout.LayoutParams(0, WC, 1f))
        er.addView(button("Apply", ac) {
            var s = et.text.toString().trim()
            if (!s.startsWith("#")) s = "#" + s
            try {
                Prefs.setColor(this, key, Color.parseColor(s))
                refreshSettings()
            } catch (e: Exception) {
                toast("Invalid hex code")
            }
        })
        er.addView(button("Reset", ac) {
            Prefs.clearColor(this, key)
            refreshSettings()
        })
        root.addView(er)
    }

    private fun showSettings() {
        inSettings = true
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        rgbTargets.clear()
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()
        addAtomic()

        val sv = ScrollView(this)
        settingsSv = sv
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(40), dp(16), dp(32))

        root.addView(label("\u2699\uFE0F Settings", 24f, tc))
        root.addView(button("\u2190 Back", ac) {
            settingsY = 0
            showHome()
        })

        // ---- theme presets ----
        root.addView(label("Theme Presets", 16f, tc))
        val cur = Prefs.themeMode(this)
        val presetRow = HorizontalScrollView(this)
        val pr = LinearLayout(this)
        for (i in Theme.MODES.indices) {
            val m = Theme.MODES[i]
            val nm = Theme.NAMES[i]
            pr.addView(button((if (m == cur) "\u2713 " else "") + nm, ac) {
                Prefs.setThemeMode(this, m, m != "none")
                refreshSettings()
            })
        }
        presetRow.addView(pr)
        root.addView(presetRow)
        root.addView(label("A preset sets every colour of that theme. 'None' keeps your manual colours and turns the animation off.", 12f, tc))

        // ---- animation ----
        root.addView(label("Animation", 16f, tc))
        toggle(root, "Atomic Animation (app)", Prefs.atomicOn(this), ac) { Prefs.setAtomicOn(this, !Prefs.atomicOn(this)) }
        toggle(root, "Video Margin Animation", Prefs.videoMarginAnim(this), ac) { Prefs.setVideoMarginAnim(this, !Prefs.videoMarginAnim(this)) }
        toggle(root, "RGB Border", Prefs.rgbBorder(this), ac) { Prefs.setRgbBorder(this, !Prefs.rgbBorder(this)) }
        val spTv = label("Animation Speed: " + String.format("%.2fx", Prefs.atomSpeed(this)), 14f, tc)
        root.addView(spTv)
        val spSb = SeekBar(this)
        spSb.max = 110
        spSb.progress = Math.round((Prefs.atomSpeed(this) - 0.5f) / 0.05f)
        spSb.progressTintList = ColorStateList.valueOf(ac)
        spSb.thumbTintList = ColorStateList.valueOf(ac)
        spSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 0.5f + p * 0.05f
                spTv.text = "Animation Speed: " + String.format("%.2fx", v)
                if (fromUser) atomic?.setSpeedMultiplier(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}

            override fun onStopTrackingTouch(s: SeekBar?) {
                Prefs.setAtomSpeed(this@MainActivity, 0.5f + (s?.progress ?: 10) * 0.05f)
            }
        })
        root.addView(spSb)

        // ---- video / playback ----
        root.addView(label("Video & Playback", 16f, tc))
        toggle(root, "Auto-rotate to match video", Prefs.autoOrient(this), ac) { Prefs.setAutoOrient(this, !Prefs.autoOrient(this)) }
        toggle(root, "Mute audio above 4x speed", Prefs.muteFast(this), ac) { Prefs.setMuteFast(this, !Prefs.muteFast(this)) }
        toggle(root, "Background Audio", Prefs.bgAudio(this), ac) {
            Prefs.setBgAudio(this, !Prefs.bgAudio(this))
            Engine.bgAudio = Prefs.bgAudio(this)
        }
        root.addView(button("Background Activity (Battery): " + (if (Power.isIgnoring(this)) "ALLOWED \u2705" else "Tap to allow"), ac) {
            Power.request(this)
        })
        val seekSecs = intArrayOf(5, 10, 15, 20, 30)
        val seekNames = arrayOf("5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds")
        root.addView(button("Double-tap Seek: " + Prefs.seekSec(this) + "s", ac) {
            AlertDialog.Builder(this)
                .setTitle("Seek duration")
                .setItems(seekNames) { _, i ->
                    Prefs.setSeekSec(this, seekSecs[i])
                    refreshSettings()
                }
                .show()
        })

        // ---- colours ----
        root.addView(label("Colours (palette or custom hex)", 16f, tc))
        colorPicker(root, "Background", Prefs.K_BG)
        colorPicker(root, "Accent", Prefs.K_ACCENT)
        colorPicker(root, "General Text", Prefs.K_TEXT)
        colorPicker(root, "Video Title Bar", Prefs.K_VBAR)
        colorPicker(root, "Video Title Text", Prefs.K_VTEXT)
        colorPicker(root, "Audio Title Bar", Prefs.K_ABAR)
        colorPicker(root, "Audio Title Text", Prefs.K_ATEXT)
        colorPicker(root, "General Icon Colour", Prefs.K_ICON)
        root.addView(button("Reset all colours to theme", ac) {
            Prefs.resetColors(this)
            refreshSettings()
        })

        // ---- wallpaper ----
        root.addView(label("Wallpaper", 16f, tc))
        root.addView(button("Choose Wallpaper", ac) { pickWall() })
        root.addView(button("Remove Wallpaper", ac) {
            Prefs.setWall(this, null)
            refreshSettings()
        })

        sv.addView(root)
        frame.addView(sv, FrameLayout.LayoutParams(MP, MP))
        addBorder()
        startRgb()
        sv.post { sv.scrollTo(0, settingsY) }
    }
}
