package com.raj.rajplayer

import android.Manifest
import android.app.Activity
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
import android.text.TextUtils
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

    data class V(val name: String, val uri: Uri, val dur: Long, val size: Long, val folder: String)
    data class Item(val title: String, val sub: String, val uri: Uri?, val badge: String, val tag: String)
    class H(val thumb: ImageView, val badge: TextView, val title: TextView, val sub: TextView)

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var frame: FrameLayout
    private var videos: List<V> = emptyList()
    private var shown: List<V> = emptyList()
    private var inSettings = false
    private var asked = false
    private var tab = 0
    private var openFolder: String? = null
    private val cache = LruCache<String, Bitmap>(100)
    private val pool = Executors.newFixedThreadPool(3)

    private val palette = listOf(
        "#000000", "#101418", "#1E1E2E", "#263238", "#37474F", "#FFFFFF", "#EEEEEE", "#FFEB3B",
        "#FF9800", "#F44336", "#E91E63", "#9C27B0", "#3F51B5", "#2196F3", "#00BCD4", "#00C853"
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
            requestPermissions(arrayOf(perm()), 1)
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

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun perm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm() = checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

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
        v.setPadding(dp(12), dp(8), dp(12), dp(8))
        v.setOnClickListener { f() }
        return v
    }

    private fun tabBtn(t: String, sel: Boolean, ac: Int, tc: Int, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 15f
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
        return if (s.isEmpty()) "Internal" else s
    }

    private fun loadVideos() {
        val list = ArrayList<V>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        try {
            contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    list.add(
                        V(
                            c.getString(nc) ?: "video",
                            ContentUris.withAppendedId(base, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            toast("Videos load nahi hue")
        }
        videos = list
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

    // ---------- list rows ----------
    private fun makeRow(): Pair<View, H> {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))

        val box = FrameLayout(this)
        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(0xFF222222.toInt())
        box.addView(thumb, FrameLayout.LayoutParams(MP, MP))
        val badge = TextView(this)
        badge.textSize = 11f
        badge.setTextColor(Color.WHITE)
        badge.setBackgroundColor(0xAA000000.toInt())
        badge.setPadding(dp(4), dp(1), dp(4), dp(1))
        val blp = FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END)
        blp.setMargins(0, 0, dp(4), dp(4))
        box.addView(badge, blp)
        row.addView(box, LinearLayout.LayoutParams(dp(128), dp(72)))

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
            holder.sub.setTextColor((tc and 0x00FFFFFF) or 0xAA000000.toInt())
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

    // ---------- Home ----------
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
        root.setPadding(dp(12), dp(36), dp(12), 0)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(label("RajPlayer", 24f, tc), LinearLayout.LayoutParams(0, WC, 1f))
        head.addView(sym("\uD83D\uDCC2", tc) { openFile() })
        head.addView(sym("\u2699", tc) { showSettings() })
        root.addView(head)

        val tabs = LinearLayout(this)
        tabs.addView(tabBtn("Videos", tab == 0, ac, tc) {
            tab = 0
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Folders", tab == 1, ac, tc) {
            tab = 1
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        root.addView(tabs)

        if (!hasPerm()) {
            root.addView(label("Videos dikhane ke liye permission chahiye.", 16f, tc))
            root.addView(button("Permission do", ac) { requestPermissions(arrayOf(perm()), 1) })
        } else if (videos.isEmpty()) {
            root.addView(label("Koi video nahi mila. Upar \uD83D\uDCC2 se file chuno.", 16f, tc))
        } else {
            val items = ArrayList<Item>()
            val of = openFolder
            if (tab == 0 || of != null) {
                shown = if (of != null) videos.filter { it.folder == of } else videos
                if (of != null) root.addView(label("\uD83D\uDCC1 " + folderName(of), 15f, tc))
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
            root.addView(lv, LinearLayout.LayoutParams(MP, MP))
        }
        frame.addView(root, FrameLayout.LayoutParams(MP, MP))
    }

    // ---------- Settings ----------
    private fun colorRow(root: LinearLayout, title: String, set: (Int) -> Unit) {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        root.addView(label(title, 16f, tc))

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        for (hex in palette) {
            val c = Color.parseColor(hex)
            val b = View(this)
            val gd = GradientDrawable()
            gd.setColor(c)
            gd.setStroke(dp(1), 0xFF888888.toInt())
            gd.cornerRadius = dp(6).toFloat()
            b.background = gd
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
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
        et.hint = "#RRGGBB"
        et.setTextColor(tc)
        et.setHintTextColor(0x88FFFFFF.toInt())
        root.addView(et)
        root.addView(button("Ye rang lagao", ac) {
            try {
                set(Color.parseColor(et.text.toString().trim()))
                showSettings()
            } catch (e: Exception) {
                toast("Galat hex code")
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
        root.addView(label("Settings", 26f, tc))
        root.addView(button("Wapas", ac) { showHome() })

        colorRow(root, "Background rang") { Prefs.setBg(this, it) }
        colorRow(root, "Text rang") { Prefs.setText(this, it) }
        colorRow(root, "Accent (button) rang") { Prefs.setAccent(this, it) }

        root.addView(label("Wallpaper", 16f, tc))
        root.addView(button("Wallpaper chuno", ac) { pickWall() })
        root.addView(button("Wallpaper hatao", ac) {
            Prefs.setWall(this, null)
            showSettings()
        })
        sv.addView(root)
        frame.addView(sv, FrameLayout.LayoutParams(MP, MP))
    }
}
