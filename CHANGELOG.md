# Changelog
Every iteration of GrabKit gets an entry here (newest first).

## [0.1.2] - 2026-10-11
### Fixed
- TikTok links failing with yt-dlp "Unexpected response from webpage request": TikTok rejects non-browser TLS fingerprints. Native page fetch and yt-dlp now impersonate Chrome via `curl_cffi` (added to requirements; non-YouTube only).
- A TikTok page without video data is no longer reported as "private" unless TikTok returns a status code saying so.
- Facebook titles no longer start with view/reaction counts; HTML entities are decoded.
### Changed
- When both the native extractor and yt-dlp fail, the error now shows both reasons (`Native: … | yt-dlp: …`).
- The same link is no longer fetched twice in one session.
### Upgrade
- Re-run `pip install -r requirements.txt` (new dependency: curl_cffi, installed via the yt-dlp extra).
### Known limitations
- Still untested against live TikTok; CDN downloads reuse httpx with page cookies, which may need the same impersonation if TikTok tightens checks.

## [0.1.1] - 2026-10-11
### Fixed
- TikTok failed to fetch. Now scrapes the web page's embedded JSON (`playAddr`, no watermark, plus photo posts) first, then the mobile feed, then yt-dlp. CDN requests carry the page cookies and Referer, including the download proxy.
- Wrapped and shortened links (t.co, bit.ly, vm/vt.tiktok.com, fb.watch, google.com/url, l.facebook.com) are unwrapped before platform detection. Downloads use the resolved URL.
- Generic "couldn't fetch" errors now append the underlying reason (trimmed) so failures are diagnosable.
### Changed
- Input hint no longer flags unknown hosts as unsupported while typing.
### Known limitations
- `google.com/goto?url=<opaque token>` links can't always be unwrapped; paste the original post link.
- TikTok fix is untested against live TikTok; if it still fails, the new error detail shows why. Try `pip install -U yt-dlp`.

## [0.1.0] - 2026-10-11
### Added
- FastAPI backend: `/api/info`, `/api/prepare`, `/api/job/{id}`, `/api/file/{id}`, `/api/stream/{token}`, `/api/health`.
- Platform detection for YouTube, TikTok, Instagram, X, Facebook.
- Extractors: yt-dlp (YouTube); TikTok aweme feed (no-watermark `play_addr`); Instagram `?__a=1&__d=dis` with carousel indexing; Facebook `hd_src`/`sd_src` HTML parse (watch + reel); X syndication API `video_info.variants`.
- yt-dlp fallback for every non-YouTube platform when the native extractor fails.
- Formats: mp4, mp3 (ffmpeg), jpg/webp (images and thumbnails). Qualities 1080/720/480/360.
- Real progress: server-side job percent (yt-dlp hooks / byte counts) plus client-side byte progress from `Content-Length`.
- Batch input (one URL per line), sequential queue, carousel pick/all, session download history.
- Typed error messages: private, geo-restricted, unsupported URL, rate limit, fetch failed.
- Dark single-page UI, system fonts only, no external requests.
### Privacy / storage
- No login, no telemetry. Temp files exist only per job and are deleted after serving (15 min sweep for abandoned jobs).
### Known limitations
- X syndication uses a fixed token (`a`); Instagram stories and most Instagram posts now need cookies, so they usually fall back to yt-dlp or report "private".
- Unit and live-platform tests have not been run. Platform endpoints change often; expect to update yt-dlp (`pip install -U yt-dlp`).
