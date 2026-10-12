# GrabKit for Android

On-device port of GrabKit: YouTube, TikTok (no watermark), Instagram, X, Facebook.  
No backend. No internet permission beyond what the extractors need. No accounts.

**Stack:** Kotlin · Compose · Hilt · OkHttp · Chaquopy (yt-dlp in-process) · ffmpeg-kit

---

## Build

### Requirements

| Tool | Version |
|------|---------|
| Android Studio | Koala (2024.1) or newer |
| Gradle wrapper | 8.7 (generated on first sync) |
| Host Python | 3.8 – 3.11 (Chaquopy pip step) |
| JDK | 17 |

### Steps

```bash
# from the repo root
cd grabkit-android

# generate the Gradle wrapper if it's missing
gradle wrapper --gradle-version 8.7

# build a debug APK
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Gradle switches (`gradle.properties`)

| Property | Default | Description |
|----------|---------|-------------|
| `grabkit.ffmpeg` | `dev.ffmpegkit-maintained:ffmpeg:6.0.3` | FFmpeg AAR coordinate |
| `grabkit.abis` | `arm64-v8a` | Comma-separated ABI list (`arm64-v8a,x86_64`) |

---

## Project layout

```
app/src/main/
├── java/com/grabkit/
│   ├── GrabKitApp.kt           # @HiltAndroidApp
│   ├── MainActivity.kt         # permissions, clipboard prefill, edge-to-edge
│   ├── di/AppModule.kt         # Hilt: provides OkHttpClient
│   ├── model/                  # MediaInfo, DownloadJob data classes
│   ├── extractor/              # one file per platform + Resolver
│   ├── service/                # DownloadService, DownloadQueue, DownloadEngine
│   ├── ui/                     # Compose screens and components
│   └── util/                   # Http, UrlNormalizer, FfmpegRunner, FileUtil, …
└── python/
    └── grabkit_ytdlp.py        # Chaquopy bridge: info() + download()
```

---

## Things you need to know before building

### ffmpeg-kit is retired

`com.arthenica:ffmpeg-kit-full` no longer resolves from Maven.  
The default coordinate is the community fork `dev.ffmpegkit-maintained:ffmpeg:6.0.3`, which ships the same `com.arthenica.ffmpegkit` API. Verify it includes `libmp3lame` and `libwebp` for the formats you need.

You can override the coordinate without touching source:

```properties
# gradle.properties
grabkit.ffmpeg=dev.ffmpegkit-maintained:ffmpeg:6.0.3
```

### YouTube requires a JS runtime

yt-dlp has required a JavaScript runtime since late 2025 to solve challenges.  
No JS runtime ships on Android by default. To enable YouTube:

1. Build or obtain a QuickJS binary for your ABI (`qjs`).
2. Place it at `app/src/main/jniLibs/<abi>/libqjs.so`.
3. The Chaquopy bridge detects and passes it to yt-dlp automatically.

Without it, YouTube may fail silently or return incomplete format lists.

### TikTok TLS fingerprint

OkHttp is configured with Chrome's cipher order, but Conscrypt controls the rest of the `ClientHello`, so it is not a true Chrome fingerprint. TikTok may block it. The extractor falls through three strategies in order:

1. Web page JSON scrape (`playAddr`, no watermark)
2. Mobile feed API
3. yt-dlp fallback

If all three fail, the UI shows the combined error from each attempt.

### Permissions

| Android version | Permission |
|-----------------|-----------|
| API 21 – 28 (Android 5 – 9) | `WRITE_EXTERNAL_STORAGE` — requested at launch |
| API 29+ (Android 10+) | MediaStore — no permission needed |

---

## Architecture

```
URL input
  └─▶ UrlNormalizer          unwraps t.co, vm.tiktok.com, fb.watch, …
        └─▶ PlatformDetector  TikTok / Instagram / Facebook / X / YouTube / unknown
              └─▶ Resolver     native extractor → yt-dlp fallback
                    └─▶ DownloadEngine   download → ffmpeg merge → MediaStore
```

- **`DownloadQueue`** is a Hilt singleton holding shared `StateFlow`s for jobs and history. The UI observes it directly without binding the service.
- **`DownloadService`** is a foreground service that hosts the engine and keeps the process alive during long downloads.
- **`YtDlpBridge`** calls into `grabkit_ytdlp.py` via Chaquopy for both metadata (`info()`) and download (`download()`).

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md).