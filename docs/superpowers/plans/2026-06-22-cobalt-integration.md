# Cobalt Migration + Download-Options Dialog — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the backend's yt-dlp extraction engine with a self-hosted Cobalt sidecar (Cobalt-only, no fallback), and turn the Android preview dialog into a download-options dialog (quality, audio-only MP3, remember last choice).

**Architecture:** Cobalt runs as an internal-only Docker container next to the FastAPI backend. `/extract` becomes a lightweight token minter (no metadata). `/media?token&quality&mode` calls Cobalt, fetches the resulting tunnel file server-side, caches it, and streams to the phone with Range/resume — reusing the existing `/media` proxy. The Android app appends the chosen quality/mode to the `/media` URL and saves audio downloads to the Audio MediaStore.

**Tech Stack:** Python 3.12 / FastAPI / httpx / pytest (backend); Cobalt (`ghcr.io/imputnet/cobalt`, Node, Docker); Kotlin / Jetpack Compose / Hilt / WorkManager / JUnit4 (Android).

## Global Constraints

- **Cobalt is internal-only:** no host port mapping; reachable only via the compose service name `cobalt-api` on port `9000`. No Turnstile/API-key.
- **Cobalt image tag:** pin to the current stable major (verify on GHCR before deploy; at time of writing `ghcr.io/imputnet/cobalt:11`). Never use `:latest` in compose.
- **Video output is always MP4 (H.264):** do not set `allowH265`; no video-format/codec picker.
- **WhatsApp-optimize (`wa=1`, video only):** when set, the backend re-encodes the fetched file with **ffmpeg** to a WhatsApp-friendly profile (H.264 high / yuv420p / AAC / `+faststart`, cap 720p, `-preset veryfast`). **ffmpeg STAYS in the backend image** (it is NOT removed). Honest caveat surfaced in the UI: WhatsApp still recompresses inline video; this only minimizes degradation. Ignored when `mode=audio`.
- **`alwaysProxy: true`** on every Cobalt request so the backend always receives a fetchable tunnel URL (never a bare CDN redirect).
- **Backend reuse:** keep HMAC token signing (`proxy.sign`/`proxy.verify`), per-URL `asyncio.Lock`, cache dir + TTL cleanup, and `FileResponse` Range support exactly as they work today.
- **Android:** minSdk 26, targetSdk/compileSdk 34, Kotlin 2.0.20, KSP (not kapt). No Room schema change (`title`/`thumbnail` already nullable).
- **Commit messages:** do NOT add any "Co-Authored-By: Claude" / AI co-author line (user global rule).
- **Quality allowlist (backend):** `{max, 4320, 2160, 1440, 1080, 720, 480, 360, 240, 144}`, default `1080`. **Mode allowlist:** `{video, audio}`, default `video`. **`wa` accepted values:** `{0, 1, true, false, yes, no}`, default `0`. The Android UI exposes a subset (Max/1080/720/480 + WhatsApp/Audio toggles).

---

# Phase 1 — Backend (Cobalt). Independently testable with `pytest`.

All backend commands run from `D:\Codding\Project\video-downloader\backend` with the venv active:
`.\.venv\Scripts\Activate.ps1`

### Task 1: Config + Cobalt client

**Files:**
- Modify: `backend/app/config.py`
- Create: `backend/app/cobalt.py`
- Test: `backend/tests/test_cobalt.py`

**Interfaces:**
- Consumes: `settings.cobalt_api_url` (added here).
- Produces:
  - `cobalt.QUALITIES: set[str]`, `cobalt.MODES: set[str]`
  - `cobalt.CobaltError(Exception)`
  - `cobalt._post(body: dict) -> dict` (HTTP POST; monkeypatched in tests)
  - `cobalt.resolve(url: str, quality: str, mode: str) -> dict` returning `{"kind": "tunnel"|"redirect", "url": str, "filename": str|None}` or raising `CobaltError`
  - `cobalt.health() -> dict` → `{"reachable": bool, "version": str|None}`

- [ ] **Step 1: Add config field**

In `backend/app/config.py`, add this line inside `class Settings` (after the `media_ttl` line):

```python
    cobalt_api_url: str = os.environ.get("COBALT_API_URL", "http://cobalt-api:9000/")
```

- [ ] **Step 2: Write the failing tests**

Create `backend/tests/test_cobalt.py`:

```python
import pytest

import app.cobalt as cobalt


def test_resolve_tunnel(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "tunnel", "url": "http://cobalt-api:9000/tunnel?id=1", "filename": "v.mp4"})
    out = cobalt.resolve("https://tiktok.com/x", "720", "video")
    assert out["kind"] == "tunnel"
    assert out["url"] == "http://cobalt-api:9000/tunnel?id=1"


def test_resolve_redirect(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "redirect", "url": "http://cdn/v.mp4", "filename": "v.mp4"})
    out = cobalt.resolve("https://tiktok.com/x", "720", "video")
    assert out["url"] == "http://cdn/v.mp4"


def test_resolve_picker_video_unsupported(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "picker", "picker": [{"type": "photo", "url": "http://x/1.jpg"}]})
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/slideshow", "720", "video")


def test_resolve_picker_audio_ok(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "picker", "audio": "http://x/a.mp3", "audioFilename": "a.mp3", "picker": []})
    out = cobalt.resolve("https://tiktok.com/slideshow", "720", "audio")
    assert out["url"] == "http://x/a.mp3"


def test_resolve_error(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "error", "error": {"code": "error.api.fetch.fail"}})
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/x", "720", "video")


def test_resolve_unreachable(monkeypatch):
    def boom(body):
        raise RuntimeError("connection refused")
    monkeypatch.setattr(cobalt, "_post", boom)
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/x", "720", "video")


def test_request_body_audio(monkeypatch):
    captured = {}
    monkeypatch.setattr(cobalt, "_post",
                        lambda body: captured.update(body) or {"status": "tunnel", "url": "u", "filename": "a.mp3"})
    cobalt.resolve("https://tiktok.com/x", "720", "audio")
    assert captured["downloadMode"] == "audio"
    assert captured["audioFormat"] == "mp3"
    assert captured["alwaysProxy"] is True


def test_request_body_video(monkeypatch):
    captured = {}
    monkeypatch.setattr(cobalt, "_post",
                        lambda body: captured.update(body) or {"status": "tunnel", "url": "u", "filename": "v.mp4"})
    cobalt.resolve("https://tiktok.com/x", "1080", "video")
    assert captured["downloadMode"] == "auto"
    assert captured["videoQuality"] == "1080"
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `pytest tests/test_cobalt.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.cobalt'`

- [ ] **Step 4: Implement `cobalt.py`**

Create `backend/app/cobalt.py`:

```python
import httpx

from .config import settings


class CobaltError(Exception):
    pass


QUALITIES = {"max", "4320", "2160", "1440", "1080", "720", "480", "360", "240", "144"}
MODES = {"video", "audio"}

_HEADERS = {"Accept": "application/json", "Content-Type": "application/json"}


def _post(body: dict) -> dict:
    with httpx.Client(timeout=60) as c:
        r = c.post(settings.cobalt_api_url, json=body, headers=_HEADERS)
    return r.json()


def resolve(url: str, quality: str, mode: str) -> dict:
    """Ask Cobalt for the final media URL. Returns {kind, url, filename} or raises."""
    body: dict = {"url": url, "filenameStyle": "basic", "alwaysProxy": True}
    if mode == "audio":
        body["downloadMode"] = "audio"
        body["audioFormat"] = "mp3"
    else:
        body["downloadMode"] = "auto"
        body["videoQuality"] = quality

    try:
        data = _post(body)
    except Exception as e:  # network / JSON failure
        raise CobaltError(f"cobalt unreachable: {e}")

    status = data.get("status")
    if status in ("tunnel", "redirect"):
        return {"kind": status, "url": data["url"], "filename": data.get("filename")}
    if status == "picker":
        # TikTok slideshow: only the audio track is supported in v1.
        if mode == "audio" and data.get("audio"):
            return {"kind": "tunnel", "url": data["audio"], "filename": data.get("audioFilename")}
        raise CobaltError("slideshow not supported")
    if status == "error":
        code = (data.get("error") or {}).get("code", "unknown")
        raise CobaltError(f"cobalt error: {code}")
    raise CobaltError(f"unexpected cobalt status: {status}")


def health() -> dict:
    try:
        with httpx.Client(timeout=5) as c:
            r = c.get(settings.cobalt_api_url)
        data = r.json()
        return {"reachable": True, "version": (data.get("cobalt") or {}).get("version")}
    except Exception:
        return {"reachable": False, "version": None}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `pytest tests/test_cobalt.py -v`
Expected: PASS (8 passed)

- [ ] **Step 6: Commit**

```bash
git add backend/app/config.py backend/app/cobalt.py backend/tests/test_cobalt.py
git commit -m "feat(backend): add Cobalt client (resolve/health) + config"
```

---

### Task 2: Rewrite endpoints + slim extractor + WhatsApp transcode

**Files:**
- Modify: `backend/app/extractor.py` (strip all yt-dlp; keep `detect_platform`)
- Create: `backend/app/transcode.py` (WhatsApp ffmpeg profile)
- Modify: `backend/app/main.py` (new `/extract`, `/media` with `wa`, `/health`; remove `/proxy`)
- Test: `backend/tests/test_transcode.py` (new); `backend/tests/test_main.py` (new); `backend/tests/test_extractor.py` (unchanged — must still pass)

**Interfaces:**
- Consumes: `cobalt.resolve`, `cobalt.health`, `cobalt.QUALITIES`, `cobalt.MODES`, `cobalt.CobaltError`; `extractor.detect_platform`; `proxy.sign`/`proxy.verify`.
- Produces:
  - `transcode.whatsapp_args(src: str, dst: str) -> list[str]`; `transcode.transcode_whatsapp(src: str, dst: str)`
  - `POST /extract {url}` → `{platform, title:null, thumbnail:null, duration:null, video:{url, ext:"mp4", filesize:null, http_headers:{}}, proxy_token:null}`
  - `GET /media?token=&quality=&mode=&wa=` → streamed file (400 bad param, 403 bad token, 422 cobalt error)
  - `main._download_via_cobalt(page_url, quality, mode, wa: bool, path)` (monkeypatched in tests)

- [ ] **Step 1: Slim `extractor.py`**

Replace the entire contents of `backend/app/extractor.py` with:

```python
import re

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
```

- [ ] **Step 2: Write the failing tests**

Create `backend/tests/test_transcode.py`:

```python
import app.transcode as transcode


def test_whatsapp_args_has_key_flags():
    args = transcode.whatsapp_args("in.mp4", "out.mp4")
    assert args[0] == "ffmpeg"
    assert args[-1] == "out.mp4"
    assert "libx264" in args
    assert "yuv420p" in args
    assert "aac" in args
    assert "-movflags" in args
    assert "+faststart" in args
```

Create `backend/tests/test_main.py`:

```python
from fastapi.testclient import TestClient

import app.main as main

client = TestClient(main.app)
KEY = {"X-API-Key": "dev-key"}


def _token_query(url="https://www.tiktok.com/@u/video/1"):
    r = client.post("/extract", json={"url": url}, headers=KEY)
    assert r.status_code == 200
    full = r.json()["video"]["url"]
    return full.split("/media")[1]  # "?token=..."


def test_extract_returns_media_url():
    r = client.post("/extract", json={"url": "https://www.tiktok.com/@u/video/1"}, headers=KEY)
    assert r.status_code == 200
    body = r.json()
    assert body["platform"] == "TIKTOK"
    assert body["title"] is None
    assert body["thumbnail"] is None
    assert "/media?token=" in body["video"]["url"]


def test_extract_rejects_unsupported():
    r = client.post("/extract", json={"url": "https://youtube.com/watch?v=1"}, headers=KEY)
    assert r.status_code == 422


def test_extract_requires_key():
    r = client.post("/extract", json={"url": "https://www.tiktok.com/@u/video/1"})
    assert r.status_code == 401


def test_media_bad_quality():
    r = client.get(f"/media{_token_query()}&quality=999&mode=video")
    assert r.status_code == 400


def test_media_bad_mode():
    r = client.get(f"/media{_token_query()}&quality=720&mode=foo")
    assert r.status_code == 400


def test_media_bad_wa():
    r = client.get(f"/media{_token_query()}&quality=720&mode=video&wa=2")
    assert r.status_code == 400


def test_media_success(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))

    def fake_dl(page_url, quality, mode, wa, path):
        with open(path, "wb") as f:
            f.write(b"FAKEDATA")

    monkeypatch.setattr(main, "_download_via_cobalt", fake_dl)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video")
    assert r.status_code == 200
    assert r.content == b"FAKEDATA"
    assert r.headers["content-type"].startswith("video/mp4")


def test_media_wa_flag_passed_through(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))
    seen = {}

    def fake_dl(page_url, quality, mode, wa, path):
        seen["wa"] = wa
        open(path, "wb").write(b"X")

    monkeypatch.setattr(main, "_download_via_cobalt", fake_dl)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video&wa=1")
    assert r.status_code == 200
    assert seen["wa"] is True


def test_media_audio_content_type(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))
    monkeypatch.setattr(main, "_download_via_cobalt",
                        lambda page_url, quality, mode, wa, path: open(path, "wb").write(b"A"))
    r = client.get(f"/media{_token_query()}&quality=720&mode=audio")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/mpeg")


def test_media_cobalt_error(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))

    def boom(page_url, quality, mode, wa, path):
        raise main.cobalt.CobaltError("slideshow not supported")

    monkeypatch.setattr(main, "_download_via_cobalt", boom)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video")
    assert r.status_code == 422
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `pytest tests/test_transcode.py tests/test_main.py -v`
Expected: FAIL — `app.transcode` does not exist yet, and current `main.py` imports `from .extractor import ExtractError, extract` (now removed) → `ImportError`.

- [ ] **Step 4a: Implement `transcode.py`**

Create `backend/app/transcode.py`:

```python
import shutil
import subprocess


def whatsapp_args(src: str, dst: str) -> list[str]:
    # Cap the minor axis to 720 without upscaling, keep aspect, force even dims.
    # landscape (iw>ih): height 720, width auto-even; portrait: width 720, height auto-even.
    vf = (
        "scale="
        "w='if(gt(iw,ih),-2,trunc(min(720,iw)/2)*2)':"
        "h='if(gt(iw,ih),trunc(min(720,ih)/2)*2,-2)'"
    )
    return [
        "ffmpeg", "-y", "-i", src,
        "-vf", vf,
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-crf", "23", "-preset", "veryfast",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        dst,
    ]


def transcode_whatsapp(src: str, dst: str):
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg not available")
    subprocess.run(whatsapp_args(src, dst), check=True, capture_output=True)
```

- [ ] **Step 4b: Rewrite `main.py`**

Replace the entire contents of `backend/app/main.py` with:

```python
import asyncio
import hashlib
import os
import time

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

from . import cobalt, extractor, proxy, transcode
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


def _download_via_cobalt(page_url: str, quality: str, mode: str, wa: bool, path: str):
    """Resolve via Cobalt, stream the file, optionally transcode for WhatsApp."""
    result = cobalt.resolve(page_url, quality, mode)
    src = path + ".src"
    with httpx.stream("GET", result["url"], timeout=None, follow_redirects=True) as r:
        r.raise_for_status()
        with open(src, "wb") as f:
            for chunk in r.iter_bytes(65536):
                f.write(chunk)
    if wa and mode == "video":
        try:
            transcode.transcode_whatsapp(src, path)
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
                await asyncio.to_thread(_download_via_cobalt, page_url, quality, mode, wa_flag, path)
            except cobalt.CobaltError as e:
                raise HTTPException(status_code=422, detail=str(e))

    # FileResponse serves with HTTP Range support, so the app can resume.
    return FileResponse(path, media_type=media_type, filename=f"media.{ext}")
```

- [ ] **Step 5: Run the full backend test suite**

Run: `pytest -v`
Expected: PASS — `test_cobalt.py` (8), `test_transcode.py` (1), `test_main.py` (10), `test_extractor.py` (4) all green.

- [ ] **Step 6: Commit**

```bash
git add backend/app/extractor.py backend/app/transcode.py backend/app/main.py backend/tests/test_transcode.py backend/tests/test_main.py
git commit -m "feat(backend): Cobalt-only /extract + /media (quality/mode/wa) with WhatsApp transcode; drop yt-dlp paths"
```

---

### Task 3: Docker packaging (Cobalt sidecar) + cleanup

**Files:**
- Modify: `backend/requirements.txt` (remove yt-dlp)
- Unchanged: `backend/Dockerfile` (ffmpeg STAYS — required by the WhatsApp transcode)
- Modify: `backend/docker-compose.yml` (add `cobalt-api`, wire `COBALT_API_URL`)
- Delete: `scripts/ytdlp-autoupdate.sh`, `scripts/install-cron.sh` (yt-dlp-specific)

**Interfaces:** none (deployment artifacts). Verified by `docker compose config` and image build.

- [ ] **Step 1: Trim `requirements.txt`**

Replace the entire contents of `backend/requirements.txt` with:

```
fastapi==0.115.0
uvicorn[standard]==0.30.6
httpx==0.27.2
pytest==8.3.2
```

- [ ] **Step 2: Confirm `Dockerfile` keeps ffmpeg (no change)**

The WhatsApp transcode (`wa=1`) shells out to ffmpeg, so it must stay in the image. No edit needed — confirm `backend/Dockerfile` still reads:

```dockerfile
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 3: Add the Cobalt sidecar to compose**

Replace the entire contents of `backend/docker-compose.yml` with:

```yaml
services:
  video-dl-api:
    build: .
    container_name: video-dl-api
    restart: unless-stopped
    env_file: .env
    environment:
      - COBALT_API_URL=http://cobalt-api:9000/
    ports:
      - "127.0.0.1:8091:8000"
    depends_on:
      - cobalt-api

  cobalt-api:
    # Pin to the current stable major — verify the tag on GHCR before deploy.
    image: ghcr.io/imputnet/cobalt:11
    container_name: cobalt-api
    restart: unless-stopped
    init: true
    environment:
      - API_URL=http://cobalt-api:9000/
    # internal-only: no `ports:` block on purpose.
```

- [ ] **Step 4: Remove the obsolete yt-dlp auto-update scripts**

```bash
git rm scripts/ytdlp-autoupdate.sh scripts/install-cron.sh
```

- [ ] **Step 5: Validate compose + build the backend image**

Run:
```bash
docker compose -f backend/docker-compose.yml config
docker build -t video-dl-api-test backend
```
Expected: `config` prints both services with no errors; the build succeeds (yt-dlp gone from `pip install`; ffmpeg still installed for the transcode).

- [ ] **Step 6: Commit**

```bash
git add backend/requirements.txt backend/docker-compose.yml
git commit -m "feat(backend): run Cobalt as internal sidecar; drop yt-dlp dep (keep ffmpeg)"
```

> **Deploy note (manual, post-merge):** on jni-server, `docker compose pull cobalt-api && docker compose up -d`. Replace the old yt-dlp auto-update cron with a Cobalt one (e.g. Watchtower watching `cobalt-api`, or a daily `docker compose pull cobalt-api && docker compose up -d`). Update README to drop yt-dlp references. These are ops steps, not code, and are out of this plan's automated scope.

---

# Phase 2 — Android (options dialog + audio).

> Android builds/tests run through the existing VPS pipeline (`scripts/build-on-vps.ps1`); JVM unit tests run as part of `./gradlew :app:testDebugUnitTest`. There is no local Android SDK, so "run" steps below execute on the VPS build host. Compose UI and MediaStore changes are verified by successful build + the final manual device test (Task 7/8), since they can't be JVM-unit-tested without Robolectric (not in the dependency set).

### Task 4: Domain — `DownloadOptions` (pure, unit-tested)

**Files:**
- Create: `app/src/main/java/com/jni/videodownloader/domain/DownloadOptions.kt`
- Test: `app/src/test/java/com/jni/videodownloader/domain/DownloadOptionsTest.kt`

**Interfaces:**
- Consumes: `Platform` enum (existing, values `TIKTOK`/`INSTAGRAM`/`FACEBOOK`).
- Produces:
  - `enum class DownloadMode { VIDEO, AUDIO }`
  - `enum class VideoQuality(apiValue: String, label: String) { MAX, Q1080, Q720, Q480 }` + `VideoQuality.fromApiValue(String?): VideoQuality`
  - `data class DownloadOptions(quality, mode, whatsapp: Boolean = false)` with `mediaUrl(base: String): String` (appends `&wa=1` only when `whatsapp && mode == VIDEO`) and `val fileExt: String`
  - `fun defaultTitle(platform: Platform, createdAt: Long): String`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jni/videodownloader/domain/DownloadOptionsTest.kt`:

```kotlin
package com.jni.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOptionsTest {

    @Test
    fun video_url_appends_quality_and_mode() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.VIDEO)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=video",
            o.mediaUrl("https://h/media?token=X"),
        )
        assertEquals("mp4", o.fileExt)
    }

    @Test
    fun audio_url_uses_audio_mode_and_mp3_ext() {
        val o = DownloadOptions(VideoQuality.MAX, DownloadMode.AUDIO)
        assertEquals(
            "https://h/media?token=X&quality=max&mode=audio",
            o.mediaUrl("https://h/media?token=X"),
        )
        assertEquals("mp3", o.fileExt)
    }

    @Test
    fun fromApiValue_falls_back_to_1080() {
        assertEquals(VideoQuality.Q1080, VideoQuality.fromApiValue("weird"))
        assertEquals(VideoQuality.Q1080, VideoQuality.fromApiValue(null))
        assertEquals(VideoQuality.Q480, VideoQuality.fromApiValue("480"))
    }

    @Test
    fun defaultTitle_contains_platform_name() {
        assertTrue(defaultTitle(Platform.TIKTOK, 0L).contains("TIKTOK"))
    }

    @Test
    fun whatsapp_video_appends_wa_flag() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.VIDEO, whatsapp = true)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=video&wa=1",
            o.mediaUrl("https://h/media?token=X"),
        )
    }

    @Test
    fun whatsapp_ignored_for_audio() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.AUDIO, whatsapp = true)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=audio",
            o.mediaUrl("https://h/media?token=X"),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jni.videodownloader.domain.DownloadOptionsTest"`
Expected: FAIL — `DownloadOptions`/`VideoQuality`/`DownloadMode`/`defaultTitle` unresolved.

- [ ] **Step 3: Implement `DownloadOptions.kt`**

Create `app/src/main/java/com/jni/videodownloader/domain/DownloadOptions.kt`:

```kotlin
package com.jni.videodownloader.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DownloadMode { VIDEO, AUDIO }

enum class VideoQuality(val apiValue: String, val label: String) {
    MAX("max", "Maksimal"),
    Q1080("1080", "1080p"),
    Q720("720", "720p"),
    Q480("480", "480p");

    companion object {
        fun fromApiValue(v: String?): VideoQuality =
            entries.firstOrNull { it.apiValue == v } ?: Q1080
    }
}

data class DownloadOptions(
    val quality: VideoQuality = VideoQuality.Q1080,
    val mode: DownloadMode = DownloadMode.VIDEO,
    val whatsapp: Boolean = false,
) {
    /** base is the backend /media URL that already carries `?token=…`. */
    fun mediaUrl(base: String): String {
        val sep = if (base.contains("?")) "&" else "?"
        val m = if (mode == DownloadMode.AUDIO) "audio" else "video"
        val wa = if (whatsapp && mode == DownloadMode.VIDEO) "&wa=1" else ""
        return "$base${sep}quality=${quality.apiValue}&mode=$m$wa"
    }

    val fileExt: String get() = if (mode == DownloadMode.AUDIO) "mp3" else "mp4"
}

fun defaultTitle(platform: Platform, createdAt: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    return "${platform.name} • ${sdf.format(Date(createdAt))}"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jni.videodownloader.domain.DownloadOptionsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jni/videodownloader/domain/DownloadOptions.kt app/src/test/java/com/jni/videodownloader/domain/DownloadOptionsTest.kt
git commit -m "feat(app): DownloadOptions domain (quality/mode, media URL builder)"
```

---

### Task 5: Prefs — remember last choice

**Files:**
- Create: `app/src/main/java/com/jni/videodownloader/data/Prefs.kt`

**Interfaces:**
- Consumes: `DownloadOptions`, `VideoQuality`, `DownloadMode`; Hilt `@ApplicationContext`.
- Produces: `Prefs` (`@Singleton`, `@Inject` constructor) with `fun lastOptions(): DownloadOptions` and `fun save(options: DownloadOptions)`.

> No JVM unit test: `SharedPreferences` needs an Android `Context` and Robolectric isn't in the dependency set. Verified by build + use in Task 6 and the manual device test. The encode/decode is intentionally trivial (string apiValue + "audio"/"video").

- [ ] **Step 1: Implement `Prefs.kt`**

Create `app/src/main/java/com/jni/videodownloader/data/Prefs.kt`:

```kotlin
package com.jni.videodownloader.data

import android.content.Context
import com.jni.videodownloader.domain.DownloadMode
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.VideoQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Prefs @Inject constructor(@ApplicationContext ctx: Context) {

    private val sp = ctx.getSharedPreferences("dl_prefs", Context.MODE_PRIVATE)

    fun lastOptions(): DownloadOptions = DownloadOptions(
        quality = VideoQuality.fromApiValue(sp.getString(KEY_QUALITY, null)),
        mode = if (sp.getString(KEY_MODE, "video") == "audio") DownloadMode.AUDIO else DownloadMode.VIDEO,
        whatsapp = sp.getBoolean(KEY_WA, false),
    )

    fun save(options: DownloadOptions) {
        sp.edit()
            .putString(KEY_QUALITY, options.quality.apiValue)
            .putString(KEY_MODE, if (options.mode == DownloadMode.AUDIO) "audio" else "video")
            .putBoolean(KEY_WA, options.whatsapp)
            .apply()
    }

    private companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_MODE = "mode"
        const val KEY_WA = "wa"
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jni/videodownloader/data/Prefs.kt
git commit -m "feat(app): Prefs to remember last download options"
```

---

### Task 6: Options dialog — ViewModel + ShareReceiverActivity

**Files:**
- Modify: `app/src/main/java/com/jni/videodownloader/ui/share/PreviewViewModel.kt`
- Modify: `app/src/main/java/com/jni/videodownloader/ui/share/ShareReceiverActivity.kt`

**Interfaces:**
- Consumes: `repo.resolve(url): VideoInfo` (existing; `VideoInfo.directUrl` is the `/media` base), `Prefs`, `DownloadOptions`, `defaultTitle`, `DownloadController.enqueue(id, url, mode, title)` (defined in Task 7).
- Produces: `PreviewState.Ready(platform, baseUrl, initial)`, `PreviewViewModel.confirm(options: DownloadOptions)`.

> This task assumes Task 7's `DownloadController.enqueue(id: Long, url: String, mode: DownloadMode, title: String)` signature. If executing strictly in order, do Task 7 first or stub the controller call; the recommended subagent flow reviews both together.

- [ ] **Step 1: Rewrite `PreviewViewModel.kt`**

Replace the entire contents of `app/src/main/java/com/jni/videodownloader/ui/share/PreviewViewModel.kt` with:

```kotlin
package com.jni.videodownloader.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.data.Prefs
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.Platform
import com.jni.videodownloader.domain.UrlExtractor
import com.jni.videodownloader.domain.defaultTitle
import com.jni.videodownloader.work.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(
        val platform: Platform,
        val baseUrl: String,
        val initial: DownloadOptions,
    ) : PreviewState
    data class Error(val message: String) : PreviewState
    data object Done : PreviewState
}

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val controller: DownloadController,
    private val prefs: Prefs,
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var platform: Platform? = null
    private var baseUrl: String? = null
    private var sourceUrl: String? = null
    private var started = false
    private var enqueued = false

    fun start(sharedText: String) {
        if (started) return
        started = true
        val url = UrlExtractor.firstUrl(sharedText)
        if (url == null || UrlExtractor.detectPlatform(url) == null) {
            _state.value = PreviewState.Error("Tautan tidak didukung")
            return
        }
        sourceUrl = url
        viewModelScope.launch {
            _state.value = try {
                val info = withContext(Dispatchers.IO) { repo.resolve(url) }
                platform = info.platform
                baseUrl = info.directUrl
                PreviewState.Ready(info.platform, info.directUrl, prefs.lastOptions())
            } catch (e: Exception) {
                PreviewState.Error(e.message ?: "Gagal menyiapkan unduhan")
            }
        }
    }

    fun confirm(options: DownloadOptions) {
        val p = platform ?: return
        val base = baseUrl ?: return
        val url = sourceUrl ?: return
        if (enqueued) return
        enqueued = true
        prefs.save(options)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val title = defaultTitle(p, now)
            val finalUrl = options.mediaUrl(base)
            val id = withContext(Dispatchers.IO) {
                repo.create(url, p, title, null, now)
            }
            controller.enqueue(id, finalUrl, options.mode, title)
            _state.value = PreviewState.Done
        }
    }
}
```

- [ ] **Step 2: Rewrite `ShareReceiverActivity.kt`**

Replace the entire contents of `app/src/main/java/com/jni/videodownloader/ui/share/ShareReceiverActivity.kt` with:

```kotlin
package com.jni.videodownloader.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jni.videodownloader.domain.DownloadMode
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.VideoQuality
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val vm: PreviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Tidak ada tautan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        vm.start(text)

        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state) {
                    if (state is PreviewState.Done) {
                        Toast.makeText(this@ShareReceiverActivity, "Unduhan dimulai", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    if (state is PreviewState.Error) {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            (state as PreviewState.Error).message,
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }

                when (val s = state) {
                    is PreviewState.Loading -> PreviewCard {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Menyiapkan…")
                    }

                    is PreviewState.Ready -> OptionsCard(
                        platformName = s.platform.name,
                        initial = s.initial,
                        onCancel = { finish() },
                        onDownload = { vm.confirm(it) },
                    )

                    else -> { /* Error / Done handled by LaunchedEffect */ }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(
    platformName: String,
    initial: DownloadOptions,
    onCancel: () -> Unit,
    onDownload: (DownloadOptions) -> Unit,
) {
    var quality by remember { mutableStateOf(initial.quality) }
    var audio by remember { mutableStateOf(initial.mode == DownloadMode.AUDIO) }
    var whatsapp by remember { mutableStateOf(initial.whatsapp) }

    PreviewCard {
        Text(platformName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Text("Kualitas", style = MaterialTheme.typography.labelMedium)
        FlowRow {
            VideoQuality.entries.forEach { q ->
                FilterChip(
                    selected = quality == q && !audio,
                    enabled = !audio,
                    onClick = { quality = q },
                    label = { Text(q.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audio saja (MP3)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = audio, onCheckedChange = { audio = it })
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Optimalkan untuk WhatsApp (HD)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = whatsapp && !audio, enabled = !audio, onCheckedChange = { whatsapp = it })
        }
        Text(
            "WhatsApp tetap mengompres video; opsi ini meminimalkan 'pecah'.",
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(onClick = onCancel) { Text("Batal") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                onDownload(
                    DownloadOptions(
                        quality = quality,
                        mode = if (audio) DownloadMode.AUDIO else DownloadMode.VIDEO,
                        whatsapp = whatsapp && !audio,
                    )
                )
            }) { Text("Unduh") }
        }
    }
}

@Composable
private fun PreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`FlowRow` is `androidx.compose.foundation.layout.FlowRow`, stable in the Compose BOM `2024.09.02` in use.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jni/videodownloader/ui/share/PreviewViewModel.kt app/src/main/java/com/jni/videodownloader/ui/share/ShareReceiverActivity.kt
git commit -m "feat(app): replace preview with download-options dialog (quality/audio)"
```

---

### Task 7: Download worker — pass mode, save audio to Audio MediaStore

**Files:**
- Modify: `app/src/main/java/com/jni/videodownloader/work/DownloadController.kt`
- Modify: `app/src/main/java/com/jni/videodownloader/work/DownloadWorker.kt`

**Interfaces:**
- Consumes: `DownloadMode`; `repo` (existing).
- Produces: `DownloadController.enqueue(downloadId: Long, url: String, mode: DownloadMode, title: String)`; worker keys `KEY_URL`, `KEY_AUDIO`, `KEY_TITLE`, `KEY_STAMP`, `KEY_ID`.

- [ ] **Step 1: Rewrite `DownloadController.kt`**

Replace the entire contents of `app/src/main/java/com/jni/videodownloader/work/DownloadController.kt` with:

```kotlin
package com.jni.videodownloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jni.videodownloader.domain.DownloadMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DownloadController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun enqueue(downloadId: Long, url: String, mode: DownloadMode, title: String) {
        val data = workDataOf(
            DownloadWorker.KEY_ID to downloadId,
            DownloadWorker.KEY_URL to url,
            DownloadWorker.KEY_AUDIO to (mode == DownloadMode.AUDIO),
            DownloadWorker.KEY_TITLE to title,
            DownloadWorker.KEY_STAMP to System.currentTimeMillis(),
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("download_$downloadId")
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("dl_$downloadId", ExistingWorkPolicy.KEEP, req)
    }
}
```

- [ ] **Step 2: Rewrite `DownloadWorker.kt`**

Replace the entire contents of `app/src/main/java/com/jni/videodownloader/work/DownloadWorker.kt` with:

```kotlin
package com.jni.videodownloader.work

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.jni.videodownloader.data.DownloadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: DownloadRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    private val downloadId: Long get() = inputData.getLong(KEY_ID, -1L)
    private val notifId: Int get() = (downloadId % 90000L).toInt() + 1000
    private val isAudio: Boolean get() = inputData.getBoolean(KEY_AUDIO, false)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationHelper.ensureChannel(applicationContext)
        val title = inputData.getString(KEY_TITLE) ?: "Video"
        val n = NotificationHelper.progress(applicationContext, title, 0, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = downloadId
        val url = inputData.getString(KEY_URL) ?: return@withContext fail(id)
        val title = inputData.getString(KEY_TITLE) ?: "video"
        val ext = if (isAudio) "mp3" else "mp4"

        NotificationHelper.ensureChannel(applicationContext)
        setForeground(getForegroundInfo())

        val tmp = File(applicationContext.cacheDir, "dl_$id.$ext")
        if (!tryDownload(url, tmp, id, title)) {
            repo.setFailed(id)
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val name = "rdl_${id}_${createdStamp()}.$ext"
        val saved = if (isAudio) saveToStore(tmp, name, audio = true) else saveToStore(tmp, name, audio = false)
        tmp.delete()
        repo.setCompleted(id, saved)
        notify(NotificationHelper.done(applicationContext, title))
        Result.success()
    }

    private fun tryDownload(url: String, out: File, id: Long, title: String): Boolean {
        return try {
            var start = if (out.exists()) out.length() else 0L
            val rb = Request.Builder().url(url)
            if (start > 0) rb.header("Range", "bytes=$start-")

            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                // Server ignored our Range and is sending the whole file: restart from 0.
                if (start > 0 && resp.code == 200) {
                    out.delete(); start = 0
                }
                val body = resp.body ?: return false
                val total = if (body.contentLength() >= 0) body.contentLength() + start else -1L
                var done = start
                var lastPercent = -1
                body.byteStream().use { input ->
                    RandomAccessFile(out, "rw").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            raf.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    repo.updateProgress(id, p)
                                    notify(NotificationHelper.progress(applicationContext, title, p, false))
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveToStore(file: File, name: String, audio: Boolean): String {
        val resolver = applicationContext.contentResolver
        val mime = if (audio) "audio/mpeg" else "video/mp4"
        val relDir = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/RDownloader"
        val nameKey = MediaStore.MediaColumns.DISPLAY_NAME
        val mimeKey = MediaStore.MediaColumns.MIME_TYPE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(nameKey, name)
                put(mimeKey, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection: Uri = if (audio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values)!!
            resolver.openOutputStream(uri).use { os -> file.inputStream().use { it.copyTo(os!!) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                ),
                "RDownloader",
            )
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            val values = ContentValues().apply {
                put(nameKey, name)
                put(mimeKey, mime)
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, dest.absolutePath)
            }
            val collection = if (audio) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
            uri?.toString() ?: dest.absolutePath
        }
    }

    private fun notify(n: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { NotificationManagerCompat.from(applicationContext).notify(notifId, n) }
        }
    }

    private fun createdStamp(): Long = inputData.getLong(KEY_STAMP, downloadId)

    private fun fail(id: Long): Result {
        if (id > 0) repo.setFailed(id)
        return Result.failure()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_URL = "url"
        const val KEY_AUDIO = "audio"
        const val KEY_TITLE = "title"
        const val KEY_STAMP = "stamp"
    }
}
```

- [ ] **Step 3: Full app build + unit tests**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `DownloadOptionsTest` passes. (Confirms no stale references to the removed `KEY_DIRECT`/`KEY_PROXY`/`KEY_HEADERS`/`VideoInfo.headers` flow.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jni/videodownloader/work/DownloadController.kt app/src/main/java/com/jni/videodownloader/work/DownloadWorker.kt
git commit -m "feat(app): worker downloads single URL; save audio to Audio MediaStore"
```

- [ ] **Step 5: Manual device acceptance (final)**

Build the signed debug APK via the existing VPS pipeline (`scripts/build-on-vps.ps1`), install on a real device, then:
1. Share a public TikTok video → options dialog shows quality chips + Audio switch + WhatsApp switch → pick 720p → Unduh → file lands in Gallery (Movies/RDownloader), plays, **no watermark**.
2. Repeat with Audio switch ON → MP3 lands in Music/RDownloader, plays. Confirm the WhatsApp switch is disabled while Audio is on.
3. Repeat for an Instagram reel and a Facebook video.
4. Confirm "remember last choice": reopen the dialog → last quality/mode/WhatsApp pre-selected.
5. Toggle airplane mode mid-download → download resumes when network returns.
6. Turn ON "Optimalkan untuk WhatsApp", download, then send the file to a WhatsApp chat → compare sharpness against the same video downloaded with the option OFF (optimized one should hold up better after WhatsApp's compression).

---

## Self-Review (completed during planning)

- **Spec coverage:**
  - Cobalt sidecar (internal-only, `API_URL`) → Task 3. ✅
  - `config.cobalt_api_url` → Task 1. ✅
  - `cobalt.py` client + status mapping (tunnel/redirect/picker/error) → Task 1. ✅
  - `/extract` mints token, no metadata → Task 2. ✅
  - `/media?quality&mode&wa`, allowlist, cache key incl. quality+mode+wa, Range → Task 2. ✅
  - WhatsApp transcode (`transcode.py` ffmpeg profile, `wa=1` video-only) → Task 2. ✅
  - `/health` reports Cobalt → Task 2. ✅
  - Remove yt-dlp/`/proxy`/autoupdate scripts; **keep ffmpeg** for WA transcode → Tasks 2 & 3. ✅
  - Options dialog (quality, audio, WhatsApp, remember) → Tasks 4–6. ✅
  - Audio → Audio MediaStore → Task 7. ✅
  - Local title from platform+timestamp → Task 4 (`defaultTitle`) used in Task 6. ✅
  - Slideshow `picker` → friendly error (audio exception) → Task 1. ✅
- **Placeholder scan:** none — every code/test step has full content. The single deploy note (cron/README) is explicitly flagged as manual ops, out of automated scope.
- **Type consistency:** `enqueue(id, url, mode, title)` defined in Task 7 matches the call in Task 6; worker `KEY_URL`/`KEY_AUDIO` defined and consumed in Task 7; `DownloadOptions(quality, mode, whatsapp)` + `mediaUrl`/`fileExt`/`VideoQuality.fromApiValue`/`defaultTitle` defined in Task 4 and used in Tasks 5–7; `_download_via_cobalt(page_url, quality, mode, wa, path)` signature matches the monkeypatched fakes in `test_main.py` and the call site in `/media`; `transcode.whatsapp_args`/`transcode_whatsapp` defined in Task 2 and consumed by `_download_via_cobalt`; `cobalt.resolve` return `{kind,url,filename}` consumed by `_download_via_cobalt`.
- **Cross-task ordering note:** Task 6 references Task 7's controller signature — flagged inline; subagent-driven flow reviews them together.
```
