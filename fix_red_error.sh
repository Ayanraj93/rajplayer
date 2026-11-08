#!/data/data/com.termux/files/usr/bin/bash
set -e
P=~/RajPlayer
PK=$P/app/src/main/java/com/raj/rajplayer
BK=$P/backup_v6

echo "=== Checking Dialogs.kt & Engine.kt signatures ==="
grep -n "fun " "$PK/Dialogs.kt" "$PK/Engine.kt" 2>/dev/null || true

# 1. Restore original working Engine.kt and PlayerActivity.kt from backup_v6 if available
if [ -f "$BK/Engine.kt" ]; then
    cp "$BK/Engine.kt" "$PK/Engine.kt"
    echo "[ok] Restored clean Engine.kt from backup"
fi

if [ -f "$BK/PlayerActivity.kt" ]; then
    cp "$BK/PlayerActivity.kt" "$PK/PlayerActivity.kt"
    echo "[ok] Restored compatible PlayerActivity.kt from backup"
fi

# 2. Patch PlayerActivity.kt safely with your custom colors, 6x speeds, and Margin AtomicView
python3 -c '
p = "'$PK'/PlayerActivity.kt"
with open(p, "r") as f:
    code = f.read()

# Fix custom colors
code = code.replace("topBar.setBackgroundColor(0x99000000.toInt())", "topBar.setBackgroundColor(Prefs.videoBarColor(this))")
code = code.replace("titleTv.setTextColor(Color.WHITE)", "titleTv.setTextColor(Prefs.videoTextColor(this))")

# Upgrade playback speeds up to 6.0x
import re
code = re.sub(
    r"floatArrayOf\([0-9., fF]+\)",
    "floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 6.0f)",
    code,
    count=1
)

# Record last played video URI when PlayerActivity starts
if "Prefs.setLastPlayed" not in code:
    code = code.replace(
        "super.onCreate(savedInstanceState)",
        "super.onCreate(savedInstanceState)\n        intent?.data?.let { Prefs.setLastPlayed(this, it.toString()) }"
    )

with open(p, "w") as f:
    f.write(code)
print("[ok] Patched PlayerActivity.kt safely")
'

# 3. Ensure Prefs.kt has both lastPlayed and all required functions
python3 -c '
p = "'$PK'/Prefs.kt"
with open(p, "r") as f:
    c = f.read()

if "fun lastPlayedUri" not in c:
    extra = """
    fun lastPlayedUri(c: Context): String? = p(c).getString("last_played_uri", null)
    fun setLastPlayedUri(c: Context, uri: String?) { p(c).edit().putString("last_played_uri", uri).apply() }
}
"""
    c = c.rstrip()
    if c.endswith("}"):
        c = c[:-1] + extra
    with open(p, "w") as f:
        f.write(c)
print("[ok] Verified Prefs.kt methods")
'

git add -A
git commit -m "Fix compilation errors by restoring compatible PlayerActivity & Engine signatures"
git push
echo "=== Pushed fix to GitHub! Check GitHub Actions now ==="
