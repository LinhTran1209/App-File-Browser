# File Server for Android

File Server is a native Android client for browsing, streaming, transferring, and administering files exposed by a [File Browser](https://filebrowser.org/) server. It is designed for private networks and lightweight hosts such as Raspberry Pi, while remaining useful with any compatible File Browser deployment.

The Android client uses paginated directory browsing, authenticated media streaming, bounded preview caching, resumable transfer tracking, and server-side permissions. Optional Raspberry Pi helpers provide low-cost video thumbnails and automatic USB mounting.

Current application version: **1.5.0**

## Screenshots

| Servers | Browser | Transfers |
| --- | --- | --- |
| ![Saved servers](docs/screenshots/servers.png) | ![File browser](docs/screenshots/browser.png) | ![Transfers](docs/screenshots/transfers.png) |

## Features

### Server and session management

- Save and monitor multiple File Browser servers.
- Detect online and offline endpoints before opening them.
- Authenticate with File Browser accounts and automatically renew sessions.
- Store credentials with AES-GCM keys backed by Android Keystore.
- Support HTTP for trusted LANs and HTTPS for remote or untrusted networks.

### File browsing and operations

- Browse directories in list or grid mode.
- Sort resources by name and preserve the visible list position.
- Navigate with an editable, horizontally scrollable path.
- Pull down to refresh the current directory.
- Create folders and upload individual files or complete directory trees.
- Download, rename, move, delete, and share resources according to server permissions.
- Display disk usage for the active storage path.
- Track uploads and downloads with progress, retry, dismiss, open, and clear actions.

### Preview and playback

- Preview common image formats with neighboring-image navigation.
- Read text, Markdown, source code, JSON, logs, environment files, and other text-based formats with scrolling.
- Preview PDF and supported document types.
- Stream authenticated audio and video through Android Media3.
- Play, pause, seek, jump backward or forward, and enter immersive landscape fullscreen.
- Recognize common video containers, including MP4, MOV, MKV, WebM, AVI, M2TS, and TS.
- Cache image and video thumbnails with an automatic least-recently-used size limit.

### Administration and customization

- Manage profile options, shares, users, permissions, and supported global server settings from the app.
- Use Vietnamese or English.
- Follow the system theme or force light/dark mode.
- Choose list/grid layout, download directory, and folder icon style.

## Architecture

The project is split into a native Android application and optional Linux helpers:

```text
App-File-Browser/
|-- app/
|   `-- src/main/java/com/j2team/fileserver/
|       |-- core/
|       |   |-- cache/          Preview and thumbnail cache policies
|       |   |-- model/          Server, resource, permission, and transfer models
|       |   |-- network/        File Browser and thumbnail HTTP clients
|       |   |-- session/        Authentication, renewal, and encrypted credentials
|       |   `-- ui/             Theme and shared icon resources
|       |-- feature/
|       |   |-- browser/        Directory listing and file operations
|       |   |-- preview/        Image, text, PDF, audio, and video preview
|       |   |-- servers/        Saved server profiles
|       |   |-- serversettings/ Native File Browser administration
|       |   |-- settings/       Local application preferences
|       |   `-- transfers/      Upload and download queues
|       |-- FileServerApp.kt
|       `-- MainActivity.kt
|-- server/
|   |-- thumbnail-service/      Low-priority ffmpegthumbnailer companion service
|   `-- usb-mount/              udev/systemd USB mount and recovery manager
|-- docs/
|   |-- screenshots/
|   `-- superpowers/            Product specification and implementation plan
|-- build.gradle.kts
`-- settings.gradle.kts
```

The Android app communicates directly with File Browser's HTTP API. Authentication tokens are attached to API and Media3 streaming requests. Thumbnail requests can optionally be delegated to the companion service so large video folders remain responsive on the phone.

## Requirements

### Android application

- Android 8.0 or newer (API 26+)
- A reachable File Browser server
- A File Browser user account with the required permissions

### Local development

- JDK 17
- Android SDK 37
- Android platform tools for ADB installation
- A compatible Gradle 9 installation or the included Gradle wrapper

### Optional Raspberry Pi helpers

- A Linux host using systemd and udev
- `ffmpegthumbnailer` for server-generated video thumbnails
- Go when rebuilding the thumbnail companion from source
- Standard filesystem tools such as `blkid`, `lsblk`, `mount`, and `findmnt`
- `dislocker` for BitLocker volumes
- Appropriate filesystem packages for the USB formats in use (for example exFAT or NTFS support)

## Build

From the repository root, set `JAVA_HOME` and `ANDROID_HOME`, then run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/file-server-v1.5.0-debug.apk
```

Install or update it on a connected device:

```powershell
adb install -r app/build/outputs/apk/debug/file-server-v1.5.0-debug.apk
```

Run only the JVM unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

## Raspberry Pi video thumbnail service

The companion in `server/thumbnail-service` produces small cached thumbnails without making the Android device decode long videos.

Build and install the binary on the server, then adapt the supplied systemd unit:

```text
server/thumbnail-service/file-server-thumbnail.service
```

Important command-line options:

- `-listen`: HTTP listen address; default `127.0.0.1:8890`
- `-root`: allowed media root; default `/media`
- `-cache`: generated thumbnail cache; default `/var/cache/file-server-thumbnail`
- `-filebrowser`: local File Browser URL; default `http://127.0.0.1:8888`

The supplied service runs with reduced CPU and I/O priority, a memory limit, read-only media access, and a writable bounded cache directory.

## Raspberry Pi automatic USB mounting

The assets in `server/usb-mount` detect removable volumes, mount supported ordinary filesystems, unlock configured BitLocker volumes, remove stale mounts, and restart dependent services after topology changes.

Install on the Raspberry Pi:

```bash
cd server/usb-mount
sudo ./install.sh
```

Configuration and secrets:

- Mount policy: `/etc/file-server/usb-mount.conf`
- Default BitLocker key: `/etc/file-server/bitlocker.key`
- Per-volume BitLocker keys: `/etc/file-server/bitlocker-keys/<UUID>.key`
- Ordinary mount root: `/media/file-server`
- Compatibility BitLocker mount: `/media/usb_bitlocker`
- Thumbnail cache: `/var/cache/file-server-thumbnail`

BitLocker key files must be owned by `root` and have mode `0600`. The mount manager supplies the key through standard input so it does not appear in process arguments or logs.

## Security notes

- TLS certificate validation is enabled; use HTTPS outside a trusted LAN.
- Saved credentials are encrypted with AES-GCM and an Android Keystore-backed key.
- Password character arrays and temporary plaintext buffers are cleared after use where possible.
- Credentials, tokens, and BitLocker keys are not intentionally written to application transfer history or service logs.
- File operations and administration remain subject to permissions enforced by the File Browser server.
- The optional thumbnail service restricts media access to its configured root.

## Troubleshooting

- **Server is offline:** verify the scheme, host, port, firewall, and File Browser service.
- **Login is rejected:** confirm the username/password and that the account is enabled.
- **A preview does not open:** verify the MIME type, server permission, and Android codec support.
- **Video thumbnails are missing:** check `file-server-thumbnail.service`, `ffmpegthumbnailer`, and `/var/cache/file-server-thumbnail`.
- **A USB disk is missing:** inspect `journalctl -u file-server-usb-reconcile.service` and verify filesystem tools or the BitLocker key.
- **A transfer fails:** keep the server reachable, retry the item, and confirm write permissions and free disk space.

## Release artifacts

GitHub Releases can attach the generated APK for direct installation. Debug APKs are suitable for testing; production distribution should use a dedicated signing configuration and a release build.
