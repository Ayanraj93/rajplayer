from pathlib import Path
from datetime import datetime
import re
import shutil

p = Path("app/src/main/java/com/raj/rajplayer/PlayerActivity.kt")
s = p.read_text()
original = s

def once(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {n}")
    s = s.replace(old, new, 1)

# 1. Animation fields
once(
    "    private var playerSettingsGear: TextView? = null\n",
    """    private var playerSettingsGear: TextView? = null
    private var headphoneButton: TextView? = null
    private var headphoneRgbAnimator: ValueAnimator? = null
    private var headphoneBlinkAnimator: ValueAnimator? = null
""",
    "animation fields"
)

# 2. Do not let generic RGB ticker override headphone RGB
once(
    'if (Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ICON)) {',
    'if (sym != "\\\\uD83C\\\\uDFA7" && Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ICON)) {',
    "generic RGB exclusion"
)

# 3. Animation lifecycle helpers
anchor = """        return t
    }

    @Suppress("DEPRECATION")
    private fun hideBars()"""
replacement = """        return t
    }

    private fun startHeadphoneAnimations() {
        val icon = headphoneButton ?: return
        headphoneRgbAnimator?.cancel()
        headphoneBlinkAnimator?.cancel()

        headphoneRgbAnimator = ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE,
            Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                icon.setTextColor(it.animatedValue as Int)
            }
            start()
        }

        headphoneBlinkAnimator = ValueAnimator.ofFloat(0.82f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                icon.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopHeadphoneAnimations() {
        headphoneRgbAnimator?.cancel()
        headphoneRgbAnimator = null
        headphoneBlinkAnimator?.cancel()
        headphoneBlinkAnimator = null
        headphoneButton?.alpha = 1f
    }

    @Suppress("DEPRECATION")
    private fun hideBars()"""
once(anchor, replacement, "animation methods")

# 4. Info and headphone buttons
pattern = re.compile(
    r'        topBar\.addView\(ib\("\\\\u2139\\\\uFE0F", 20f\) \{.*?'
    r'\}\)\s*'
    r'        topBar\.addView\(ib\("\\\\uD83D\\\\uDD04", 20f\) \{',
    re.S
)
m = pattern.search(s)
if not m:
    raise RuntimeError("Info/orientation button block not found")

replacement = r'''        val infoButton = ib("\u2139\uFE0F", 20f) {
            showInfo()
            showControls()
        }
        infoButton.setOnClickListener {
            infoButton.animate().cancel()
            infoButton.rotation = 0f
            infoButton.animate().rotationBy(360f).setDuration(650L).start()
            showInfo()
            showControls()
        }
        infoButton.setOnLongClickListener {
            decoderDialog()
            true
        }
        topBar.addView(infoButton)

        topBar.addView(ib("\uD83D\uDD04", 20f) {'''
s = s[:m.start()] + replacement + s[m.end():]

old = r'''        topBar.addView(ib("\uD83C\uDFA7", 20f) {
            enterAudioMode()
        })'''
new = r'''        val headphone = ib("\uD83C\uDFA7", 20f) {
            enterAudioMode()
        }
        headphone.minWidth = dp(48)
        headphone.minHeight = dp(48)
        headphone.setPadding(dp(12), dp(10), dp(12), dp(10))
        headphone.setOnLongClickListener {
            Engine.bgAudio = !Engine.bgAudio
            Prefs.setBgAudio(this, Engine.bgAudio)
            if (Engine.bgAudio && Engine.isPlaying()) {
                PlaybackService.start(this)
            } else if (!Engine.bgAudio) {
                PlaybackService.stop(this)
            }
            showMsg("Background audio " + onOff(Engine.bgAudio))
            true
        }
        headphoneButton = headphone
        topBar.addView(headphone)'''
once(old, new, "headphone button")

# 5. Start/stop animations with activity lifecycle
once(
    """        hideBars()
    }

    override fun onPause() {
        playerGearAnimator?.cancel()""",
    """        startHeadphoneAnimations()
        hideBars()
    }

    override fun onPause() {
        stopHeadphoneAnimations()
        playerGearAnimator?.cancel()""",
    "animation lifecycle"
)

once(
    """    override fun onDestroy() {
        titleRgbAnimator?.cancel()""",
    """    override fun onDestroy() {
        stopHeadphoneAnimations()
        titleRgbAnimator?.cancel()""",
    "destroy cleanup"
)

# 6. Remove nonfunctional Audio Sync and duplicate Video Info options
start = s.find("    private fun showMenu() {")
end = s.find("    private fun toggleMargin() {", start)
if start < 0 or end < 0:
    raise RuntimeError("showMenu boundaries not found")

menu = r'''    private fun showMenu() {
        val abLabel = if (abA < 0) "A-B Repeat: set point A"
        else if (abB < 0) "A-B Repeat: set point B"
        else "A-B Repeat: clear"

        val opts = arrayOf(
            "Speed (ruler)  " + rateLabel(rate),
            "Decoder: " + Tuning.decLabel(Tuning.decMode(this)),
            "Smart auto-fix: " + onOff(Tuning.autoFix(this)),
            "Equalizer Presets",
            "Audio Boost (up to 200%)",
            "Sleep Timer",
            "Colour Grading / HDR",
            "Load External Subtitle",
            "Add Bookmark",
            "View Bookmarks",
            abLabel,
            "Background Audio: " + onOff(Engine.bgAudio),
            "Picture-in-Picture",
            "Convert to Audio (MP3, M4A...)",
            "Margin Animation: " + onOff(Prefs.videoMarginAnim(this)),
            "Auto-rotate to match video: " + onOff(Prefs.autoOrient(this)),
            "Allow Background (Battery)"
        )

        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> openSpeed()
                    1 -> decoderDialog()
                    2 -> {
                        Tuning.setAutoFix(this, !Tuning.autoFix(this))
                        showMsg("Smart auto-fix " + onOff(Tuning.autoFix(this)))
                    }
                    3 -> Dialogs.eq(this)
                    4 -> Dialogs.boost(this)
                    5 -> Dialogs.sleep(this)
                    6 -> openGrade()
                    7 -> pickSub()
                    8 -> addBookmark()
                    9 -> bookmarksDialog()
                    10 -> abStep()
                    11 -> {
                        Engine.bgAudio = !Engine.bgAudio
                        Prefs.setBgAudio(this, Engine.bgAudio)
                        if (Engine.bgAudio && Engine.isPlaying()) PlaybackService.start(this)
                        else if (!Engine.bgAudio) PlaybackService.stop(this)
                        showMsg("Background audio " + onOff(Engine.bgAudio))
                    }
                    12 -> enterPip()
                    13 -> Hooks.convert(this, listOf(Engine.cur()), listOf(Engine.title), null)
                    14 -> toggleMargin()
                    15 -> {
                        Prefs.setAutoOrient(this, !Prefs.autoOrient(this))
                        if (Prefs.autoOrient(this)) Orient.setFor(this, Engine.cur()) { videoPortrait = it }
                        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showMsg("Auto-rotate " + onOff(Prefs.autoOrient(this)))
                    }
                    16 -> Power.request(this)
                    else -> {}
                }
            }
            .show()
    }

'''
s = s[:start] + menu + s[end:]

if s == original:
    raise RuntimeError("No changes produced")

stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
backup = p.with_name(p.name + ".before-new-ui-" + stamp + ".bak")
shutil.copy2(p, backup)
p.write_text(s)

print("PATCH_APPLIED")
print("Backup:", backup)
print("Updated:", p)
