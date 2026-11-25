from pathlib import Path
from datetime import datetime
import shutil

main = Path("app/src/main/java/com/raj/rajplayer/MainActivity.kt")
player = Path("app/src/main/java/com/raj/rajplayer/PlayerActivity.kt")

src = {
    "main": main.read_text(),
    "player": player.read_text()
}
out = dict(src)

def rep(key, old, new):
    n = out[key].count(old)
    if n != 1:
        raise RuntimeError(f"{key}: expected 1 match, found {n}\n{old[:120]}")
    out[key] = out[key].replace(old, new, 1)

# MAIN: dedicated conversion selection mode
rep("main",
    "    private var selectMode = false\n",
    "    private var selectMode = false\n    private var conversionSelectMode = false\n")

rep("main", """    private fun exitSelect() {
        selectMode = false
        selected.clear()
        showHome()
    }""",
"""    private fun exitSelect() {
        selectMode = false
        conversionSelectMode = false
        selected.clear()
        showHome()
    }""")

rep("main", """            head.addView(sym("\\u2715", ic) { exitSelect() })
            val cnt = TextView(this)
            cnt.text = selected.size.toString() + " selected"
            cnt.textSize = 17f
            cnt.setTextColor(tc)
            selCountTv = cnt
            head.addView(cnt, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\\u2611", ic) { selectAll() })
            head.addView(sym("\\u2B50", ic) { favSelected() })
            head.addView(sym("\\uD83C\\uDFB5", ic) {
                val chosen = shown.filter { selected.contains(it.uri.toString()) }
                Hooks.convert(this, chosen.map { it.uri }, chosen.map { it.name }) {
                    dirty = true
                    exitSelect()
                }
            })
            head.addView(sym("\\uD83D\\uDDD1", Color.parseColor("#FF5252")) { confirmDelete() })""",
"""            head.addView(sym("\\u2715", ic) { exitSelect() })
            val cnt = TextView(this)
            cnt.text = selected.size.toString() + " selected"
            cnt.textSize = 17f
            cnt.setTextColor(tc)
            selCountTv = cnt
            head.addView(cnt, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\\u2611", ic) { selectAll() })
            if (conversionSelectMode) {
                head.addView(sym("\\uD83C\\uDFB5", ic) {
                    val chosen = shown.filter { selected.contains(it.uri.toString()) }
                    if (chosen.isEmpty()) {
                        toast("Select videos first")
                    } else {
                        Hooks.convert(this, chosen.map { it.uri }, chosen.map { it.name }) {
                            dirty = true
                            exitSelect()
                        }
                    }
                })
            } else {
                head.addView(sym("\\u2B50", ic) { favSelected() })
                head.addView(sym("\\uD83D\\uDDD1", Color.parseColor("#FF5252")) { confirmDelete() })
            }""")

rep("main", """            brand.setOnLongClickListener {
                selectMode = true
                selected.clear()
                toast("Select videos for multiple conversion")
                showHome()
                true
            }""",
"""            brand.setOnLongClickListener {
                selectMode = true
                conversionSelectMode = true
                tab = 0
                openFolder = null
                searchOn = false
                query = ""
                selected.clear()
                toast("Video conversion mode: select videos")
                showHome()
                true
            }""")

rep("main", """        root.addView(navRow)

        if (searchOn) {""",
"""        if (!conversionSelectMode) root.addView(navRow)

        if (searchOn && !conversionSelectMode) {""")

rep("main", """        root.addView(miniBar, mlp)

        renderList()""",
"""        if (!conversionSelectMode) root.addView(miniBar, mlp)

        renderList()""")

rep("main", """        if (q.isNotEmpty()) {
            shown = (videos + audios).filter { it.name.lowercase().contains(q) }""",
"""        if (conversionSelectMode) {
            shown = videos
            for (v in shown) items.add(itemOf(v, "V"))
        } else if (q.isNotEmpty()) {
            shown = (videos + audios).filter { it.name.lowercase().contains(q) }""")

rep("main", """        v.setOnClickListener { f() }
        return v
    }

    private fun tabBtn""",
"""        v.setOnClickListener {
            if (t.contains("\\u2699")) {
                v.animate().cancel()
                v.rotation = 0f
                v.animate().rotationBy(360f).setDuration(700L).start()
            }
            f()
        }
        return v
    }

    private fun tabBtn""")

# PLAYER: status bar only, not navigation bar
rep("player", """    private var systemBarsVisible = false
    private var audioSettingsAnimator""",
"""    private var systemBarsVisible = false
    private var lastPlayingState: Boolean? = null
    private var audioSettingsAnimator""")

rep("player", """    @Suppress("DEPRECATION")
    private fun hideBars() {
        systemBarsVisible = false
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }""",
"""    @Suppress("DEPRECATION")
    private fun hideBars() {
        systemBarsVisible = false
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }""")

rep("player", """    private fun showControls() {
        showBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 2600)
    }

    private fun hideControls() {
        hideBars()
        topBar.visibility = View.GONE
        controls.visibility = View.GONE
        lockBtn.visibility = View.GONE
    }""",
"""    private fun showPausedControls() {
        hideBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        ui.removeCallbacks(hideRun)
    }

    private fun showControls() {
        if (!Engine.isPlaying()) {
            showPausedControls()
            return
        }
        showBars()
        if (locked) {
            lockBtn.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            lockBtn.visibility = View.VISIBLE
        }
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 5000L)
    }

    private fun hideControls() {
        hideBars()
        topBar.visibility = View.GONE
        controls.visibility = View.GONE
        lockBtn.visibility = View.GONE
        ui.removeCallbacks(hideRun)
    }

    private fun applyPlaybackUi(playing: Boolean) {
        if (playing) hideControls() else showPausedControls()
    }""")

rep("player", """    private fun togglePlay() {
        Engine.toggle(this)
        showControls()
    }""",
"""    private fun togglePlay() {
        Engine.toggle(this)
        ui.post {
            val playing = Engine.isPlaying()
            lastPlayingState = playing
            applyPlaybackUi(playing)
        }
    }""")

rep("player", """                playBtn.text = if (p.isPlaying) "\\u23F8" else "\\u25B6"
                if (::audioModeSeek.isInitialized) {""",
"""                playBtn.text = if (p.isPlaying) "\\u23F8" else "\\u25B6"
                if (lastPlayingState != p.isPlaying) {
                    lastPlayingState = p.isPlaying
                    applyPlaybackUi(p.isPlaying)
                }
                if (::audioModeSeek.isInitialized) {""")

rep("player", """            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!controlsVisible()) {
                    showControls() // first tap: controls + Android system bars
                } else if (systemBarsVisible) {
                    hideBars() // next tap: hide only Android bars; keep controls visible
                } else {
                    showBars()
                }
                return true
            }""",
"""            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (Engine.isPlaying()) {
                    if (!controlsVisible()) {
                        showControls()
                    } else if (systemBarsVisible) {
                        hideBars()
                        ui.removeCallbacks(hideRun)
                        ui.postDelayed(hideRun, 5000L)
                    } else {
                        hideControls()
                    }
                } else {
                    if (systemBarsVisible) hideBars() else showBars()
                }
                return true
            }""")

rep("player", """        Engine.listener = lis
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this) else PlaybackService.stop(this)""",
"""        Engine.listener = lis
        lastPlayingState = null
        if (Engine.active && Engine.isPlaying()) PlaybackService.start(this) else PlaybackService.stop(this)""")

rep("player", """        playerSettingsGear?.let { gear ->
            if (Prefs.themeMode(this) == "rgb") {
                playerGearAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 5000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { gear.rotation = it.animatedValue as Float }
                    start()
                }
            }
        }""",
"""        playerSettingsGear?.let { gear ->
            playerGearAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 5000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { gear.rotation = it.animatedValue as Float }
                start()
            }
        }""")

# Write only after every source match has been validated.
stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
for key, path in (("main", main), ("player", player)):
    backup = path.with_name(path.name + ".before-ui-" + stamp + ".bak")
    shutil.copy2(path, backup)
    path.write_text(out[key])
    print("Updated:", path)
    print("Backup:", backup)

print("PATCH_APPLIED")
