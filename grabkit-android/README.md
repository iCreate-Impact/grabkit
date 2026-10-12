# GrabKit for Android

On-device port of the GrabKit web app: YouTube, TikTok (no watermark), Instagram, X, Facebook.
No backend. Kotlin + Compose + Hilt + OkHttp; yt-dlp runs in-process via Chaquopy; FFmpeg via an ffmpeg-kit build.

## Build
1. Open in Android Studio (Koala+), let it generate the Gradle wrapper (or run `gradle wrapper --gradle-version 8.7`).
2. Needs a host `python3` (>= 3.8) for Chaquopy's pip step.
3. `./gradlew assembleDebug`

Switches live in `gradle.properties`: `grabkit.ffmpeg` (FFmpeg AAR coordinate) and `grabkit.abis`.

## Layout
- `extractor/` TikTok, Instagram, Facebook, X (native), YouTube (yt-dlp), `Resolver` (normalize -> detect -> native -> yt-dlp fallback)
- `service/` `DownloadEngine` (pipeline), `DownloadQueue` (shared StateFlows), `DownloadService` (foreground host)
- `src/main/python/grabkit_ytdlp.py` Chaquopy bridge (the spec's `assets/python` is not where Chaquopy reads sources)
- `ui/` Compose screens

## Things you must know
- **ffmpeg-kit is retired.** `com.arthenica:ffmpeg-kit-full` no longer resolves. Default is the community fork `dev.ffmpegkit-maintained:ffmpeg:6.0.3` (same `com.arthenica.ffmpegkit` API). Verify it ships `libmp3lame` and `libwebp`, and which ABIs and minSdk it supports.
- **YouTube needs a JS runtime** (yt-dlp requirement since late 2025). None exists on Android. Drop a QuickJS `qjs` binary in `jniLibs/<abi>/libqjs.so`; the bridge passes it to yt-dlp automatically. Without it YouTube may fail or miss formats. Keep yt-dlp current.
- **TikTok TLS**: OkHttp is given Chrome's cipher order, but Conscrypt still controls the rest of the ClientHello, so it is not a true Chrome fingerprint. TikTok may block it; the app then tries the feed API, then yt-dlp, then shows the combined error.
- Android 5-9 need `WRITE_EXTERNAL_STORAGE` (requested at launch); Android 10+ uses MediaStore with no permission.
- Only the Python bridge was executed (against real yt-dlp with a local fixture). The Kotlin was parser-checked, never compiled against Android.
