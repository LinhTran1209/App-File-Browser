# File Server for Android

File Server is a native Android client for browsing and managing files exposed by a [File Browser](https://filebrowser.org/) server. It is designed for local networks and lightweight hosts such as Raspberry Pi, with paginated browsing, streamed media playback, and bounded preview caching.

## Features

- Manage multiple File Browser servers with online/offline status.
- Browse folders in list or grid mode, sort by name, and pull to refresh.
- Upload and download files or folders with progress tracking.
- Create, rename, move, and delete resources according to server permissions.
- Preview images, video, audio, PDF, text, source code, and common document formats.
- Stream authenticated media with playback controls and fullscreen video.
- Use Vietnamese or English, light or dark themes, and selectable folder icons.
- Keep credentials in process memory and automatically limit thumbnail cache usage.

## Project Structure

```text
app/src/main/java/com/j2team/fileserver/
├── core/
│   ├── cache/       # Thumbnail and temporary-file cache management
│   ├── model/       # Server, resource, permission, and listing models
│   ├── network/     # File Browser HTTP API client
│   ├── session/     # Authentication and process-scoped sessions
│   └── ui/          # Theme and shared icon definitions
├── feature/
│   ├── browser/     # Directory browsing and file operations
│   ├── preview/     # Image, text, PDF, audio, and video preview
│   ├── servers/     # Saved server profiles
│   ├── settings/    # App preferences and localization
│   └── transfers/   # Upload and download queues
├── FileServerApp.kt
└── MainActivity.kt
```

## Requirements

- Android 8.0 or newer (API 26+)
- A reachable File Browser server and valid user account
- JDK 17 for local builds
- Android SDK API 37

Plain HTTP is supported for trusted local networks. Use HTTPS when connecting over an untrusted network.

## Build

Clone the repository, configure `ANDROID_HOME`, and run:

```powershell
.\gradlew.bat assembleDebug
```

If the Gradle wrapper is unavailable, run the project with a compatible Gradle 9 installation:

```powershell
gradle assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected Android device with:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Technology

- Kotlin and Jetpack Compose
- Material 3
- Android Media3 / ExoPlayer
- Kotlin Coroutines
- Storage Access Framework

## Security

- TLS certificate validation is not disabled.
- Credentials and tokens are not written to logs or transfer history.
- Sessions are kept only while the app process remains alive.
- File operations remain subject to permissions enforced by the File Browser server.

## Version

Current version: **1.2.0**
