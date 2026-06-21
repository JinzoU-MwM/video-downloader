import asyncio
import hashlib
import os
import time

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel
from yt_dlp.version import __version__ as ytdlp_version

from . import extractor, proxy
from .config import settings
from .extractor import ExtractError, extract

app = FastAPI(title="video-dl-api")

# Per-URL-hash locks so concurrent requests for the same video download once.
_media_locks: dict[str, asyncio.Lock] = {}


class ExtractReq(BaseModel):
    url: str


def _require_key(x_api_key: str | None):
    if x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="invalid api key")


@app.get("/health")
def health():
    return {"status": "ok", "ytdlp_version": ytdlp_version}


@app.post("/extract")
def do_extract(req: ExtractReq, x_api_key: str | None = Header(default=None)):
    _require_key(x_api_key)
    try:
        result = extract(req.url)
    except ExtractError as e:
        raise HTTPException(status_code=422, detail=str(e))

    # The raw CDN URL needs the extraction session's cookies, so the phone can't
    # download it directly. Instead, hand the app a backend /media URL that
    # downloads the video server-side via yt-dlp and streams it back.
    media_token = proxy.sign({"url": req.url})
    base = settings.public_base_url.rstrip("/")
    result["video"]["url"] = f"{base}/media?token={media_token}"
    result["video"]["http_headers"] = {}
    result["video"]["filesize"] = None
    result["proxy_token"] = None
    return result


def _cleanup_media():
    try:
        now = time.time()
        for name in os.listdir(settings.media_dir):
            p = os.path.join(settings.media_dir, name)
            if os.path.isfile(p) and now - os.path.getmtime(p) > settings.media_ttl:
                os.remove(p)
    except FileNotFoundError:
        pass


def _download_to_cache(page_url: str, path: str):
    tmp_tmpl = path + ".src.%(ext)s"
    produced = extractor.download(page_url, tmp_tmpl)
    os.replace(produced, path)


@app.get("/media")
async def media(token: str):
    try:
        data = proxy.verify(token)
    except ValueError as e:
        raise HTTPException(status_code=403, detail=str(e))

    page_url = data["url"]
    os.makedirs(settings.media_dir, exist_ok=True)
    _cleanup_media()

    h = hashlib.sha256(page_url.encode()).hexdigest()[:24]
    path = os.path.join(settings.media_dir, h + ".mp4")

    lock = _media_locks.setdefault(h, asyncio.Lock())
    async with lock:
        if not (os.path.exists(path) and os.path.getsize(path) > 0):
            try:
                await asyncio.to_thread(_download_to_cache, page_url, path)
            except ExtractError as e:
                raise HTTPException(status_code=422, detail=str(e))

    # FileResponse serves with HTTP Range support, so the app can resume.
    return FileResponse(path, media_type="video/mp4", filename="video.mp4")


@app.get("/proxy")
async def do_proxy(token: str, request: Request):
    """Legacy direct-CDN proxy (kept for compatibility; /media is preferred)."""
    try:
        data = proxy.verify(token)
    except ValueError as e:
        raise HTTPException(status_code=403, detail=str(e))

    upstream_headers = dict(data.get("headers", {}))
    rng = request.headers.get("range")
    if rng:
        upstream_headers["Range"] = rng

    async with httpx.AsyncClient(timeout=30, follow_redirects=True) as c:
        probe = await c.request(
            "GET", data["url"], headers={**upstream_headers, "Range": rng or "bytes=0-0"}
        )
    status = probe.status_code if probe.status_code in (200, 206) else 200
    resp_headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": probe.headers.get("content-type", "video/mp4"),
    }
    if rng and "content-range" in probe.headers:
        resp_headers["Content-Range"] = probe.headers["content-range"]

    async def stream():
        async with httpx.AsyncClient(timeout=None, follow_redirects=True) as c:
            async with c.stream("GET", data["url"], headers=upstream_headers) as r:
                async for chunk in r.aiter_bytes(chunk_size=65536):
                    yield chunk

    return StreamingResponse(stream(), status_code=status, headers=resp_headers)
