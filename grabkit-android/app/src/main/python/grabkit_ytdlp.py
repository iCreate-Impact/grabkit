"""GrabKit yt-dlp bridge. Runs inside Chaquopy; called from YtDlpBridge.kt.

There is no ffmpeg *binary* on Android, so yt-dlp never merges or post-processes here.
It only downloads the raw streams; Kotlin then merges / extracts mp3 with ffmpeg-kit.
"""
import json
import os
import re

import yt_dlp

_ANSI = re.compile(r"\x1b\[[0-9;]*m")


def _clean(s):
    return _ANSI.sub("", str(s or "")).strip()


class _Log:
    """Silences yt-dlp and remembers error lines so a failure has a readable reason."""

    def __init__(self):
        self.errors = []

    def debug(self, m):
        pass

    def info(self, m):
        pass

    def warning(self, m):
        pass

    def error(self, m):
        self.errors.append(_clean(m))


def _base(qjs, log):
    o = {"quiet": True, "no_warnings": True, "socket_timeout": 20, "retries": 3, "logger": log}
    if qjs:
        # YouTube needs an external JS runtime. Kotlin passes the path of a bundled qjs binary, if any.
        o["js_runtimes"] = {"quickjs": {"path": qjs}}
    return o


def info(url, qjs=None):
    """Metadata only. Returns JSON: {title, thumb, pl, items:[{type, thumb, maxh, sources}]}."""
    log = _Log()
    opts = {**_base(qjs, log), "skip_download": True, "noplaylist": True}
    with yt_dlp.YoutubeDL(opts) as y:
        i = y.extract_info(url, download=False)
    if not i:
        raise RuntimeError(log.errors[-1] if log.errors else "No media found at that link.")
    ents = list(i.get("entries") or [])
    pl = bool(ents)
    items = []
    for e in [e for e in (ents or [i]) if e]:
        hs = [f["height"] for f in e.get("formats") or [] if f.get("height") and f.get("vcodec") != "none"]
        if hs or not e.get("thumbnail"):
            items.append({"type": "video", "thumb": e.get("thumbnail"), "maxh": max(hs or [0]), "sources": []})
        else:
            items.append({"type": "image", "thumb": e["thumbnail"], "maxh": 0,
                          "sources": [{"h": 0, "url": e["thumbnail"]}]})
    if not items:
        raise RuntimeError("No media found at that link.")
    return json.dumps({"title": i.get("title") or "Media", "thumb": i.get("thumbnail") or items[0]["thumb"],
                       "pl": pl, "items": items}, default=str)


class _YDL(yt_dlp.YoutubeDL):
    """Records how many streams (1 = muxed/audio, 2 = video+audio) will be downloaded.

    yt-dlp removes 'requested_formats' from the dict it hands to progress hooks, so the count has to
    be read here, before the per-stream downloads start."""

    stages = 1

    def process_info(self, info_dict):
        self.stages = max(1, len(info_dict.get("requested_formats") or [1]))
        return super().process_info(info_dict)


def _role(ic):
    if ic.get("vcodec") == "none":
        return "audio"
    if ic.get("acodec") == "none":
        return "video"
    return "muxed"


def _speed(s):
    return "%.1f MiB/s" % (s / 1048576) if s >= 1048576 else "%d KiB/s" % (s / 1024)


def _eta(e):
    m, s = divmod(int(e), 60)
    return "%02d:%02d" % (m, s)


def download(url, out_dir, kind, quality, index, playlist, sink, qjs=None):
    """Downloads raw stream file(s) into out_dir. sink.update(percent, label) reports progress (0-95).

    kind: "video" | "audio". Returns JSON: {"files": [{"path": ..., "role": "video"|"audio"|"muxed"}]}."""
    log = _Log()
    files = []
    st = {"done": 0}
    holder = {}

    def hook(d):
        n = holder["y"].stages
        ic = d.get("info_dict") or {}
        role = _role(ic)
        what = {"audio": " audio", "video": " video"}.get(role, "") if n > 1 else ""
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            frac = min((d.get("downloaded_bytes") or 0) / total, 1.0) if total else 0.0
            label = "Downloading" + what
            if d.get("speed"):
                label += " \u00b7 " + _speed(d["speed"])
            if d.get("eta") is not None:
                label += " \u00b7 ETA " + _eta(d["eta"])
            sink.update(min((st["done"] + frac) / n * 95, 95.0), label)
        elif d["status"] == "finished":
            st["done"] += 1
            files.append({"path": d["filename"], "role": role})
            sink.update(min(st["done"] / n * 95, 95.0), "Processing")

    if kind == "audio":
        fmt = "bestaudio/best"
    else:
        h = "[height<=%d]" % quality if quality else ""
        fmt = "bv*%s[ext=mp4]+ba[ext=m4a]/bv*%s+ba/b%s/b" % (h, h, h)
    o = {**_base(qjs, log), "format": fmt, "outtmpl": os.path.join(out_dir, "out.%(ext)s"),
         "progress_hooks": [hook], "noplaylist": not playlist, "noprogress": True, "overwrites": True,
         # Without ffmpeg yt-dlp refuses multi-format downloads unless ignoreerrors is set.
         # 'only_download' keeps extraction errors (private, geo...) as real exceptions.
         "ignoreerrors": "only_download"}
    if playlist:
        o["playlist_items"] = str(index + 1)
    y = holder["y"] = _YDL(o)
    with y:
        y.download([url])
    files = [f for f in files if os.path.exists(f["path"])]
    if not files:
        raise RuntimeError(log.errors[-1] if log.errors else "Download produced no file.")
    return json.dumps({"files": files})
