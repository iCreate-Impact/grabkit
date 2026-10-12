# Changelog

## [0.1.0] - 2026-10-11
### Added
- Native Android port of GrabKit 0.1.2: TikTok (web JSON then feed API), Instagram, Facebook, X extractors; yt-dlp via Chaquopy for YouTube and as fallback; link unwrapping; batch fetch; carousel picker; mp4/mp3/jpg/webp; byte and stage progress; foreground download service; MediaStore saving; session history.
### Changed vs the spec
- ffmpeg-kit coordinate replaced by a maintained fork (original retired).
- yt-dlp runs without ffmpeg: it downloads raw streams (`ignoreerrors=only_download` is required or it aborts), Kotlin merges them.
- Queue state is a Hilt singleton observed directly by the UI instead of binding the service.
- Python sources in `src/main/python`; legacy storage permission added for API <= 28.
### Known limitations
- Not compiled or run on a device. YouTube needs a bundled JS runtime. TikTok TLS fingerprint is approximate.
