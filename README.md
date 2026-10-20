# GrabKit
Self-hosted media downloader: YouTube, TikTok, Instagram, X, Facebook. No login, no telemetry, nothing stored server-side beyond an active request.

## Run
Requires Python 3.10+ and **ffmpeg** on PATH (mp3, merging, jpg/webp conversion).
`./run.sh` (macOS/Linux) or `run.bat` (Windows), then open http://127.0.0.1:8000

## Use
Paste one or more links (one per line) and press Enter. Pick format and quality per card, tick the carousel items you want, download.

## Layout
- `app/main.py` - API, extractors, job runner
- `app/web/index.html` - UI
- `CHANGELOG.md` - history per iteration

## Notes
- Keep yt-dlp current: `pip install -U yt-dlp`.
- Bind to localhost only. The server fetches URLs on your behalf; do not expose it publicly.
- Only download content you have the right to save. Platform terms and copyright law still apply.
