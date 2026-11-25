from pathlib import Path
from datetime import datetime
import shutil

p = Path("app/src/main/java/com/raj/rajplayer/PlayerActivity.kt")
s = p.read_text()
original = s

def replace_once(old, new, name):
    global s
    count = s.count(old)
    if count != 1:
        raise RuntimeError(f"{name}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)

replace_once(
    "    private var playerSettingsGear: TextView? = null\n",
    """    private var playerSettingsGear: TextView? = null
    private var headphoneButton: TextView? = null
    private var headphoneRgbAnimator: ValueAnimator? = null
    private var headphoneBlinkAnimator: ValueAnimator? = null
""",
    "animation fields"
)

replace_once(
    'if (Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ICON)) {',
    r'''if (sym != "\uD83C\uDFA7" && Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ICON)) {''',
    "RGB condition"
)

replace_once(
    """        return t
    }

    @Suppress("DEPRECATION")
    private fun hideBars()""",
    """        return t
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
    private fun hideBars()""",
    "animation methods"
)

replace_once(
    r'''        topBar.addView(ib("\u2139\uFE0F", 20f) {
            showInfo()
            showControls()
        })''',
    r'''        val infoButton = ib("\u2139\uFE0F", 20f) {
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
        topBar.addView(infoButton)''',
    "Info icon"
)

replace_once(
    r'''        topBar.addView(ib("\uD83C\uDFA7", 20f) {
            enterAudioMode()
        })''',
    r'''        val headphone = ib("\uD83C\uDFA7", 20f) {
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
        topBar.addView(headphone)''',
    "Headphone icon"
)

replace_once(
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
    "resume/pause lifecycle"
)

replace_once(
    """    override fun onDestroy() {
        titleRgbAnimator?.cancel()""",
    """    override fun onDestroy() {
        stopHeadphoneAnimations()
        titleRgbAnimator?.cancel()""",
    "destroy lifecycle"
)

if s == original:
    raise RuntimeError("No changes produced")

backup = p.with_name(
    p.name + ".before-icon-fix-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".bak"
)
shutil.copy2(p, backup)
p.write_text(s)

print("ICON_PATCH_APPLIED")
print("Backup:", backup)
