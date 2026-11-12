# RajPlayer 1.3 — List refresh and seek controls

## Changes
- Added pull-to-refresh to the video/audio/folder list using SwipeRefreshLayout.
- MediaStore queries run on the existing background executor.
- Prevented overlapping media scans.
- Publish refreshed video/audio lists only after both queries complete, so a failed scan does not erase the previous list.
- Added configurable seek interval shared by audio and video: 10s, 20s, 30s, 40s, 60s, or custom 10–240 seconds.
- Added audio backward/forward seek buttons using the configured interval.
- Preserved existing theme, RGB, color picker, diamond animation and title-bar code.

## Notes
- This patch does not yet replace LibVLC with Media3. The playback engine migration is a separate change because the existing player activities, video surface, tracks, audio service, and controls are coupled to LibVLC.
- A successful build on GitHub Actions is still required to verify dependency resolution and Android compilation.
