#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="${1:-$HOME/rajplayer}"
ZIP="${2:-$HOME/storage/downloads/RajPlayer-Media3-Source.zip}"
BRANCH="media3-engine-migration"
REMOTE="https://github.com/Ayanraj93/rajplayer.git"

pkg install -y git unzip

if [ ! -d "$REPO/.git" ]; then
  git clone "$REMOTE" "$REPO"
fi
if [ ! -f "$ZIP" ]; then
  echo "ZIP not found: $ZIP" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP"
SRC="$TMP/rajplayer"
if [ ! -d "$SRC/app/src/main/java/com/raj/rajplayer" ]; then
  echo "Unexpected ZIP layout: expected rajplayer/app/src/main/java/com/raj/rajplayer" >&2
  exit 1
fi

cd "$REPO"
git fetch origin
git checkout main
git pull --ff-only origin main
git switch -C "$BRANCH"
cp -a "$SRC/." "$REPO/"
bash ./verify_media3_engine.sh

git add -A
if git diff --cached --quiet; then
  echo "No source changes to commit."
else
  git commit -m "Migrate RajPlayer playback engine to AndroidX Media3"
fi

echo "Pushing branch $BRANCH ..."
git push -u origin "$BRANCH"
echo "Done. Open GitHub Actions to monitor the Media3 APK build."
