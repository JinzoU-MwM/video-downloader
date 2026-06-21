# Design — Native Android Video Downloader ("R Download"-style)

**Date:** 2026-06-21
**Status:** Approved (brainstorming) → implementation planning
**Source PRD:** `docs/PRD.md` (Android Native Video Downloader Ala R Download)

## 1. Goal

A native Android app that captures a TikTok / Instagram / Facebook video via the
Android **Share sheet** (no copy/paste), extracts the real video, downloads it in
the background with progress notifications, and keeps a local gallery/history —
mirroring the one-tap "share → download" UX of iOS "R Download".

## 2. Two deliverables

| # | Component | Runtime | Tech |
|---|-----------|---------|------|
| **A** | Android app (the APK) | User's phone | Kotlin, Jetpack Compose, MVVM, Room, WorkManager, Hilt, OkHttp/Retrofit |
| **B** | Extraction backend | jni-server (Docker) | Python, FastAPI, `yt-dlp` |

The app does **not** scrape social platforms in-process. It sends the shared URL to
the self-hosted backend, which resolves the direct media URL with `yt-dlp`. When a
platform changes its internals, the **backend container** is updated — not the
installed APK.

## 3. Extraction backend (B)

- **FastAPI** app wrapping **`yt-dlp`**, packaged as a Docker image, deployed under
  `/data/docker/video-dl-api` on jni-server with a `docker-compose.yml`.
- Fronted by the existing reverse proxy on a subdomain (target: `dl.jni.my.id` —
  exact name confirmed against the live proxy config during implementation).
- Protected by a **static API key** (`X-API-Key` header) so it is not an open relay.

### Endpoints

| Method | Path | Request | Response |
|--------|------|---------|----------|
| `GET` | `/health` | — | `{status:"ok", ytdlp_version}` |
| `POST` | `/extract` | `{url}` | `{platform, title, thumbnail, duration, video:{url, ext, filesize, http_headers}}` |
| `GET` | `/proxy?token=…` | signed token referencing a resolved media URL+headers | streams media bytes, **supports HTTP Range** |

- `/extract` only **resolves** (no download) using `yt-dlp` "dump single json"; picks the
  best progressive MP4 format and returns its direct CDN URL + the `http_headers`
  yt-dlp says are required (User-Agent, Referer, Cookie if any).
- `/proxy` is the **fallback** path: when a CDN signed URL rejects the phone's IP or
  headers, the app downloads through the backend instead. The proxy passes the
  client `Range` header upstream and relays `Content-Range`/`Accept-Ranges`, so
  **resume still works** through the proxy.
- The signed `token` for `/proxy` is an HMAC of the upstream URL+headers+expiry, so
  the proxy cannot be turned into an open web proxy for arbitrary URLs.

### Operational notes
- `yt-dlp` pinned in the image; updated by rebuilding the container.
- IG/FB **private** content is out of scope (needs login/cookies). Public
  posts/reels/videos only. TikTok is the most reliable source.
- Personal-use tool; respect each platform's Terms of Service.

## 4. Android app (A)

### Stack (per PRD)
Kotlin · Jetpack Compose · MVVM + StateFlow · Room · WorkManager + Foreground
Service · Retrofit + OkHttp · Hilt · MediaStore (Scoped Storage) · NotificationCompat.
**minSdk 26, targetSdk 34, compileSdk 34.** Gradle Kotlin DSL + version catalog.
**KSP** (not kapt) for Room + Hilt to keep the VPS build fast and low-memory.
Kotlin 2.0.x with the Compose Compiler Gradle plugin.

### Entry & flow

```
ShareReceiverActivity   (intent-filter: ACTION_SEND, text/plain; translucent dialog theme)
  1. Receive shared text → UrlExtractor.firstUrl(text)
  2. UrlExtractor.detectPlatform(url) → TIKTOK | INSTAGRAM | FACEBOOK (else: error toast)
  3. Call backend POST /extract  → title, thumbnail, video info
  4. Compose Preview dialog: thumbnail + title + [Unduh] / [Batal] + 3s auto-start countdown
  5. On confirm/auto → insert Room row (QUEUED) → enqueue DownloadWorker → finish()

DownloadWorker  (CoroutineWorker, runs as Foreground Service)
  - Download chosen video URL via OkHttp with Range header (resume from temp file)
  - On HTTP 403/expired direct URL → retry via backend /proxy
  - Update Room (progress 0..100, status) + progress notification (pause/resume actions)
  - On success: copy temp file into MediaStore (Movies/RDownloader/) so it appears in
    the device gallery; persist final file_path; status COMPLETED; "selesai" notification
  - WorkManager retry/backoff covers transient network failure (auto-resume)

MainActivity  (Compose, single-Activity nav)
  - Gallery/History grid from Room (thumbnail, platform badge, date, status, progress)
  - Tap completed item → play via ACTION_VIEW / in-app player; long-press → delete
```

### Modules / packages
- `data` — `AppDatabase`, `DownloadDao`, `DownloadEntity`; `ExtractionApi` (Retrofit);
  `DownloadRepository` (single source of truth, exposes `Flow<List<Download>>`).
- `domain` — models (`Download`, `Platform`, `VideoInfo`); `UrlExtractor`
  (regex URL find + platform detection).
- `work` — `DownloadWorker`, `NotificationHelper`, `DownloadManagerController`
  (enqueue/pause/resume/cancel via WorkManager).
- `ui` — `ShareReceiverActivity` + `PreviewDialog`; `MainActivity` + `GalleryScreen`;
  `MainViewModel`, `PreviewViewModel` (StateFlow).
- `di` — Hilt modules (`DatabaseModule`, `NetworkModule`, `RepositoryModule`).

### Permissions (manifest)
`INTERNET`, `POST_NOTIFICATIONS` (33+), `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_DATA_SYNC` (34), `READ_MEDIA_VIDEO` (33+),
`WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`). Saving uses MediaStore (no broad
storage permission needed on 29+).

### Room `downloads` table (exactly per PRD)
| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER PK autoGenerate | |
| `url` | TEXT | original shared URL |
| `platform` | TEXT | TIKTOK / INSTAGRAM / FACEBOOK |
| `title` | TEXT? | nullable |
| `thumbnail` | TEXT? | local thumbnail path / remote url |
| `file_path` | TEXT? | saved video path (MediaStore uri) |
| `status` | TEXT | QUEUED / DOWNLOADING / COMPLETED / FAILED |
| `progress` | INTEGER | 0–100 |
| `created_at` | INTEGER | unix ms |

## 5. Build pipeline (on jni-server)

jni-server has Docker but **no JDK / Android SDK / Gradle**. The host stays clean by
building inside a container.

1. Develop locally in `D:\Codding\Project\video-downloader`.
2. **Custom build image** `Dockerfile.build`: `eclipse-temurin:17-jdk-jammy` +
   Android cmdline-tools + `platform-tools`, `platforms;android-34`,
   `build-tools;34.0.0` (licenses accepted).
3. `rsync` source tree → `jni-server:/data/build/video-downloader` (exclude
   `.git`, `build/`, `.gradle/`).
4. `docker run` the build image mounting the source + a persistent Gradle cache
   volume → `./gradlew assembleDebug`.
5. Output `app/build/outputs/apk/debug/app-debug.apk` → `scp` back to the user.

**Signing:** debug-signed APK (sideload install; not Play Store).

**Memory guard** (`gradle.properties`): `org.gradle.jvmargs=-Xmx2g
-XX:MaxMetaspaceSize=512m`, `org.gradle.daemon=false`, `org.gradle.parallel=false`,
KSP over kapt. If the first build OOMs (7.5 GB total / ~4.6 GB free), add temporary
swap on jni-server, then retry.

## 6. Testing
- JVM unit tests run during the Gradle build:
  - `UrlExtractorTest` — extract URL from messy share text; correct platform per URL
    shape (tiktok.com, vm.tiktok.com, instagram.com/reel|p|tv, facebook.com,
    fb.watch).
  - `DownloadRepositoryTest` — entity↔model mapping, status transitions.
- Backend: `pytest` hitting `/health` and a mocked `/extract`.
- No emulator on the VPS → installing the APK on a real device is the final manual
  acceptance check; install + share-test steps delivered with the APK.

## 7. Out of scope (v1)
- IG/FB private/login-gated content.
- Play Store release / Play signing.
- iOS.
- In-app account login to social platforms.

## 8. Risks & mitigations
| Risk | Mitigation |
|------|------------|
| Platform changes break extraction | Backend `yt-dlp` update (rebuild container), no app update needed |
| Signed CDN URL rejects phone | `/proxy` streaming fallback with Range support |
| VPS build OOM | `-Xmx2g`, daemon off, KSP, add swap if needed |
| Open extractor abused | `X-API-Key` on `/extract`; HMAC token on `/proxy` |
| Large (500 MB) downloads interrupted | Range resume + WorkManager retry/backoff |
