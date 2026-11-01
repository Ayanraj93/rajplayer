package com.raj.rajplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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

    private var videos: List<V> = emptyList()
    private var audios: List<V> = emptyList()
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

    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(4)

    private val palette = listOf(
        "#0B0F14", "#121820", "#1A222D", "#1E1E2E", "#2D1B36", "#0F2A3F", "#FFFFFF", "#FFD700",
        "#FF9800", "#FF5252", "#FF4081", "#E040FB", "#7C4DFF", "#448AFF", "#00E5FF", "#00E676"
    )

    private val miniTick = object : Runnable {
        override fun run() {
            refreshMini()
            ui.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
        Engine.bgAudio = Prefs.bgAudio(this)
    }

    override fun onResume() {
        super.onResume()
        if (!hasPerm() && !asked) {
            asked = true
            reqPerms()
        }
        dirty = true
        if (inSettings) showSettings() else showHome()
        ui.post(miniTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(miniTick)
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
            if (resultCode == RESULT_OK) afterDelete() else toast("Delete cancel hua")
            return
        }
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 2) openUri(u)
        if (requestCode == 3) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Prefs.setWall(this, u.toString())
            showSettings()
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun perm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun audioPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    // video YA audio me se koi ek mil jaye to bhi list dikhao
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
            toast("Wallpaper nahi khul paya")
        }
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

    // ---------- media load ----------
    private fun loadMedia() {
        val vList = ArrayList<V>()
        val vBase = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val vProj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        try {
            contentResolver.query(vBase, vProj, null, null, null)?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dac = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    vList.add(
                        V(
                            c.getString(nc) ?: "video",
                            ContentUris.withAppendedId(vBase, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: "",
                            c.getLong(dac)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        videos = sortList(vList)

        val aList = ArrayList<V>()
        val aBase = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val aProj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED
        )
        try {
            contentResolver.query(aBase, aProj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dac = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    aList.add(
                        V(
                            c.getString(nc) ?: "audio",
                            ContentUris.withAppendedId(aBase, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: "",
                            c.getLong(dac)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        audios = sortList(aList)
    }

    private fun sortList(l: List<V>): List<V> {
        val mode = Prefs.sortMode(this)
        val asc = Prefs.sortAsc(this)
        val sorted = when (mode) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedBy { it.size }
            3 -> l.sortedBy { it.dur }
            else -> l.sortedBy { it.date }
        }
        return if (asc) sorted else sorted.reversed()
    }

    // ---------- play ----------
    private fun playList(list: List<V>, index: Int) {
        if (index < 0 || index >= list.size) return
        val cur = list[index]
        val aud = isAudio(cur.uri)
        val q = list.filter { isAudio(it.uri) == aud }
        val qi = q.indexOfFirst { it.uri == cur.uri }
        val i = Intent(this, if (aud) AudioActivity::class.java else PlayerActivity::class.java)
        i.data = cur.uri
        i.putStringArrayListExtra("list", ArrayList(q.map { it.uri.toString() }))
        i.putStringArrayListExtra("names", ArrayList(q.map { it.name }))
        i.putExtra("idx", qi)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun openUri(u: Uri) {
        val aud = (contentResolver.getType(u) ?: "").startsWith("audio/")
        val i = Intent(this, if (aud) AudioActivity::class.java else PlayerActivity::class.java)
        i.data = u
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun openNowPlaying() {
        val i = Intent(this, if (Engine.isVideo) PlayerActivity::class.java else AudioActivity::class.java)
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
        et.hint = "http://... ya rtsp://..."
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

    // ---------- select + delete ----------
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
            toast("Pehle koi item tick karo")
            return
        }
        var n = 0
        for (k in selected) {
            if (!Prefs.isFav(this, k)) {
                Prefs.toggleFav(this, k)
                n++
            }
        }
        toast(n.toString() + " item Playlist me jode \u2B50")
        exitSelect()
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) {
            toast("Pehle koi item tick karo")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete karna hai?")
            .setMessage(selected.size.toString() + " file hamesha ke liye delete ho jayegi. Wapas nahi aayegi.")
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
            toast("Delete nahi ho paya: " + e.message)
        }
    }

    private fun afterDelete() {
        if (Engine.active && selected.contains(Engine.cur().toString())) Engine.stopAll(this)
        selected.clear()
        selectMode = false
        dirty = true
        toast("Delete ho gaya")
        showHome()
    }

    // ---------- sort dialog ----------
    private fun sortDialog() {
        val modes = arrayOf("Date Added", "File Name", "File Size", "Length")
        val orders = arrayOf("New to Old", "Old to New")
        var mIdx = Prefs.sortMode(this)
        var aIdx = if (Prefs.sortAsc(this)) 1 else 0

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))

        box.addView(label("Sort Criteria", 15f, Prefs.text(this)))
        val lv = ListView(this)
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = modes.size
            override fun getItem(i: Int) = modes[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val t = TextView(this@MainActivity)
                t.text = (if (mIdx == i) "\u26A1 " else "   ") + modes[i]
                t.textSize = 15f
                t.setTextColor(Prefs.text(this@MainActivity))
                t.setPadding(0, dp(6), 0, dp(6))
                return t
            }
        }
        lv.setOnItemClickListener { _, _, i, _ ->
            mIdx = i
            lv.invalidateViews()
        }
        box.addView(lv, LinearLayout.LayoutParams(MP, dp(140)))

        box.addView(label("Order Direction", 15f, Prefs.text(this)))
        val lv2 = ListView(this)
        lv2.adapter = object : BaseAdapter() {
            override fun getCount() = orders.size
            override fun getItem(i: Int) = orders[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val t = TextView(this@MainActivity)
                t.text = (if (aIdx == i) "\u26A1 " else "   ") + orders[i]
                t.textSize = 15f
                t.setTextColor(Prefs.text(this@MainActivity))
                t.setPadding(0, dp(6), 0, dp(6))
                return t
            }
        }
        lv2.setOnItemClickListener { _, _, i, _ ->
            aIdx = i
            lv2.invalidateViews()
        }
        box.addView(lv2, LinearLayout.LayoutParams(MP, dp(90)))

        AlertDialog.Builder(this)
            .setTitle("Advanced Sorting")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                Prefs.setSortMode(this, mIdx)
                Prefs.setSortAsc(this, aIdx == 1)
                dirty = true
                showHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- rows ----------
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
            holder.title.text = item.title
            holder.title.setTextColor(tc)
            holder.sub.text = item.sub
            holder.sub.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
            holder.badge.text = item.badge
            holder.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE

            val u = item.uri
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

    // ---------- mini player ----------
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

    private fun buildMini(tc: Int, ac: Int): LinearLayout {
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

    // ---------- Home ----------
    private fun showHome() {
        inSettings = false
        if (hasPerm() && dirty) {
            loadMedia()
            dirty = false
        }
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(32), dp(12), dp(8))

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        if (selectMode) {
            head.addView(sym("\u2715", tc) { exitSelect() })
            val cnt = TextView(this)
            cnt.text = selected.size.toString() + " selected"
            cnt.textSize = 17f
            cnt.setTextColor(tc)
            selCountTv = cnt
            head.addView(cnt, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\u2611", tc) { selectAll() })
            head.addView(sym("\u2B50", tc) { favSelected() })
            head.addView(sym("\uD83D\uDDD1", Color.parseColor("#FF5252")) { confirmDelete() })
        } else {
            selCountTv = null
            val brand = TextView(this)
            brand.text = "\u26A1 RajPlayer Pro"
            brand.textSize = 22f
            brand.setTextColor(ac)
            brand.setTypeface(null, android.graphics.Typeface.BOLD)
            head.addView(brand, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\uD83D\uDD0D", tc) {
                searchOn = !searchOn
                if (!searchOn) query = ""
                showHome()
            })
            head.addView(sym("\uD83C\uDF10", tc) { streamDialog() })
            head.addView(sym("\uD83D\uDCC2", tc) { openFile() })
            head.addView(sym("\u2699", tc) { showSettings() })
        }
        root.addView(head)

        val navRow = LinearLayout(this)
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(0, dp(8), 0, dp(8))

        val tabs = LinearLayout(this)
        tabs.addView(tabBtn("Videos", tab == 0, ac, tc) {
            tab = 0; openFolder = null; showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Folders", tab == 1, ac, tc) {
            tab = 1; openFolder = null; showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Music", tab == 2, ac, tc) {
            tab = 2; openFolder = null; showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Playlist", tab == 3, ac, tc) {
            tab = 3; openFolder = null; showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        navRow.addView(tabs, LinearLayout.LayoutParams(0, WC, 1f))

        navRow.addView(sym("\u21C5", tc) { sortDialog() })
        navRow.addView(sym(if (Prefs.gridView(this)) "\u2637" else "\u2638", tc) {
            Prefs.setGridView(this, !Prefs.gridView(this))
            showHome()
        })
        root.addView(navRow)

        if (searchOn) {
            val se = EditText(this)
            se.hint = "Search media..."
            se.setTextColor(tc)
            se.setHintTextColor(0x77FFFFFF.toInt())
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

        miniBar = buildMini(tc, ac)
        val mlp = LinearLayout.LayoutParams(MP, WC)
        mlp.setMargins(0, dp(6), 0, 0)
        root.addView(miniBar, mlp)

        renderList()
        refreshMini()
        frame.addView(root, FrameLayout.LayoutParams(MP, MP))
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
            col.addView(button("App settings kholo", ac) { openAppSettings() })
            col.addView(label("Settings > Permissions me 'Music and audio' aur 'Photos and videos' ko Allow (all) karo, phir wapas aao.", 13f, tc))
            listHolder.addView(col)
            return
        }

        val items = ArrayList<Item>()
        val of = openFolder
        val q = query.trim().lowercase()
        val activeList = if (tab == 2) audios else videos

        if (q.isNotEmpty()) {
            shown = (videos + audios).filter { it.name.lowercase().contains(q) }
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), if (isAudio(v.uri)) "\uD83C\uDFB5" else "V"))
            }
        } else if (tab == 3) {
            val favSet = Prefs.favs(this)
            shown = videos.filter { favSet.contains(it.uri.toString()) } + audios.filter { favSet.contains(it.uri.toString()) }
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "\u2B50"))
            }
        } else if (tab == 2) {
            shown = audios
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "\uD83C\uDFB5"))
            }
        } else if (tab == 0 || of != null) {
            shown = if (of != null) activeList.filter { it.folder == of } else activeList
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "V"))
            }
        } else {
            val groups = videos.groupBy { it.folder }
            val keys = groups.keys.sortedBy { folderName(it).lowercase() }
            for (k in keys) {
                val l = groups[k] ?: continue
                items.add(Item(folderName(k), l.size.toString() + " videos", l[0].uri, l.size.toString(), "F:" + k))
            }
        }

        if (items.isEmpty()) {
            val emptyMsg = if (tab == 3) "Playlist abhi khali hai. Kisi item ko long press karke tick karo, phir \u2B50 dabao." else "No items found."
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

    // ---------- Settings ----------
    private fun applyPreset(bg: String, tx: String, ac: String) {
        Prefs.setBg(this, Color.parseColor(bg))
        Prefs.setText(this, Color.parseColor(tx))
        Prefs.setAccent(this, Color.parseColor(ac))
        showSettings()
    }

    private fun colorRow(root: LinearLayout, title: String, set: (Int) -> Unit) {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        root.addView(label(title, 15f, tc))

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
            val lp = LinearLayout.LayoutParams(dp(38), dp(38))
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            b.layoutParams = lp
            b.setOnClickListener {
                set(c)
                showSettings()
            }
            row.addView(b)
        }
        hs.addView(row)
        root.addView(hs)

        val et = EditText(this)
        et.hint = "Custom #HexColor"
        et.setTextColor(tc)
        et.setHintTextColor(0x77FFFFFF.toInt())
        root.addView(et)
        root.addView(button("Apply Color", ac) {
            try {
                set(Color.parseColor(et.text.toString().trim()))
                showSettings()
            } catch (e: Exception) {
                toast("Invalid Hex Code")
            }
        })
    }

    private fun showSettings() {
        inSettings = true
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()

        val sv = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(40), dp(16), dp(24))
        root.addView(label("\u2699\uFE0F Ultra Settings", 24f, tc))
        root.addView(button("\u2190 Back", ac) { showHome() })

        root.addView(label("Video Background Audio", 15f, tc))
        root.addView(button("Background Audio: " + (if (Prefs.bgAudio(this)) "ON" else "OFF"), ac) {
            Prefs.setBgAudio(this, !Prefs.bgAudio(this))
            Engine.bgAudio = Prefs.bgAudio(this)
            showSettings()
        })

        root.addView(label("Manual Double-Tap Seek Time", 15f, tc))
        val seekSecs = intArrayOf(5, 10, 15, 20, 30)
        val seekNames = arrayOf("5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds")
        val sbBtn = Button(this)
        sbBtn.text = "Current Seek: " + Prefs.seekSec(this) + "s"
        sbBtn.setBackgroundColor(ac)
        sbBtn.setTextColor(contrast(ac))
        sbBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Select Seek Duration")
                .setItems(seekNames) { _, i ->
                    Prefs.setSeekSec(this, seekSecs[i])
                    showSettings()
                }
                .show()
        }
        root.addView(sbBtn)

        root.addView(label("Preset Themes", 15f, tc))
        val ths = HorizontalScrollView(this)
        val thr = LinearLayout(this)
        thr.addView(button("Cyber Dark", ac) { applyPreset("#0B0F14", "#FFFFFF", "#00E676") })
        thr.addView(button("OLED Black", ac) { applyPreset("#000000", "#FFFFFF", "#00E5FF") })
        thr.addView(button("Royal Blue", ac) { applyPreset("#0D1B2A", "#E0E1DD", "#448AFF") })
        thr.addView(button("Deep Purple", ac) { applyPreset("#1A122B", "#FFFFFF", "#E040FB") })
        thr.addView(button("Clean Light", ac) { applyPreset("#F5F7FA", "#111111", "#2979FF") })
        ths.addView(thr)
        root.addView(ths)

        colorRow(root, "Background Color") { Prefs.setBg(this, it) }
        colorRow(root, "Text Color") { Prefs.setText(this, it) }
        colorRow(root, "Accent & Button Color") { Prefs.setAccent(this, it) }

        root.addView(label("Custom Wallpaper", 15f, tc))
        root.addView(button("Pick Wallpaper Image", ac) { pickWall() })
        root.addView(button("Remove Wallpaper", ac) {
            Prefs.setWall(this, null)
            showSettings()
        })
        sv.addView(root)
        frame.addView(sv, FrameLayout.LayoutParams(MP, MP))
    }
}
