# Changelog
Every iteration of GrabKit gets an entry here (newest first).

## [0.1.10] - 2026-10-21
### Changed
- TikTok posts with creator "audience controls" (TikTok shows "Log in to TikTok" and sends no video data to logged-out viewers; flagged `isContentClassified`) now return a clear message: login is required, so it can't be downloaded without one. Other fallbacks are skipped for this case since they hit the same wall. Mentions the optional `cookies.txt` route.
### Notes
- Confirmed on the vt.tiktok.com test link: the 0.1.9 handle lookup worked (`@cherishmeeeeee`), and the real cause was the login gate.

## [0.1.9] - 2026-10-21
### Fixed
- TikTok `vt.` short link: share links can resolve to `tiktok.com/@/video/<id>` (empty handle). TikTok answers that with a stub (post metadata, no video), which both the native extractor and yt-dlp saw. URL cleanup now accepts an empty handle, and the real author handle is looked up via TikTok's public oEmbed endpoint (no login, no cookies) before fetching.
### Changed
- TikTok failure messages now include the exact URL that was fetched, so a remaining failure shows what was requested.
### Known limitations
- Untested against live TikTok. If oEmbed is unavailable the URL is left as-is.

## [0.1.8] - 2026-10-21
### Added
- Optional `cookies.txt` support (project folder, or `GRABKIT_COOKIES`): passed to yt-dlp and the TikTok page/CDN requests so login-only posts can be fetched with the user's own session. Not required; not used for YouTube.
### Changed
- The TikTok "no video data" case (post metadata present, `video` empty, as logged for the vt.tiktok.com link) now says the post is likely restricted or login-only and shows TikTok's flags/status instead of a bare key list.
### Known limitations
- Cookie route untested live. Cookies expire; re-export if it stops working.

## [0.1.7] - 2026-10-21
### Fixed
- TikTok `vt.` link: the page now loads, but extraction failed with "no playable URL" because the video URL wasn't in the one spot checked. All URLs under any `play*` key are now collected (clean `playAddr` first, `downloadAddr` and `playApi` last).
### Changed
- If no URL is found, the error lists the page's video keys, so the next fix can target the real layout.
### Known limitations
- Still untested against live TikTok. Some videos (age-restricted, region-locked) may expose no URL without a login.

## [0.1.6] - 2026-10-21
### Fixed
- TikTok short links (`vt.tiktok.com/...`) failing. Share links resolve to URLs with tracking params; TikTok URLs are now rebuilt to the plain `https://www.tiktok.com/@user/video/<id>` form before fetching.
### Added
- Extra TikTok routes: the page fetch retries with a mobile Chrome fingerprint, and a new embed-page extractor (`/embed/v2/<id>`) runs before the dead mobile-feed route.
- "No embedded data" errors now report page size and whether a captcha/verification wall was served.
### Known limitations
- Untested against live TikTok. If the `vt.` link still fails, the error text now says whether TikTok served a verification wall.

## [0.1.5] - 2026-10-20
### Changed
- Launch scripts start uvicorn with `--reload --reload-dir app`, so replacing files restarts the server automatically. Previously new HTML was served by an old Python process, which made fixes look broken.
- Footer says "Old server code is running" when the backend predates the version endpoint (shown as "vundefined" before).
### Note
- No extractor changes. The Facebook video fix from 0.1.4 only takes effect once the server restarts.

## [0.1.4] - 2026-10-20
### Fixed
- Facebook video still listed as a single image. Facebook URLs shaped like videos (`/videos/`, `/reel/`, `/watch`, `/share/v/`, `fb.watch`) are now always treated as video, even if yt-dlp reports only a thumbnail.
### Added
- Footer shows the running version and warns if ffmpeg is missing (`/api/health` now returns `version`). If the footer doesn't say v0.1.4, the old server is still running: stop it and start again.
### Known limitations
- Facebook quality stays "Best" (no height data).

## [0.1.3] - 2026-10-20
### Added
- Favicon (`app/web/favicon.svg`): the amber download arrow from the Android launcher icon on a dark rounded square.
### Fixed
- Facebook videos appearing as a single "image". The yt-dlp fallback decided "video" only when a format had a pixel height, and Facebook's progressive formats have none. A video is now any entry with a non-image video format or a duration.
- Facebook native extractor now fetches with the Chrome-fingerprint client and falls back to the `og:video` meta tag.
- yt-dlp-sourced Facebook titles also lose the "views · reactions |" prefix.
### Known limitations
- Facebook quality can't be chosen when formats carry no height; it downloads at Best.

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
