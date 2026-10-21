"""GrabKit backend. Stateless: temp files live only for the active job."""
import glob, html as htmllib, json, os, re, shutil, subprocess, tempfile, threading, time, uuid
from urllib.parse import parse_qs, urlparse
import httpx, yt_dlp
from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from starlette.background import BackgroundTask

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
HOSTS = {"youtube": ("youtube.com", "youtu.be"), "tiktok": ("tiktok.com",), "instagram": ("instagram.com",),
         "facebook": ("facebook.com", "fb.watch", "fb.com"), "x": ("twitter.com", "x.com")}
STD = (1080, 720, 480, 360)
VERSION = "0.1.6"
CACHE, JOBS, STREAMS = {}, {}, {}
app = FastAPI(title="GrabKit")


class AppError(Exception):
    def __init__(s, code, message, status=400):
        s.code, s.message, s.status = code, message, status


@app.exception_handler(AppError)
def _err(_, e): return JSONResponse({"error": e.code, "message": e.message}, e.status)


def classify(m):
    s = m.lower()
    if any(k in s for k in ("private", "login", "log in", "sign in", "cookies", "authenticat", "members-only")):
        return AppError("private", "This content is private or needs a login. Only public posts can be downloaded.", 403)
    if any(k in s for k in ("geo", "your country", "not available in")):
        return AppError("geo", "This content is geo-restricted in the server's region.", 451)
    if any(k in s for k in ("429", "too many requests", "rate limit", "rate-limit")):
        return AppError("rate_limit", "The platform is rate limiting this server. Wait a few minutes and retry.", 429)
    if any(k in s for k in ("unsupported url", "not a valid url")):
        return AppError("unsupported", "That link format isn't supported. Use a direct link to a post or video.", 400)
    d = re.sub(r"\x1b\[[0-9;]*m", "", m).strip()[:140]
    e = AppError("fetch_failed", "Couldn't fetch that link. It may be deleted, or the platform changed its page format." + (f" ({d})" if d else ""), 502)
    e.detail = d
    return e


def wrap(e): return e if isinstance(e, AppError) else classify(str(e))
def detect(url):
    h = (urlparse(url).hostname or "").lower()
    for p, hs in HOSTS.items():
        if any(h == x or h.endswith("." + x) for x in hs): return p
    raise AppError("unsupported_url", "Not a supported link. Use YouTube, TikTok, Instagram, X or Facebook. If this is a redirect link, paste the original post link.")
def http(**kw): return httpx.Client(headers={"User-Agent": UA}, follow_redirects=True, timeout=20, **kw)
def safe(s): return re.sub(r"[^A-Za-z0-9._ -]+", "_", s or "media").strip(" ._")[:70] or "media"


# ---------- extractors: return {title, thumb, engine, items:[{type, thumb, sources:[{h,url}]}]} ----------
def tiktok_app(url):
    with http() as c:
        r = c.get(url)
        m = re.search(r"/(?:video|photo)/(\d+)", str(r.url))
        if not m: raise AppError("unsupported", "No TikTok video ID found in that URL.")
        vid = m.group(1)
        q = dict(aweme_id=vid, iid="7318518857994389254", device_id="7318517321748022790", channel="googleplay", aid="1180",
                 app_name="musical_ly", version_code="300904", version_name="30.9.4", device_platform="android",
                 os_version="13", device_type="Pixel 7", device_brand="google", region="US", carrier_region="US")
        j = c.get("https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/", params=q, headers={
            "User-Agent": "com.zhiliaoapp.musically/2023009040 (Linux; U; Android 13; en_US; Pixel 7)"}).json()
    a = next((x for x in j.get("aweme_list") or [] if str(x.get("aweme_id")) == vid), None)
    if not a: raise AppError("fetch_failed", "TikTok returned no data for this post (deleted or private?).", 502)
    cover = ((a.get("video") or {}).get("cover") or {}).get("url_list", [None])[0]
    imgs = (a.get("image_post_info") or {}).get("images")
    if imgs:
        items = [{"type": "image", "thumb": i["display_image"]["url_list"][0],
                  "sources": [{"h": 0, "url": i["display_image"]["url_list"][0]}]} for i in imgs]
    else:
        v = a["video"]  # play_addr is the clean (no-watermark) stream; download_addr carries the watermark
        srcs = [{"h": v.get("height", 0), "url": v["play_addr"]["url_list"][0]}]
        for b in v.get("bit_rate") or []:
            srcs.append({"h": b["play_addr"].get("height", 0), "url": b["play_addr"]["url_list"][0]})
        items = [{"type": "video", "thumb": cover, "sources": srcs}]
    return {"title": a.get("desc") or "TikTok post", "thumb": cover or items[0]["thumb"], "items": items}


def page(url, hdrs, imp="chrome"):
    """GET with a real-browser TLS fingerprint when curl_cffi is installed (TikTok blocks plain clients)."""
    try:
        from curl_cffi import requests as cr
        r = cr.get(url, impersonate=imp, headers=hdrs, timeout=20, allow_redirects=True)
        return r.text, {k: v for k, v in r.cookies.items()}
    except ImportError:
        with http() as c:
            r = c.get(url, headers=hdrs)
            return r.text, dict(c.cookies.items())


def ydl_extra(url):
    if "youtu" in url: return {}
    try:
        import curl_cffi  # noqa: F401
        from yt_dlp.networking.impersonate import ImpersonateTarget
        return {"impersonate": ImpersonateTarget("chrome")}
    except Exception: return {}


def tiktok_web(url):
    for imp in ("chrome", "chrome_android"):  # TikTok serves different pages per client
        text, cks = page(url, {"Accept-Language": "en-US,en;q=0.9", "Referer": "https://www.tiktok.com/"}, imp)
        m = re.search(r'<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.+?)</script>', text, re.S)
        if m: break
    if not m:
        wall = ", verification wall" if re.search(r"captcha|verify", text[:20000], re.I) else ""
        raise AppError("fetch_failed", f"no embedded data in page ({len(text)} bytes{wall})", 502)
    d = json.loads(m.group(1)).get("__DEFAULT_SCOPE__", {}).get("webapp.video-detail") or {}
    it = (d.get("itemInfo") or {}).get("itemStruct")
    if not it:
        if d.get("statusCode"): raise AppError("private", "This TikTok is private, removed, or region-locked.", 403)
        raise AppError("fetch_failed", "page had no video item", 502)
    hdr = {"Cookie": "; ".join(f"{k}={v}" for k, v in cks.items()), "Referer": "https://www.tiktok.com/"}  # CDN needs page cookies
    v = it.get("video") or {}; cover = v.get("cover") or v.get("originCover")
    imgs = (it.get("imagePost") or {}).get("images")
    if imgs:
        items = [{"type": "image", "thumb": i["imageURL"]["urlList"][0],
                  "sources": [{"h": 0, "url": i["imageURL"]["urlList"][0], "hdr": hdr}]} for i in imgs]
    else:
        u = v.get("playAddr") or next(iter(((v.get("bitrateInfo") or [{}])[0].get("PlayAddr") or {}).get("UrlList") or []), None)
        if not u: raise AppError("fetch_failed", "no playable URL", 502)
        items = [{"type": "video", "thumb": cover, "sources": [{"h": v.get("height", 0), "url": u, "hdr": hdr}]}]
    return {"title": it.get("desc") or "TikTok post", "thumb": cover or items[0]["thumb"], "items": items}


def tiktok_embed(url):
    vid = re.search(r"/video/(\d+)", url).group(1)
    text, cks = page(f"https://www.tiktok.com/embed/v2/{vid}", {"Accept-Language": "en-US,en;q=0.9"})
    m = re.search(r'<script id="__FRONTITY_CONNECT_STATE__"[^>]*>(.+?)</script>', text, re.S)
    if not m: raise AppError("fetch_failed", f"embed page had no data ({len(text)} bytes)", 502)
    data = (json.loads(m.group(1)).get("source") or {}).get("data") or {}
    vd = next((v["videoData"] for v in data.values() if isinstance(v, dict) and v.get("videoData")), None)
    inf = (vd or {}).get("itemInfos") or {}; v = inf.get("video") or {}
    if not v.get("urls"): raise AppError("fetch_failed", "embed had no video URL", 502)
    hdr = {"Cookie": "; ".join(f"{k}={x}" for k, x in cks.items()), "Referer": "https://www.tiktok.com/"}
    cover = (inf.get("coversOrigin") or [None])[0]
    return {"title": inf.get("text") or "TikTok post", "thumb": cover, "items": [{"type": "video", "thumb": cover, "sources": [
        {"h": (v.get("videoMeta") or {}).get("height", 0), "url": v["urls"][0], "hdr": hdr}]}]}


def tiktok(url):  # web page (clean playAddr) -> mobile feed; resolve() then falls back to yt-dlp
    errs = []
    for f in (tiktok_web, tiktok_embed, tiktok_app):
        try: return f(url)
        except AppError as e:
            if e.code == "private": raise
            errs.append(f"{f.__name__}: {e.message}")
        except Exception as e: errs.append(f"{f.__name__}: {type(e).__name__} {e}")
    raise AppError("fetch_failed", "; ".join(errs)[:200], 502)


def ig_node(n):
    thumb = ((n.get("image_versions2") or {}).get("candidates") or [{}])[0].get("url") or n.get("display_url")
    if n.get("video_versions"):
        return {"type": "video", "thumb": thumb, "sources": [{"h": v.get("height", 0), "url": v["url"]} for v in n["video_versions"]]}
    if n.get("video_url"): return {"type": "video", "thumb": thumb, "sources": [{"h": 720, "url": n["video_url"]}]}
    return {"type": "image", "thumb": thumb, "sources": [{"h": 0, "url": thumb}]}


def instagram(url):
    m = re.search(r"instagram\.com/(?:[\w.]+/)?(p|reel|reels|tv)/([\w-]+)", url)
    if not m: raise AppError("unsupported", "Use an Instagram post, reel or tv link. Stories need a login.")
    kind = "reel" if m.group(1) == "reels" else m.group(1)
    with http() as c:
        r = c.get(f"https://www.instagram.com/{kind}/{m.group(2)}/", params={"__a": "1", "__d": "dis"},
                  headers={"X-IG-App-ID": "936619743392459", "X-Requested-With": "XMLHttpRequest"})
        try: j = r.json()
        except Exception: raise AppError("private", "Instagram sent a login wall for this post.", 403)
    if j.get("items"):
        n = j["items"][0]; nodes = n.get("carousel_media") or [n]
        title = ((n.get("caption") or {}).get("text")) or "Instagram post"
    else:
        sm = j["graphql"]["shortcode_media"]
        nodes = [e["node"] for e in (sm.get("edge_sidecar_to_children") or {}).get("edges", [])] or [sm]
        title = next(iter(((sm.get("edge_media_to_caption") or {}).get("edges") or [{}])), {}).get("node", {}).get("text") or "Instagram post"
    items = [ig_node(x) for x in nodes]
    return {"title": title, "thumb": items[0]["thumb"], "items": items}


def facebook(url):
    html, _ = page(url, {"Accept-Language": "en-US,en;q=0.9"})
    found = {}
    for key, h in (("hd_src", 720), ("browser_native_hd_url", 720), ("playable_url_quality_hd", 720),
                   ("sd_src", 360), ("browser_native_sd_url", 360), ("playable_url", 360)):
        for m in re.finditer(r'"%s":"(https:[^"]+)"' % key, html):
            found.setdefault(h, json.loads('"' + m.group(1) + '"'))
    for m in re.finditer(r'property="og:video(?::secure_url)?" content="([^"]+)"', html):
        found.setdefault(360, htmllib.unescape(m.group(1)))
    if not found:
        raise AppError("private", "No public video found. The post may be private, or Facebook is showing a login wall.", 403)
    og = re.search(r'property="og:image" content="([^"]+)"', html)
    t = re.search(r'property="og:title" content="([^"]*)"', html)
    title = re.sub(r"^[^|]*\b(?:views|reactions)\b[^|]*\|\s*", "", htmllib.unescape(t.group(1))) if t else "Facebook video"
    thumb = og.group(1).replace("&amp;", "&") if og else None
    return {"title": title, "thumb": thumb,
            "items": [{"type": "video", "thumb": thumb, "sources": [{"h": h, "url": u} for h, u in found.items()]}]}


def xpost(url):
    m = re.search(r"status(?:es)?/(\d+)", url)
    if not m: raise AppError("unsupported", "Use a direct link to a post (…/status/<id>).")
    with http() as c:
        r = c.get("https://cdn.syndication.twimg.com/tweet-result", params={"id": m.group(1), "token": "a", "lang": "en"})
    if r.status_code == 404: raise AppError("private", "Post not found, deleted, or not public.", 404)
    if r.status_code == 429: raise classify("429")
    j = r.json(); items = []
    for md in j.get("mediaDetails") or []:
        th = md.get("media_url_https")
        if md["type"] == "photo": items.append({"type": "image", "thumb": th, "sources": [{"h": 0, "url": th + "?name=orig"}]})
        else:
            vs = [v for v in md["video_info"]["variants"] if v.get("content_type") == "video/mp4"]
            items.append({"type": "video", "thumb": th, "sources": [
                {"h": int((re.search(r"/(\d+)x(\d+)/", v["url"]) or [0, 0, 0])[2]), "url": v["url"]} for v in vs]})
    if not items: raise AppError("fetch_failed", "That post has no downloadable media.", 404)
    return {"title": (j.get("text") or "X post")[:120], "thumb": items[0]["thumb"], "items": items}


def ytdlp_info(url):
    with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": True, **ydl_extra(url)}) as y:
        i = y.extract_info(url, download=False)
    pl = bool(i.get("entries")); es = [e for e in (i.get("entries") or [i]) if e]
    items = []
    for e in es:
        vf = [f for f in e.get("formats") or [] if f.get("vcodec") != "none" and f.get("ext") not in ("jpg", "jpeg", "png", "webp", "mhtml")]
        hs = [f["height"] for f in vf if f.get("height")]  # Facebook progressive formats carry no height
        if vf or e.get("duration") or not e.get("thumbnail"): items.append({"type": "video", "thumb": e.get("thumbnail"), "maxh": max(hs or [0]), "sources": []})
        else: items.append({"type": "image", "thumb": e["thumbnail"], "sources": [{"h": 0, "url": e["thumbnail"]}]})
    if re.search(r"fb\.watch|(?:facebook|fb)\.com/.*(?:/videos?/|/reels?/|/watch|/share/[rv]/)", url):
        for it in items:  # a video-style Facebook URL is a video even if yt-dlp lists only a thumbnail
            if it["type"] == "image": it.update(type="video", maxh=0, sources=[])
    if not items: raise AppError("fetch_failed", "No media found at that link.", 404)
    return {"title": i.get("title") or "Media", "thumb": i.get("thumbnail") or items[0]["thumb"], "items": items, "engine": "ytdlp", "pl": pl}


DIRECT = {"tiktok": tiktok, "instagram": instagram, "facebook": facebook, "x": xpost}


SHORT = {"t.co", "bit.ly", "tinyurl.com", "goo.gl", "lnkd.in", "ow.ly", "buff.ly", "is.gd", "vm.tiktok.com", "vt.tiktok.com", "fb.watch"}


def normalize(url):
    for _ in range(3):
        u = urlparse(url); h = (u.hostname or "").lower(); q = parse_qs(u.query); nxt = None
        try:
            if h.endswith("google.com") and u.path in ("/url", "/goto"):
                nxt = (q.get("url") or q.get("q") or [""])[0]
                if not nxt.startswith("http"):  # opaque token: best-effort follow
                    with http() as c: nxt = str(c.get(url).url)
            elif h in ("l.facebook.com", "lm.facebook.com"): nxt = (q.get("u") or [""])[0]
            elif h in SHORT:
                with http() as c: nxt = str(c.get(url).url)
        except Exception: nxt = None
        if not nxt or nxt == url: break
        url = nxt
    return url


def canon(url):
    """Share links carry tracking params and odd shapes; rebuild the plain TikTok video URL."""
    u = urlparse(url)
    if (u.hostname or "").endswith("tiktok.com"):
        m = re.search(r"/(@[\w.\-]+)/(video|photo)/(\d+)", u.path)
        if m: return f"https://www.tiktok.com/{m.group(1)}/{m.group(2)}/{m.group(3)}"
        m = re.search(r"/v/(\d+)", u.path)
        if m: return f"https://www.tiktok.com/@_/video/{m.group(1)}"
    return url


def resolve(url):
    orig = url.strip(); hit = CACHE.get(orig)  # metadata only, never media
    if hit and time.time() - hit[0] < 600: return hit[1]
    url = canon(normalize(orig)); p = detect(url)
    try:
        res = ytdlp_info(url) if p == "youtube" else {**DIRECT[p](url), "engine": "direct"}
    except Exception as e:
        if p == "youtube": raise wrap(e)
        try: res = ytdlp_info(url)
        except Exception as e2:
            first, second = wrap(e), wrap(e2)
            if first.code != "fetch_failed": raise first
            if second.code != "fetch_failed": raise second
            raise AppError("fetch_failed", f"Couldn't fetch that link. Native: {getattr(first, 'detail', None) or first.message} | yt-dlp: {getattr(second, 'detail', '')}"[:420], 502)
    if p == "facebook": res["title"] = re.sub(r"^[^|]*\b(?:views|reactions)\b[^|]*\|\s*", "", res["title"])
    res["platform"] = p; res["url"] = url; CACHE[orig] = (time.time(), res)
    return res


def quals(it):
    hs = {s["h"] for s in it["sources"] if s["h"]} if it["sources"] else None
    if hs is None:
        m = it.get("maxh", 0); return [h for h in STD if h <= m] or ([m] if m else [])
    return sorted(hs, reverse=True)


def pick(srcs, q):
    srcs = sorted(srcs, key=lambda s: -s["h"])
    if not q: return srcs[0]
    ok = [s for s in srcs if s["h"] <= q]
    return ok[0] if ok else srcs[-1]


class InfoReq(BaseModel): url: str
class PrepReq(BaseModel):
    url: str; kind: str; ext: str; quality: int | None = None; index: int = 0


@app.get("/api/health")
def health(): return {"ok": True, "version": VERSION, "ffmpeg": bool(shutil.which("ffmpeg"))}


@app.post("/api/info")
def info(r: InfoReq):
    res = resolve(r.url)
    return {"platform": res["platform"], "title": res["title"], "thumb": res["thumb"], "items": [
        {"type": i["type"], "thumb": i["thumb"], "qualities": quals(i) if i["type"] == "video" else []} for i in res["items"]]}


def ff(*a):
    if not shutil.which("ffmpeg"): raise AppError("ffmpeg_missing", "ffmpeg is not installed on the server.", 500)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *a], check=True, capture_output=True)


def dl_to(url, path, j, lo, hi, hdr=None):
    with httpx.stream("GET", url, headers={"User-Agent": UA, **(hdr or {})}, follow_redirects=True, timeout=30) as r:
        r.raise_for_status(); tot = int(r.headers.get("content-length") or 0); got = 0
        with open(path, "wb") as f:
            for ch in r.iter_bytes(65536):
                f.write(ch); got += len(ch)
                if tot: j["pct"] = round(lo + (hi - lo) * got / tot, 1)


def work(j, url, res, it, idx, kind, ext, q):
    tmp = j["dir"]; out = os.path.join(tmp, "out." + ext)
    if kind == "image":
        src = it["sources"][0]["url"] if it["type"] == "image" else it["thumb"]
        raw = os.path.join(tmp, "raw"); j["label"] = "Downloading"; dl_to(src, raw, j, 0, 85, it["sources"][0].get("hdr") if it["type"] == "image" else None)
        j["label"] = "Converting"; ff("-i", raw, *(["-q:v", "2"] if ext == "jpg" else []), out)
    elif res["engine"] == "ytdlp":
        stages = 2 if kind == "video" else 1; st = {"done": 0}
        def hook(d):
            if d["status"] == "downloading":
                t = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                j["pct"] = round((st["done"] + (d["downloaded_bytes"] / t if t else 0)) / stages * 95, 1)
            elif d["status"] == "finished":
                st["done"] += 1; j["pct"] = round(st["done"] / stages * 95, 1); j["label"] = "Processing"
        o = {"outtmpl": tmp + "/out.%(ext)s", "quiet": True, "no_warnings": True, "noplaylist": not res.get("pl"),
             "progress_hooks": [hook], "merge_output_format": "mp4", **ydl_extra(url)}
        if res.get("pl"): o["playlist_items"] = str(idx + 1)
        if kind == "audio":
            o.update(format="bestaudio/best", postprocessors=[{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}])
        else:
            h = f"[height<={q}]" if q else ""
            o["format"] = f"bv*{h}[ext=mp4]+ba[ext=m4a]/bv*{h}+ba/b{h}/b"
        j["label"] = "Downloading"
        with yt_dlp.YoutubeDL(o) as y: y.download([url])
        out = next(iter(glob.glob(tmp + "/out.*")), None)
        if not out: raise AppError("fetch_failed", "Download produced no file.", 502)
    else:  # direct audio: fetch best video, extract mp3
        raw = os.path.join(tmp, "raw"); j["label"] = "Downloading"; s = pick(it["sources"], None); dl_to(s["url"], raw, j, 0, 85, s.get("hdr"))
        j["label"] = "Converting"; ff("-i", raw, "-vn", "-codec:a", "libmp3lame", "-q:a", "2", out)
    return out


@app.post("/api/prepare")
def prepare(r: PrepReq):
    res = resolve(r.url)
    if not 0 <= r.index < len(res["items"]): raise AppError("bad_index", "That item doesn't exist.")
    it = res["items"][r.index]; suffix = f"-{r.index + 1}" if len(res["items"]) > 1 else ""
    if r.kind == "audio" and it["type"] == "image": raise AppError("bad_format", "Images can only be saved as jpg or webp.")
    if r.kind == "video" and it["type"] == "image": r.kind, r.ext = "image", "jpg"
    name = f"{safe(res['title'])}{suffix}.{r.ext}"
    if res["engine"] == "direct" and r.kind == "video":
        s = pick(it["sources"], r.quality); tok = uuid.uuid4().hex; STREAMS[tok] = (s["url"], time.time(), s.get("hdr"))
        return {"mode": "direct", "href": f"/api/stream/{tok}", "name": name}
    for k, v in list(JOBS.items()):  # sweep abandoned jobs
        if time.time() - v["t"] > 900: shutil.rmtree(v["dir"], ignore_errors=True); JOBS.pop(k, None)
    jid = uuid.uuid4().hex
    j = JOBS[jid] = {"state": "run", "pct": 0, "label": "Fetching", "t": time.time(), "dir": tempfile.mkdtemp(prefix="grabkit_"), "name": name}
    def run():
        try: j["path"] = work(j, res["url"], res, it, r.index, r.kind, r.ext, r.quality); j["state"] = "done"; j["pct"] = 100
        except Exception as e: j.update(state="error", error=wrap(e).message)
    threading.Thread(target=run, daemon=True).start()
    return {"mode": "job", "id": jid, "name": name}


@app.get("/api/job/{jid}")
def job(jid: str):
    j = JOBS.get(jid)
    if not j: raise AppError("gone", "That download expired. Start it again.", 404)
    return {k: j.get(k) for k in ("state", "pct", "label", "error")}


@app.get("/api/file/{jid}")
def file(jid: str):
    j = JOBS.get(jid)
    if not j or j["state"] != "done": raise AppError("gone", "That file isn't ready or has expired.", 404)
    def clean(): shutil.rmtree(j["dir"], ignore_errors=True); JOBS.pop(jid, None)
    return FileResponse(j["path"], filename=j["name"], background=BackgroundTask(clean))


@app.get("/api/stream/{tok}")
def stream(tok: str):
    u = STREAMS.pop(tok, None)
    if not u or time.time() - u[1] > 600: raise AppError("gone", "That link expired. Start it again.", 404)
    c = httpx.Client(headers={"User-Agent": UA}, follow_redirects=True, timeout=30)
    r = c.send(c.build_request("GET", u[0], headers=u[2] or {}), stream=True)
    if r.status_code >= 400: r.close(); c.close(); raise classify(str(r.status_code))
    h = {"Content-Length": r.headers["content-length"]} if r.headers.get("content-length") else {}
    return StreamingResponse(r.iter_bytes(65536), media_type=r.headers.get("content-type", "video/mp4"), headers=h,
                             background=BackgroundTask(lambda: (r.close(), c.close())))


app.mount("/", StaticFiles(directory=os.path.join(os.path.dirname(__file__), "web"), html=True), name="web")
