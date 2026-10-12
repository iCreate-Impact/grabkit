# Contributing to GrabKit

Thanks for taking the time. Contributions of all sizes are welcome — bug reports, fixes, new extractors, Android improvements, docs.

---

## Before you open a PR

- Check open issues first. Someone may already be working on it.
- For anything larger than a one-line fix, open an issue first to discuss the approach. Saves everyone time if the direction needs adjusting.

---

## Setting up locally

### Web app

```bash
git clone https://github.com/iCreate-Impact/grabkit.git
cd grabkit
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
./run.sh
```

### Android

```bash
cd grabkit-android
gradle wrapper --gradle-version 8.7   # if wrapper is missing
./gradlew assembleDebug
```

See [`grabkit-android/README.md`](grabkit-android/README.md) for the full build requirements (ffmpeg-kit, Chaquopy, JS runtime for YouTube).

---

## Where things live

| You want to… | Look in |
|--------------|---------|
| Fix a broken platform extractor (web) | `app/main.py` — each platform has its own extractor class |
| Fix a broken platform extractor (Android) | `app/src/main/java/com/grabkit/extractor/` |
| Change the yt-dlp bridge | `app/main.py` (web) or `app/src/main/python/grabkit_ytdlp.py` (Android) |
| Change the download pipeline | `app/main.py` (web) or `service/DownloadEngine.kt` (Android) |
| Update the UI | `app/web/index.html` (web) or `ui/` (Android Compose) |
| Add a new format or quality option | Both places above |

---

## What makes a good extractor fix

Platform APIs change constantly. When you fix an extractor:

1. **Say what broke.** In the PR description, note what the old request was and what the platform changed.
2. **Show the new response shape** (redacted if needed) so reviewers understand the new parse logic.
3. **Document fragility.** If the fix depends on a header, cookie, or token that might rotate, say so in a comment.
4. **Don't remove the yt-dlp fallback.** Native extractors are faster but yt-dlp is the safety net. Keep both paths.

---

## Code style

### Python (web app)

- Standard library imports, then third-party, then local — one blank line between groups.
- Type hints on function signatures.
- No external formatters required, but keep it readable. `black`-compatible style is fine.

### Kotlin (Android)

- Follow the existing file structure: one class per file, named to match.
- Hilt for DI — don't pass the `Application` context around manually.
- Compose for UI — no XML layouts.
- `StateFlow` for shared state, not `LiveData`.
- Keep `suspend` functions in ViewModels or the service layer; no coroutine launches in composables.

---

## Commits and PRs

- One logical change per commit. If you fixed a bug and refactored something unrelated, split them.
- Commit messages: short imperative summary (`Fix TikTok feed parse after API change`), optionally followed by a blank line and detail.
- PR titles follow the same format.
- Update `CHANGELOG.md` (the relevant one — root for web, `grabkit-android/` for Android) under an `[Unreleased]` section if your change is user-visible.

---

## Reporting bugs

Open an issue with:

1. The platform and URL type (TikTok video, Instagram carousel, YouTube playlist, etc.) — no need to share the actual URL.
2. The error message shown in the UI or the terminal output.
3. Your yt-dlp version (`yt-dlp --version`) and Python version.
4. For Android: the logcat output from the download attempt.

If the fix is "update yt-dlp," try that first before filing.

---

## What we won't merge

- Features that require a server-side account, database, or cloud dependency.
- UI frameworks — the web UI stays as a single self-contained HTML file.
- Anything that bypasses platform DRM or targets content the user doesn't have access to.
- Android XML layouts or LiveData (existing patterns only).