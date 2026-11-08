#!/data/data/com.termux/files/usr/bin/bash
set -e
P=~/RajPlayer
PK=$P/app/src/main/java/com/raj/rajplayer
[ -d "$PK" ] || { echo "RajPlayer path nahi mila"; exit 1; }

echo "Adding missing bms & setBms functions to Prefs.kt..."

# Append bms and setBms to Prefs.kt safely if not already present
python3 -c "
prefs_path = '$PK/Prefs.kt'
with open(prefs_path, 'r') as f:
    code = f.read()

if 'fun bms' not in code:
    bms_code = '''
    fun bms(c: Context, key: String): List<Long> {
        val s = p(c).getString(\"bm_\" + key, \"\") ?: \"\"
        if (s.isEmpty()) return emptyList()
        return s.split(\",\").mapNotNull { it.toLongOrNull() }
    }

    fun setBms(c: Context, key: String, v: List<Long>) {
        val e = p(c).edit()
        if (v.isEmpty()) e.remove(\"bm_\" + key) else e.putString(\"bm_\" + key, v.joinToString(\",\"))
        e.apply()
    }
}
'''
    code = code.strip()
    if code.endswith('}'):
        code = code[:-1] + bms_code
    with open(prefs_path, 'w') as f:
        f.write(code)
    print('[ok] bms functions appended successfully.')
else:
    print('[info] bms functions already exist.')
"

git add -A
git commit -m "RajPlayer: Fixed missing bms and setBms methods in Prefs.kt"
git push
echo "Successfully fixed and pushed to GitHub!"
