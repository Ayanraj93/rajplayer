# RajPlayer 1.4 — Media3 engine migration

## Playback engine
- Replaced the active LibVLC playback engine with AndroidX Media3 ExoPlayer 1.11.1.
- Uses Android MediaCodec decoder selection through Media3's default renderer factory.
- Uses SurfaceView for normal playback; existing colour grading continues to use TextureView only while enabled.
- Enables MediaCodec asynchronous queueing and decoder fallback.
- Uses Media3/Sonic time stretching with pitch set to 1.0; audio and video default to 4x.
- Uses Media3's playback clock and closest-sync seeking; avoids reloading/re-attaching the surface on seeks.
- Counts dropped video frames and displays performance notices; does not silently lower playback speed or switch to software decoding mid-seek.

## Existing UI/features
- Keeps the existing RGB themes, custom color settings, Diamond/Atomic animation, video/audio title bars, playlists, gestures, PiP, bookmarks, background audio, and list refresh/seek controls.
- The Diamond/Atomic animation is paused above 2x to reduce GPU work during high-speed playback.

## Limitations to validate
- VLC's HW+ toggle has no direct Media3 equivalent. Media3 selects Android MediaCodec decoders and the actual decoder name is shown in the decoder information dialog.
- Fast seeking uses the closest sync/key frame, so the target may land near rather than exactly on the requested timestamp.
- Media3's automatic A/V timestamp synchronization is used. Manual audio offset is not implemented in this migration.
- 4x playback is a requested default, not a guarantee of zero frame drops for every codec, resolution, or device.
- Run `bash verify_media3_engine.sh` and let GitHub Actions compile the APK before installing it.
