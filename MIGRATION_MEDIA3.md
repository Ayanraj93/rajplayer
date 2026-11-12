# RajPlayer Media3 engine migration

## Engine verification

The active `app` source now uses AndroidX Media3 ExoPlayer 1.11.1. The LibVLC dependency and VLC imports have been removed from the active application source. Run `bash verify_media3_engine.sh` to verify the engine wiring.

## Playback pipeline

- ExoPlayer + Android MediaCodec renderer selection.
- SurfaceView output by default; TextureView is used only when the existing Colour Grading mode is enabled.
- MediaCodec asynchronous queueing is explicitly enabled.
- Media3's default hardware-first platform decoder selection is used with decoder fallback enabled for compatibility.
- Playback starts at 4.0x with pitch parameter 1.0. Audio processing is kept in Media3's application-level time-stretch path (Sonic) by disabling AudioOutput playback-parameter delegation.
- Seek requests use CLOSEST_SYNC to reduce seek latency. This can land on the nearest sync/key frame rather than an exact frame; exact seeking may take longer.
- Video late-frame drops are measured through AnalyticsListener. The player does not reload the item or switch to software decoding during a seek, avoiding self-inflicted black frames.

## Important limitations

- VLC's proprietary `HW+` switch has no direct Media3 equivalent. Media3 selects Android platform MediaCodec decoders; it does not promise that every codec/profile is hardware accelerated on every device.
- 4x is the requested default, not a guarantee that every file/device can decode at 4x. 4K, high frame-rate, 10-bit and high-bitrate streams need device testing.
- Media3 synchronizes A/V using media timestamps and its playback clock. The old manual audio-offset UI is informational in this migration; a custom audio-delay AudioProcessor has not been implemented.
- Colour grading intentionally uses TextureView because the existing view-based colour matrix requires it. Normal playback uses SurfaceView.
- The source has not been compiled in this workspace because an Android SDK/Gradle installation is not available here. GitHub Actions is configured to compile and upload the debug APK. Fix any CI compiler errors before installing the APK.
