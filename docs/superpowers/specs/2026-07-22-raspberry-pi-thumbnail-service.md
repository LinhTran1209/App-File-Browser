# Raspberry Pi Video Thumbnail Service

## Goal

Keep browsing and video playback smooth on Android by moving video frame extraction to the Raspberry Pi. The Android app must never decode every video merely because a folder is visible.

## Architecture

- A small Go HTTP service listens on port `8890` on the Raspberry Pi.
- `GET /v1/thumbnail?path=...` returns an existing JPEG thumbnail only. It never starts expensive work.
- `POST /v1/thumbnail?path=...` requests generation after a user opens a video.
- Requests carry the current File Browser `X-Auth` token. The service validates it against the local File Browser API before reading a file.
- Only paths below `/media` are accepted; cleaned paths and symbolic links cannot escape that root.
- One low-priority worker runs `ffmpegthumbnailer`, so multiple videos cannot saturate the Pi.
- The frame is selected at 5 minutes for long videos and at 35% of duration for videos shorter than 5 minutes.
- Output is a low-resolution JPEG (256 px maximum edge). The server cache is capped at 100 MB and evicts least-recently-used files.
- Android downloads cached thumbnails asynchronously into its existing bounded thumbnail cache. A missing or unavailable companion service falls back to the video icon without affecting playback.

## Operations and security

- The service runs as the unprivileged `linh3` user under systemd.
- systemd applies low CPU/I/O priority, a memory limit, read-only media access, and a private temporary directory.
- The health endpoint contains no file information.
- No password or permanent File Browser credential is stored by the companion service.

