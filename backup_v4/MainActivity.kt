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
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    class H(val thumb: ImageView, val badge: TextView, val title: TextView, val sub: TextView)

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var frame: FrameLayout
    private lateinit var listHolder: FrameLayout
    private var videos: List<V> = emptyList()
    private var shown: List<V> = emptyList()
    private var inSettings = false
    private var asked = false
    private var tab = 0 // 0:Videos, 1:Folders, 2:Playlist
    private var openFolder: String? = null
    private var searchOn = false
    private var query = ""
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(4)

    private val palette = listOf(
        "#0B0F14", "#121820", "#1A222D", "#1E1E2E", "#2D1B36", "#0F2A3F", "#FFFFFF", "#FFD700",
        "#FF9800", "#FF5252", "#FF4081", "#E040FB", "#7C4DFF", "#448AFF", "#00E5FF", "#00E676"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        if (!hasPerm() && !asked) {
            asked = true
            reqPerms()
        }
        if (inSettings) showSettings() else showHome()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inSettings) {
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
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 2) {
            val i = Intent(this, PlayerActivity::class.java)
            i.data = u
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        }
        if (requestCode == 3) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Prefs.setWall(this, u.toString())
            showSettings()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun perm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm() = checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    private fun reqPerms() {
        val l = ArrayList<String>()
        l.add(perm())
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun label(t: String, size: Float, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setPadding(0, dp(6), 0, dp(6))
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
        v.textSize = 20f
        v.setTextColor(color)
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
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
            val gd = GradientDrawable()
            gd.setColor(ac)
            gd.cornerRadius = dp(8).toFloat()
            v.background = gd
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
            dim.setBackgroundColor(0xAA0B0F14.toInt())
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

    private fun loadVideos() {
        val list = ArrayList<V>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        try {
            contentResolver.query(base, proj, null, null, null)?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dac = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    list.add(
                        V(
                            c.getString(nc) ?: "video",
                            ContentUris.withAppendedId(base, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: "",
                            c.getLong(dac)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            toast("Videos load nahi hue")
        }
        videos = sortList(list)
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

    private fun playList(list: List<V>, index: Int) {
        if (index < 0 || index >= list.size) return
        val i = Intent(this, PlayerActivity::class.java)
        i.data = list[index].uri
        i.putStringArrayListExtra("list", ArrayList(list.map { it.uri.toString() }))
        i.putExtra("idx", index)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    private fun sortDialog() {
        val modes = arrayOf("Date Added", "File Name", "File Size", "Video Length")
        val orders = arrayOf("New to Old (Descending)", "Old to New (Ascending)")
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
                t.text = (if (mIdx == i) "⚡ " else "   ") + modes[i]
                t.textSize = 15f
                t.setTextColor(Prefs.text(this@MainActivity))
                t.setPadding(0, dp(6), 0, dp(6))
                return t
            }
        }
        lv.setOnItemClickListener { _, _, i, _ -> mIdx = i; lv.invalidateViews() }
        box.addView(lv, LinearLayout.LayoutParams(MP, dp(140)))

        box.addView(label("Order Direction", 15f, Prefs.text(this)))
        val lv2 = ListView(this)
        lv2.adapter = object : BaseAdapter() {
            override fun getCount() = orders.size
            override fun getItem(i: Int) = orders[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val t = TextView(this@MainActivity)
                t.text = (if (aIdx == i) "⚡ " else "   ") + orders[i]
                t.textSize = 15f
                t.setTextColor(Prefs.text(this@MainActivity))
                t.setPadding(0, dp(6), 0, dp(6))
                return t
            }
        }
        lv2.setOnItemClickListener { _, _, i, _ -> aIdx = i; lv2.invalidateViews() }
        box.addView(lv2, LinearLayout.LayoutParams(MP, dp(90)))

        AlertDialog.Builder(this)
            .setTitle("Advanced Sorting")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                Prefs.setSortMode(this, mIdx)
                Prefs.setSortAsc(this, aIdx == 1)
                showHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeRow(): Pair<View, H> {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(6), dp(6), dp(6))

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
        return Pair(row, H(thumb, badge, title, sub))
    }

    private fun makeAdapter(items: List<Item>, tc: Int): BaseAdapter = object : BaseAdapter() {
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
            holder.title.text = item.title
            holder.title.setTextColor(tc)
            holder.sub.text = item.sub
            holder.sub.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
            holder.badge.text = item.badge
            holder.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE

            val u = item.uri
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

    private fun showHome() {
        inSettings = false
        if (hasPerm()) loadVideos()
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(32), dp(12), 0)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val brand = TextView(this)
        brand.text = "⚡ RajPlayer Pro"
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
        tabs.addView(tabBtn("Playlist", tab == 2, ac, tc) {
            tab = 2; openFolder = null; showHome()
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
            se.hint = "Search videos instantly..."
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
        root.addView(listHolder, LinearLayout.LayoutParams(MP, MP))
        renderList()
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
            col.addView(label("Storage permission is required to list media files.", 16f, tc))
            col.addView(button("Grant Permission", ac) { reqPerms() })
            listHolder.addView(col)
            return
        }
        if (videos.isEmpty()) {
            listHolder.addView(label("No media files found. Use folder or file picker above.", 15f, tc))
            return
        }

        val items = ArrayList<Item>()
        val of = openFolder
        val q = query.trim().lowercase()

        if (q.isNotEmpty()) {
            shown = videos.filter { it.name.lowercase().contains(q) }
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " • " + sizeStr(v.size), v.uri, durStr(v.dur), "V"))
            }
        } else if (tab == 2) {
            shown = videos
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " • " + sizeStr(v.size), v.uri, durStr(v.dur), "V"))
            }
        } else if (tab == 0 || of != null) {
            shown = if (of != null) videos.filter { it.folder == of } else videos
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " • " + sizeStr(v.size), v.uri, durStr(v.dur), "V"))
            }
        } else {
            val groups = videos.groupBy { it.folder }
            val keys = groups.keys.sortedBy { folderName(it).lowercase() }
            for (k in keys) {
                val l = groups[k] ?: continue
                items.add(Item(folderName(k), "${l.size} videos", l[0].uri, "${l.size}", "F:$k"))
            }
        }

        if (items.isEmpty()) {
            listHolder.addView(label("No items found.", 15f, tc))
            return
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (of != null && q.isEmpty()) col.addView(label("📁 " + folderName(of), 15f, tc))

        val lv = ListView(this)
        lv.divider = null
        lv.adapter = makeAdapter(items, tc)
        lv.setOnItemClickListener { _, _, i, _ ->
            val chosen = items[i]
            if (chosen.tag.startsWith("F:")) {
                openFolder = chosen.tag.substring(2)
                showHome()
            } else {
                playList(shown, shown.indexOfFirst { it.uri == chosen.uri })
            }
        }
        col.addView(lv, LinearLayout.LayoutParams(MP, 0, 1f))
        listHolder.addView(col, FrameLayout.LayoutParams(MP, MP))
    }

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
        root.addView(label("⚙️ Ultra Settings", 24f, tc))
        root.addView(button("← Back to Player", ac) { showHome() })

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
