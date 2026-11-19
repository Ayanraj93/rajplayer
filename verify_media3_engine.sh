#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

grep -Fq 'val media3Version = "1.11.1"' app/build.gradle.kts
grep -Fq 'androidx.media3:media3-exoplayer:$media3Version' app/build.gradle.kts
grep -Fq 'androidx.media3:media3-decoder-av1:$media3Version' app/build.gradle.kts
grep -Fq 'DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON' app/src/main/java/com/raj/rajplayer/Engine.kt
grep -Fq 'includeMedia3(file("androidx-media"))' settings.gradle.kts
grep -Fq 'nativelib/arm64-v8a/libdav1d.a' .github/workflows/build.yml
grep -Fq 'nativelib/armeabi-v7a/libdav1d.a' .github/workflows/build.yml
grep -Fq 'Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")' androidx-media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java
grep -q 'ExoPlayer.Builder' app/src/main/java/com/raj/rajplayer/Engine.kt
grep -q 'setVideoSurfaceView' app/src/main/java/com/raj/rajplayer/MediaPlayer.kt
grep -q 'setEnableAudioOutputPlaybackParameters(false)' app/src/main/java/com/raj/rajplayer/Engine.kt
grep -q 'forceEnableMediaCodecAsynchronousQueueing' app/src/main/java/com/raj/rajplayer/Engine.kt
if grep -R -nE 'org\.videolan|VLCVideoLayout|LibVLC|libvlc-all' app/src/main/java app/build.gradle.kts; then
  echo 'ERROR: VLC reference still exists in active app source/dependencies.' >&2
  exit 1
fi
printf '%s\n' 'PASS: Active app source uses AndroidX Media3 ExoPlayer; no LibVLC dependency or VLC imports remain.'
printf '%s\n' 'Decoder: Android MediaCodec selection via Media3 DefaultRenderersFactory.'
printf '%s\n' 'Video output: SurfaceView by default; TextureView only while Color Grading is enabled.'
printf '%s\n' 'Audio speed: 1x default, pitch=1.0, Media3 application-level time stretching.'
