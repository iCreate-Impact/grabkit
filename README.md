# GrabKit

Self-hosted media downloader for YouTube, TikTok (no watermark), Instagram, X, and Facebook.
No login. No telemetry. Nothing stored server-side beyond an active request.

Available as a **local web app** (Python + FastAPI) and a **native Android app** (Kotlin + Compose).

---

## What it does

- Paste one or more links → pick format and quality → download
- Formats: MP4, MP3, JPG, WebP
- Quality: up to 1080p where the platform allows
- Batch input, carousel picker for multi-image posts, per-job progress
- All processing happens locally — no cloud, no accounts

---

## Repository layout

```
grabkit/                        ← you are here (repo root)
├── app/
│   ├── main.py                 # FastAPI backend: extractors, job runner, API
│   └── web/index.html          # single-page UI (HTML + CSS + JS, one file)
├── requirements.txt
├── run.sh / run.bat
├── CHANGELOG.md
├── grabkit-android/            # Android sub-project
│   ├── app/src/main/
│   │   ├── java/com/grabkit/   # Kotlin source
│   │   └── python/             # Chaquopy bridge (yt-dlp)
│   ├── build.gradle.kts
│   └── CHANGELOG.md
└── zip_project.py              # release zipper (respects .gitignore)
```

---

## Web app

### Requirements
- Python 3.10+
- **ffmpeg** on PATH (needed for MP3 encoding, stream merging, WebP conversion)

### Install and run

```bash
# clone
git clone https://github.com/iCreate-Impact/grabkit
cd grabkit

# create a virtualenv (recommended)
python3 -m venv .venv
source .venv/bin/activate       # Windows: .venv\Scripts\activate

# install dependencies
pip install -r requirements.txt

# run
./run.sh                        # macOS / Linux
run.bat                         # Windows
```

Open http://127.0.0.1:8000 in your browser.

### Keeping it working

Platform extraction breaks whenever sites change their APIs. Keep yt-dlp current:

```bash
pip install -U yt-dlp
```

### Notes

- Bind to `127.0.0.1` only. The server fetches URLs on your behalf; do not expose it publicly.
- Only download content you have the right to save. Platform terms and copyright law apply.

---

## Android app

> See [`grabkit-android/README.md`](grabkit-android/README.md) for the full Android build guide.

**Quick summary:**
1. Open `grabkit-android/` in Android Studio (Koala or newer)
2. Let Gradle sync (requires a host `python3 >= 3.8` for Chaquopy)
3. Run `./gradlew assembleDebug`

Minimum SDK: Android 7.0 (API 24). No backend required — yt-dlp runs in-process via Chaquopy.

---

## API

The web backend exposes a small REST API at `http://127.0.0.1:8000`:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/info` | Fetch metadata for one or more URLs |
| `POST` | `/api/prepare` | Queue a download job |
| `GET` | `/api/job/{id}` | Poll job status and progress |
| `GET` | `/api/file/{id}` | Download the finished file |
| `GET` | `/api/stream/{token}` | Stream download with progress |
| `GET` | `/api/health` | Health check |

---

## Changelog

- [Web app changelog](CHANGELOG.md)
- [Android changelog](grabkit-android/CHANGELOG.md)

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

[MIT](LICENSE)