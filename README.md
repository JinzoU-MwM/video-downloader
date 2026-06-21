# R Downloader — Native Android Video Downloader

One-tap save of TikTok / Instagram / Facebook videos via the Android **Share sheet**
(no copy/paste), inspired by the iOS "R Download" flow. Share a video → it's extracted,
downloaded in the background with a progress notification, and saved to your gallery.

## Architecture

Two pieces:

| Component | Where | Tech |
|-----------|-------|------|
| **Android app** (`app/`) | Phone | Kotlin, Jetpack Compose, MVVM, Room, WorkManager, Hilt, Retrofit/OkHttp, MediaStore |
| **Extraction backend** (`backend/`) | jni-server (Docker) | Python, FastAPI, `yt-dlp` |

The app never scrapes social platforms in-process. It sends the shared URL to the
self-hosted backend, which resolves the real media URL with `yt-dlp`. When a platform
changes, you update the **backend container** — not the installed APK.

```
Share sheet ─▶ ShareReceiverActivity ─▶ POST /extract (backend) ─▶ preview dialog
   ─▶ DownloadWorker (OkHttp + Range resume) ─▶ MediaStore (Movies/RDownloader) ─▶ gallery
```

### Backend endpoints
- `GET /health` → `{status, ytdlp_version}`
- `POST /extract` (`X-API-Key`) → `{platform, title, thumbnail, video:{url, http_headers, …}, proxy_token}`
- `GET /proxy?token=…` → streams media with HTTP Range support (fallback when a CDN
  signed URL rejects the phone; the `token` is an HMAC, so it's not an open proxy)

## Deploy the backend (jni-server)

```bash
# on jni-server
cd /data/docker/video-dl-api
# .env holds API_KEY / HMAC_SECRET / PROXY_TOKEN_TTL (generated once)
docker compose up -d --build
curl -s http://127.0.0.1:8091/health
```

Public ingress is via Cloudflare Tunnel: `rdl-api.jni.my.id → http://127.0.0.1:8091`
(rule in `~/.cloudflared/config.yml`, DNS via `cloudflared tunnel route dns`). TLS is
terminated at Cloudflare's edge.

To update extraction after a platform change:
```bash
cd /data/docker/video-dl-api && docker compose up -d --build   # pulls latest yt-dlp
```

## Build the APK (on jni-server, host stays clean)

The build runs inside a Docker image with the Android SDK (jni-server has no JDK/SDK).

```powershell
# from this repo on Windows (needs ssh access to jni-server + a committed HEAD)
powershell -File scripts/build-on-vps.ps1 -ApiKey "<BACKEND_API_KEY>" -BaseUrl "https://rdl-api.jni.my.id/"
# unit tests only:
powershell -File scripts/build-on-vps.ps1 -Mode test
```
Output: `dist/app-debug.apk` (debug-signed; sideload — not a Play Store build).

`BACKEND_BASE_URL` and `BACKEND_API_KEY` are injected at build time via Gradle `-P`
properties (never committed).

## Install & use

1. Copy `dist/app-debug.apk` to your phone and install (enable "install unknown apps").
2. Open **R Downloader** once and allow the notification permission.
3. In TikTok/Instagram/Facebook: **Share → R Downloader**.
4. A preview dialog shows the title/thumbnail and auto-downloads after 3s (or tap **Unduh**).
5. Watch the progress notification; the finished video lands in the in-app gallery and in
   your device gallery under **Movies/RDownloader**.

## Limitations
- **Public content only.** Instagram/Facebook private or login-gated posts won't work
  (they need cookies/login). TikTok public videos are the most reliable.
- Personal-use tool — respect each platform's Terms of Service.
- minSdk 26 (Android 8.0), targetSdk 34.

## Repo layout
```
app/                     Android app (Kotlin/Compose)
backend/                 FastAPI + yt-dlp extraction service
Dockerfile.build         Android SDK build image
scripts/build-on-vps.ps1 Build the APK on jni-server
scripts/*.sh             Backend deploy / ingress / smoke-test helpers
docs/                    PRD + design spec + implementation plan
dist/app-debug.apk       Built APK
```
