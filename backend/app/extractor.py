import re

from yt_dlp import YoutubeDL


class ExtractError(Exception):
    pass


_PATTERNS = [
    ("TIKTOK", re.compile(r"(tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com)", re.I)),
    ("INSTAGRAM", re.compile(r"instagram\.com", re.I)),
    ("FACEBOOK", re.compile(r"(facebook\.com|fb\.watch|fb\.com)", re.I)),
]


def detect_platform(url: str):
    for name, pat in _PATTERNS:
        if pat.search(url):
            return name
    return None


def _pick_format(info: dict) -> dict:
    fmts = [f for f in info.get("formats", []) if f.get("url")]
    progressive = [
        f
        for f in fmts
        if f.get("vcodec") not in (None, "none")
        and f.get("acodec") not in (None, "none")
        and f.get("ext") == "mp4"
    ]
    pool = progressive or [f for f in fmts if f.get("vcodec") not in (None, "none")]
    if not pool and info.get("url"):
        return {
            "url": info["url"],
            "ext": info.get("ext", "mp4"),
            "filesize": info.get("filesize"),
            "http_headers": info.get("http_headers", {}),
        }
    if not pool:
        raise ExtractError("no downloadable format")
    best = max(pool, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
    return {
        "url": best["url"],
        "ext": best.get("ext", "mp4"),
        "filesize": best.get("filesize") or best.get("filesize_approx"),
        "http_headers": best.get("http_headers", {}),
    }


def extract(url: str) -> dict:
    platform = detect_platform(url)
    if platform is None:
        raise ExtractError("unsupported platform")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        raise ExtractError(str(e))
    if info is None:
        raise ExtractError("no info extracted")
    if "entries" in info:
        entries = [e for e in info["entries"] if e]
        if not entries:
            raise ExtractError("empty playlist")
        info = entries[0]
    video = _pick_format(info)
    return {
        "platform": platform,
        "title": info.get("title"),
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "video": video,
    }
