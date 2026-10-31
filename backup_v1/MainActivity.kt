package com.raj.rajplayer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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

class MainActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var frame: FrameLayout
    private var videos: List<Pair<String, Uri>> = emptyList()
    private var inSettings = false
    private var asked = false

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
        if (inSettings) showHome() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 2) play(u)
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

    private fun loadVideos() {
        val list = ArrayList<Pair<String, Uri>>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        try {
            contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    list.add(Pair(c.getString(nc) ?: "video", ContentUris.withAppendedId(base, c.getLong(idc))))
                }
            }
        } catch (e: Exception) {
            toast("Videos load nahi hue")
        }
        videos = list
    }

    private fun play(uri: Uri) {
        val i = Intent(this, PlayerActivity::class.java)
        i.data = uri
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
        root.setPadding(dp(16), dp(40), dp(16), dp(8))
        root.addView(label("RajPlayer", 28f, tc))

        val row = LinearLayout(this)
        row.addView(button("File kholo", ac) { openFile() })
        row.addView(button("Settings", ac) { showSettings() })
        root.addView(row)

        if (!hasPerm()) {
            root.addView(label("Videos dikhane ke liye permission chahiye.", 16f, tc))
            root.addView(button("Permission do", ac) { requestPermissions(arrayOf(perm()), 1) })
        } else if (videos.isEmpty()) {
            root.addView(label("Koi video nahi mila. 'File kholo' se chuno.", 16f, tc))
        } else {
            val lv = ListView(this)
            lv.divider = null
            lv.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, videos.map { it.first }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val t = super.getView(position, convertView, parent) as TextView
                    t.setTextColor(tc)
                    return t
                }
            }
            lv.setOnItemClickListener { _, _, i, _ -> play(videos[i].second) }
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
