# Video Thumbnail On-Play Design

## Goal

Keep folders containing many long remote videos immediately responsive and prevent thumbnail work from competing with video playback.

## Root Cause

The v1.3.0 list creates a `MediaMetadataRetriever` for every uncached visible video. Each retriever opens the remote file and seeks to 25% of its duration. Multiple long videos therefore create concurrent range requests and decoder work, saturating the network/server and delaying ExoPlayer.

## Approved Design

- The browser never opens a remote video to generate a thumbnail.
- An uncached video displays the existing video icon immediately.
- Cached video thumbnails are decoded from disk normally.
- When a user opens a video and ExoPlayer renders its first frame, the player captures that already-rendered frame from a `TextureView`.
- The captured frame is scaled to at most 320 pixels on its longest edge, compressed as JPEG, and written through `AppCacheManager`.
- Returning to the folder displays the cached thumbnail without network work.
- Image thumbnail behavior remains unchanged.
- The existing automatic 100 MB LRU thumbnail cache remains unchanged.

## Error Handling

Thumbnail capture is best-effort. A missing frame, failed bitmap capture, or failed cache write must not interrupt playback or show an error. The browser continues to display the video icon.

## Constraints

- Keep app version at `1.3.0` / version code `4`.
- Do not change `README.md`.
- Do not add background video thumbnail requests.
- Build and install the debug APK, but do not run automated tests per user request.
