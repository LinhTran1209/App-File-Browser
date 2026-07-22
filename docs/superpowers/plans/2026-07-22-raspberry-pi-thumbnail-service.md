# Raspberry Pi Video Thumbnail Service Implementation Plan

1. Inspect the Pi, File Browser service, media root, and available ports.
2. Implement the Go companion service with File Browser token validation, strict path validation, one-worker generation, deduplication, and a 100 MB LRU cache.
3. Install `ffmpegthumbnailer`, `ffmpeg`, and Go on the Pi; deploy the binary and hardened systemd unit.
4. Add a resilient Android companion client: cache-only reads in folder lists and generation requests only after opening a video.
5. Restore the native Media3 video surface so thumbnail work cannot interfere with playback.
6. Build the APK, install it through ADB, verify the service health and an authenticated thumbnail flow, then commit and push the changes.
