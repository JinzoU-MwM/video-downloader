import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from yt_dlp.version import __version__ as ytdlp_version

from . import proxy
from .config import settings
from .extractor import ExtractError, extract

app = FastAPI(title="video-dl-api")


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
    v = result["video"]
    result["proxy_token"] = proxy.sign(
        {"url": v["url"], "headers": v.get("http_headers", {})}
    )
    return result


@app.get("/proxy")
async def do_proxy(token: str, request: Request):
    try:
        data = proxy.verify(token)
    except ValueError as e:
        raise HTTPException(status_code=403, detail=str(e))

    upstream_headers = dict(data.get("headers", {}))
    rng = request.headers.get("range")
    if rng:
        upstream_headers["Range"] = rng

    # Probe upstream to relay status / content-type / range headers.
    async with httpx.AsyncClient(timeout=30, follow_redirects=True) as c:
        probe = await c.request(
            "GET",
            data["url"],
            headers={**upstream_headers, "Range": rng or "bytes=0-0"},
        )
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

    status = 206 if rng else 200
    return StreamingResponse(stream(), status_code=status, headers=resp_headers)
