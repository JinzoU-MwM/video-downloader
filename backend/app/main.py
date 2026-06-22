import asyncio
import hashlib
import os
import subprocess
import time

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

from . import cobalt, extractor, proxy, transcode, ytdlp_fallback
from .config import settings

app = FastAPI(title="video-dl-api")

# Per-URL-hash locks so concurrent requests for the same media download once.
_media_locks: dict[str, asyncio.Lock] = {}


class ExtractReq(BaseModel):
    url: str


def _require_key(x_api_key: str | None):
    if x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="invalid api key")


def _truthy(v: str) -> bool:
    return v.lower() in ("1", "true", "yes")


@app.get("/health")
def health():
    return {"status": "ok", "cobalt": cobalt.health()}


@app.post("/extract")
def do_extract(req: ExtractReq, x_api_key: str | None = Header(default=None)):
    _require_key(x_api_key)
    platform = extractor.detect_platform(req.url)
    if platform is None:
        raise HTTPException(status_code=422, detail="unsupported platform")

    # Cobalt returns no metadata; the app picks quality/mode/wa in its options
    # dialog and appends them to this /media URL. The token signs only the URL.
    media_token = proxy.sign({"url": req.url})
    base = settings.public_base_url.rstrip("/")
    return {
        "platform": platform,
        "title": None,
        "thumbnail": None,
        "duration": None,
        "video": {
            "url": f"{base}/media?token={media_token}",
            "ext": "mp4",
            "filesize": None,
            "http_headers": {},
        },
        "proxy_token": None,
    }


def _cleanup_media():
    try:
        now = time.time()
        for name in os.listdir(settings.media_dir):
            p = os.path.join(settings.media_dir, name)
            if os.path.isfile(p) and now - os.path.getmtime(p) > settings.media_ttl:
                os.remove(p)
    except FileNotFoundError:
        pass


def _fetch(url: str, dst: str):
    """Stream a full HTTP body to dst."""
    with httpx.stream("GET", url, timeout=None, follow_redirects=True) as r:
        r.raise_for_status()
        with open(dst, "wb") as f:
            for chunk in r.iter_bytes(65536):
                f.write(chunk)


def _download(page_url: str, quality: str, mode: str, wa: bool, path: str):
    """Fetch the media, then optionally transcode for WhatsApp.

    Cobalt is primary (no watermark, clean quality/audio). If Cobalt can't fetch
    the source (e.g. TikTok anti-bot from this IP) we fall back to yt-dlp, which
    is proven to work here. Raises CobaltError / ExtractError (-> 422) on total
    failure, so we never cache/serve a 0-byte file or leak a 500.
    """
    ext = "mp3" if mode == "audio" else "mp4"
    src = f"{path}.src.{ext}"

    got = False
    try:
        result = cobalt.resolve(page_url, quality, mode)
        _fetch(result["url"], src)
        got = os.path.getsize(src) > 0
    except (cobalt.CobaltError, httpx.HTTPError):
        got = False

    if not got:
        if os.path.exists(src):
            os.remove(src)
        produced = ytdlp_fallback.download(page_url, quality, mode, path)
        os.replace(produced, src)

    if wa and mode == "video":
        try:
            transcode.transcode_whatsapp(src, path)
        except subprocess.CalledProcessError as e:
            if os.path.exists(path):
                os.remove(path)
            raise cobalt.CobaltError(f"transcode failed (ffmpeg exit {e.returncode})")
        finally:
            if os.path.exists(src):
                os.remove(src)
    else:
        os.replace(src, path)


@app.get("/media")
async def media(token: str, quality: str = "1080", mode: str = "video", wa: str = "0"):
    try:
        data = proxy.verify(token)
    except ValueError as e:
        raise HTTPException(status_code=403, detail=str(e))
    if quality not in cobalt.QUALITIES:
        raise HTTPException(status_code=400, detail="bad quality")
    if mode not in cobalt.MODES:
        raise HTTPException(status_code=400, detail="bad mode")
    if wa.lower() not in ("0", "1", "true", "false", "yes", "no"):
        raise HTTPException(status_code=400, detail="bad wa")
    wa_flag = _truthy(wa)

    page_url = data["url"]
    os.makedirs(settings.media_dir, exist_ok=True)
    _cleanup_media()

    ext = "mp3" if mode == "audio" else "mp4"
    media_type = "audio/mpeg" if mode == "audio" else "video/mp4"
    h = hashlib.sha256(f"{page_url}|{quality}|{mode}|{wa_flag}".encode()).hexdigest()[:24]
    path = os.path.join(settings.media_dir, f"{h}.{ext}")

    lock = _media_locks.setdefault(h, asyncio.Lock())
    async with lock:
        if not (os.path.exists(path) and os.path.getsize(path) > 0):
            try:
                await asyncio.to_thread(_download, page_url, quality, mode, wa_flag, path)
            except (cobalt.CobaltError, ytdlp_fallback.ExtractError) as e:
                raise HTTPException(status_code=422, detail=str(e))

    # FileResponse serves with HTTP Range support, so the app can resume.
    return FileResponse(path, media_type=media_type, filename=f"media.{ext}")
