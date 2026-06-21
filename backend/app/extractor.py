import os
import re
import time

from yt_dlp import YoutubeDL


class ExtractError(Exception):
    pass


_PATTERNS = [
    ("TIKTOK", re.compile(r"(tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com)", re.I)),
    ("INSTAGRAM", re.compile(r"instagram\.com", re.I)),
    ("FACEBOOK", re.compile(r"(facebook\.com|fb\.watch|fb\.com)", re.I)),
]

# Errors that are TikTok/IG anti-bot flakiness (the page came back without data),
# not a permanently-unavailable video. Worth retrying with a fresh session.
_TRANSIENT_HINTS = (
    "rehydration",
    "unexpected response",
    "unable to extract",
    "challenge",
    "please report this issue",
    "timed out",
    "read timed out",
)


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


def _extract_once(url: str, platform: str) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
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


def _is_transient(msg: str) -> bool:
    m = msg.lower()
    return any(h in m for h in _TRANSIENT_HINTS)


def _attempts() -> int:
    return int(os.environ.get("EXTRACT_ATTEMPTS", "8"))


def _delay() -> float:
    return float(os.environ.get("EXTRACT_RETRY_DELAY", "0.6"))


def _with_retry(fn):
    """Run fn(); retry on transient anti-bot failures with a fresh session."""
    attempts = _attempts()
    delay = _delay()
    last_err = "unknown error"
    for i in range(attempts):
        try:
            return fn()
        except ExtractError:
            raise
        except Exception as e:
            last_err = str(e)
            if not _is_transient(last_err) or i == attempts - 1:
                raise ExtractError(last_err)
            time.sleep(delay)
    raise ExtractError(last_err)


def extract(url: str) -> dict:
    platform = detect_platform(url)
    if platform is None:
        raise ExtractError("unsupported platform")
    return _with_retry(lambda: _extract_once(url, platform))


def _download_once(url: str, outtmpl: str) -> str:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": "mp4/best",
        "outtmpl": outtmpl,
        "retries": 5,
        "fragment_retries": 5,
    }
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        if info and "entries" in info:
            entries = [e for e in info["entries"] if e]
            if not entries:
                raise ExtractError("empty playlist")
            info = entries[0]
        return ydl.prepare_filename(info)


def download(url: str, outtmpl: str) -> str:
    """Download the video server-side via yt-dlp (handles CDN auth/cookies).

    Returns the path to the downloaded file. Retries transient anti-bot failures.
    """
    platform = detect_platform(url)
    if platform is None:
        raise ExtractError("unsupported platform")
    return _with_retry(lambda: _download_once(url, outtmpl))
