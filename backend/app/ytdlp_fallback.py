import os
import time


class ExtractError(Exception):
    pass


# Anti-bot flakiness (the page came back without data), worth retrying with a
# fresh session — the pattern that made yt-dlp reliable for TikTok on the VPS.
_TRANSIENT_HINTS = (
    "rehydration",
    "unexpected response",
    "unable to extract",
    "challenge",
    "please report this issue",
    "timed out",
    "read timed out",
    "http error 5",
)


def _is_transient(msg: str) -> bool:
    m = msg.lower()
    return any(h in m for h in _TRANSIENT_HINTS)


def format_selector(quality: str, mode: str) -> str:
    if mode == "audio":
        return "bestaudio/best"
    if quality == "max":
        return "bv*+ba/b"
    h = quality
    return (
        f"bv*[height<={h}][ext=mp4]+ba[ext=m4a]/"
        f"b[height<={h}][ext=mp4]/"
        f"bv*[height<={h}]+ba/b[height<={h}]/b"
    )


def _opts(quality: str, mode: str, outtmpl: str) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "outtmpl": outtmpl,
        "retries": 5,
        "fragment_retries": 5,
        "format": format_selector(quality, mode),
    }
    if mode == "audio":
        opts["postprocessors"] = [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "mp3",
            "preferredquality": "128",
        }]
    else:
        opts["merge_output_format"] = "mp4"
    return opts


def _download_once(url: str, quality: str, mode: str, out_base: str) -> str:
    from yt_dlp import YoutubeDL  # lazy: module import shouldn't require yt-dlp

    ext = "mp3" if mode == "audio" else "mp4"
    outtmpl = f"{out_base}.ytdl.%(ext)s"
    with YoutubeDL(_opts(quality, mode, outtmpl)) as ydl:
        info = ydl.extract_info(url, download=True)
        if info and "entries" in info:
            entries = [e for e in info["entries"] if e]
            if not entries:
                raise ExtractError("empty playlist")
            info = entries[0]
    produced = f"{out_base}.ytdl.{ext}"
    if not os.path.exists(produced):
        produced = ydl.prepare_filename(info)
    if not os.path.exists(produced):
        raise ExtractError("yt-dlp produced no file")
    return produced


def _attempts() -> int:
    return int(os.environ.get("EXTRACT_ATTEMPTS", "8"))


def _delay() -> float:
    return float(os.environ.get("EXTRACT_RETRY_DELAY", "0.6"))


def download(url: str, quality: str, mode: str, out_base: str) -> str:
    """Download via yt-dlp, retrying transient anti-bot failures. Returns file path."""
    attempts = _attempts()
    last = "unknown error"
    for i in range(attempts):
        try:
            return _download_once(url, quality, mode, out_base)
        except ExtractError:
            raise
        except Exception as e:
            last = str(e)
            if not _is_transient(last) or i == attempts - 1:
                raise ExtractError(last)
            time.sleep(_delay())
    raise ExtractError(last)
