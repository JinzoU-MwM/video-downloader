# Native Video Downloader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app that captures TikTok/Instagram/Facebook videos via the Share sheet, resolves the real media via a self-hosted `yt-dlp` backend, and downloads them in the background with notifications + a local gallery. APK is built on jni-server.

**Architecture:** Two deliverables. (B) A FastAPI + `yt-dlp` Docker service on jni-server resolves direct media URLs. (A) A Kotlin/Compose MVVM app calls that service, downloads with WorkManager/OkHttp (Range resume), saves via MediaStore, and tracks history in Room. The APK builds inside a Docker image on jni-server (host has no JDK/SDK).

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Hilt, Room (KSP), WorkManager, Retrofit/OkHttp, MediaStore; Python 3.12 + FastAPI + yt-dlp; Docker; Gradle Kotlin DSL.

## Global Constraints

- **minSdk 26, targetSdk 34, compileSdk 34.** (verbatim from spec §4)
- **KSP, not kapt**, for Room + Hilt. (spec §4 / §5 memory guard)
- **Gradle memory guard:** `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m`, `org.gradle.daemon=false`, `org.gradle.parallel=false`. (spec §5)
- **Package namespace:** `com.jni.videodownloader`.
- **App stays scraper-free** — all extraction goes through the backend `/extract`. (spec §2)
- **Backend auth:** `/extract` requires `X-API-Key`; `/proxy` requires an HMAC token. (spec §3)
- **Signing:** debug-signed APK, sideload. (spec §5)
- **Build host:** jni-server (`ssh jni-server`), Docker only — never install JDK/SDK on the host. (spec §5)
- **Backend host path:** `/data/docker/video-dl-api`; build staging: `/data/build/video-downloader`. (spec §5)
- Room `downloads` columns exactly per spec §4 table.

---

## Phase 0 — Buildable skeleton + VPS build pipeline (de-risk first)

### Task 1: Gradle project skeleton (compiles to a "Hello" Compose app)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/jni/videodownloader/ui/main/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Create: Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`)

**Interfaces:**
- Produces: a Gradle project whose `:app:assembleDebug` yields `app/build/outputs/apk/debug/app-debug.apk`. Namespace `com.jni.videodownloader`. `applicationId` same.

- [ ] **Step 1: `gradle/libs.versions.toml`** (version catalog — single source of dependency versions)

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
hilt = "2.52"
room = "2.6.1"
workmanager = "2.9.1"
retrofit = "2.11.0"
okhttp = "4.12.0"
coroutines = "1.8.1"
composeBom = "2024.09.02"
lifecycle = "2.8.5"
activityCompose = "1.9.2"
coil = "2.7.0"
hiltWork = "1.2.0"
navCompose = "2.8.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navCompose" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "hiltWork" }
hilt-work-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltWork" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-moshi = { module = "com.squareup.retrofit2:converter-moshi", version.ref = "retrofit" }
moshi-kotlin = { module = "com.squareup.moshi:moshi-kotlin", version = "1.15.1" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
junit = { module = "junit:junit", version = "4.13.2" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 2: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VideoDownloader"
include(":app")
```

- [ ] **Step 3: root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 4: `gradle.properties`** (memory guard from Global Constraints)

```properties
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
ksp.incremental=true
```

- [ ] **Step 5: `app/build.gradle.kts`** (full app module config — all deps declared now so later tasks only add code)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.jni.videodownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jni.videodownloader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Backend base URL + API key are injected as BuildConfig fields.
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://dl.jni.my.id/\"")
        buildConfigField("String", "BACKEND_API_KEY", "\"REPLACE_AT_BUILD\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 6: `app/proguard-rules.pro`** (empty placeholder)

```proguard
# Keep default; release not minified in v1.
```

- [ ] **Step 7: `app/src/main/res/values/strings.xml` and `themes.xml`**

```xml
<!-- strings.xml -->
<resources>
    <string name="app_name">R Downloader</string>
</resources>
```

```xml
<!-- themes.xml -->
<resources>
    <style name="Theme.VideoDownloader" parent="android:Theme.Material.Light.NoActionBar" />
    <style name="Theme.VideoDownloader.Transparent" parent="android:Theme.Translucent.NoTitleBar" />
</resources>
```

- [ ] **Step 8: `app/src/main/AndroidManifest.xml`** (skeleton — just MainActivity for now)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.VideoDownloader">
        <activity
            android:name=".ui.main.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.VideoDownloader">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: minimal `MainActivity.kt`**

```kotlin
package com.jni.videodownloader.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Text("R Downloader") } } }
    }
}
```

- [ ] **Step 10: Gradle wrapper.** Create `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Obtain `gradle-wrapper.jar` + `gradlew`/`gradlew.bat` by running (locally, if Gradle present, OR fetch the jar) — if no local Gradle, download wrapper jar from `https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar`. The VPS build image (Task 2) also has Gradle and can run `gradle wrapper` to regenerate. Document in Task 2 that the build runs `gradle wrapper --gradle-version 8.9` if the jar is missing.

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "feat: gradle skeleton compose app"
```

---

### Task 2: VPS build image + build script (prove the pipeline end-to-end)

**Files:**
- Create: `Dockerfile.build`
- Create: `scripts/build-on-vps.ps1`
- Create: `scripts/build-inside-container.sh`

**Interfaces:**
- Produces: running `scripts/build-on-vps.ps1` syncs source to `jni-server:/data/build/video-downloader`, builds in Docker, and copies `app-debug.apk` to `./dist/app-debug.apk` locally.

- [ ] **Step 1: `Dockerfile.build`** (Android SDK build environment; host stays clean)

```dockerfile
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=$ANDROID_HOME
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip wget git && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline.zip && \
    unzip -q cmdline.zip && mv cmdline-tools latest && rm cmdline.zip

RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

WORKDIR /workspace
```

- [ ] **Step 2: `scripts/build-inside-container.sh`** (runs in the container)

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /workspace
API_KEY="${BACKEND_API_KEY:-dev-key}"
BASE_URL="${BACKEND_BASE_URL:-https://dl.jni.my.id/}"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  gradle wrapper --gradle-version 8.9
fi
chmod +x ./gradlew
# Inject secrets without committing them
./gradlew :app:assembleDebug --no-daemon \
  -PbackendApiKey="$API_KEY" -PbackendBaseUrl="$BASE_URL"
echo "APK at app/build/outputs/apk/debug/app-debug.apk"
```

> Note: update `app/build.gradle.kts` `buildConfigField` to read `project.findProperty("backendApiKey")` / `backendBaseUrl` so secrets are injected at build time, not committed. (Apply this small edit in this task.)

- [ ] **Step 3: `scripts/build-on-vps.ps1`** (orchestrates from Windows)

```powershell
param(
  [string]$Server = "jni-server",
  [string]$Remote = "/data/build/video-downloader",
  [string]$ApiKey = $env:BACKEND_API_KEY,
  [string]$BaseUrl = "https://dl.jni.my.id/"
)
$ErrorActionPreference = "Stop"
# 1. Sync source (exclude build artifacts) using tar over ssh (rsync may be absent on Windows)
Write-Host "Syncing source to $Server:$Remote ..."
ssh $Server "mkdir -p $Remote"
$exclude = @('.git','build','.gradle','dist','*.apk')
$tarArgs = @('-czf','-','--exclude=.git','--exclude=build','--exclude=.gradle','--exclude=dist','.')
# Use git archive when possible for a clean tree:
git archive --format=tar.gz -o dist-src.tgz HEAD 2>$null
if (-not (Test-Path dist-src.tgz)) { tar @tarArgs | Out-Null }
Get-Content dist-src.tgz -AsByteStream | ssh $Server "cat > $Remote/src.tgz && cd $Remote && tar xzf src.tgz"
Remove-Item dist-src.tgz -ErrorAction SilentlyContinue
# 2. Build image (cached after first run)
ssh $Server "cd $Remote && docker build -f Dockerfile.build -t android-build:34 ."
# 3. Run build with a persistent gradle cache volume
ssh $Server "cd $Remote && docker run --rm -v ${Remote}:/workspace -v android-gradle-cache:/root/.gradle -e BACKEND_API_KEY='$ApiKey' -e BACKEND_BASE_URL='$BaseUrl' android-build:34 bash scripts/build-inside-container.sh"
# 4. Fetch APK
New-Item -ItemType Directory -Force dist | Out-Null
scp "${Server}:${Remote}/app/build/outputs/apk/debug/app-debug.apk" dist/app-debug.apk
Write-Host "APK -> dist/app-debug.apk"
```

- [ ] **Step 4: Run the pipeline on the skeleton**

Run: `pwsh scripts/build-on-vps.ps1 -ApiKey dev-key`
Expected: first run builds the Docker image (downloads SDK, several minutes), then Gradle downloads deps, then `dist/app-debug.apk` appears. **This proves the VPS build works before any feature code.**

> If Gradle OOMs: `ssh jni-server` → add 4 GB swap (`fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`), re-run.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile.build scripts/ app/build.gradle.kts && git commit -m "build: dockerized android build pipeline on jni-server"
```

---

## Phase 1 — Extraction backend (B)

### Task 3: Backend `UrlPlatform` + `yt-dlp` extractor with tests

**Files:**
- Create: `backend/app/__init__.py`, `backend/app/config.py`, `backend/app/extractor.py`
- Create: `backend/requirements.txt`
- Test: `backend/tests/test_extractor.py`

**Interfaces:**
- Produces: `extractor.detect_platform(url:str)->str|None` returns `"TIKTOK"|"INSTAGRAM"|"FACEBOOK"|None`.
- Produces: `extractor.extract(url:str)->dict` returns `{platform,title,thumbnail,duration,video:{url,ext,filesize,http_headers}}`; raises `ExtractError` on failure.

- [ ] **Step 1: `backend/requirements.txt`**

```
fastapi==0.115.0
uvicorn[standard]==0.30.6
yt-dlp==2025.5.22
httpx==0.27.2
pytest==8.3.2
```

- [ ] **Step 2: Write failing test `backend/tests/test_extractor.py`**

```python
from app.extractor import detect_platform

def test_detect_tiktok():
    assert detect_platform("https://www.tiktok.com/@u/video/123") == "TIKTOK"
    assert detect_platform("https://vm.tiktok.com/ABC123/") == "TIKTOK"

def test_detect_instagram():
    assert detect_platform("https://www.instagram.com/reel/Cabc/") == "INSTAGRAM"
    assert detect_platform("https://instagram.com/p/Cxyz/") == "INSTAGRAM"

def test_detect_facebook():
    assert detect_platform("https://www.facebook.com/watch/?v=123") == "FACEBOOK"
    assert detect_platform("https://fb.watch/abc/") == "FACEBOOK"

def test_detect_none():
    assert detect_platform("https://youtube.com/watch?v=1") is None
```

- [ ] **Step 3: Run test, verify it fails**

Run: `cd backend && python -m pytest tests/test_extractor.py -v`
Expected: FAIL (ImportError: cannot import name detect_platform)

- [ ] **Step 4: `backend/app/config.py`**

```python
import os

class Settings:
    api_key: str = os.environ.get("API_KEY", "dev-key")
    hmac_secret: str = os.environ.get("HMAC_SECRET", "dev-secret")
    proxy_token_ttl: int = int(os.environ.get("PROXY_TOKEN_TTL", "3600"))

settings = Settings()
```

- [ ] **Step 5: `backend/app/extractor.py`** (implement detection + yt-dlp resolve)

```python
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
    progressive = [f for f in fmts
                   if f.get("vcodec") not in (None, "none")
                   and f.get("acodec") not in (None, "none")
                   and (f.get("ext") == "mp4")]
    pool = progressive or [f for f in fmts if f.get("vcodec") not in (None, "none")]
    if not pool and info.get("url"):
        return {"url": info["url"], "ext": info.get("ext", "mp4"),
                "filesize": info.get("filesize"), "http_headers": info.get("http_headers", {})}
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
    opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": True}
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        raise ExtractError(str(e))
    if "entries" in info:
        info = info["entries"][0]
    video = _pick_format(info)
    return {
        "platform": platform,
        "title": info.get("title"),
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "video": video,
    }
```

- [ ] **Step 6: Run tests, verify detection tests pass**

Run: `cd backend && python -m pytest tests/test_extractor.py -v`
Expected: PASS (4 passed). (`extract()` itself is network-dependent — not unit-tested here.)

- [ ] **Step 7: Commit**

```bash
git add backend/ && git commit -m "feat(backend): yt-dlp extractor + platform detection"
```

---

### Task 4: Backend HMAC proxy token + FastAPI app with tests

**Files:**
- Create: `backend/app/proxy.py`, `backend/app/main.py`
- Test: `backend/tests/test_api.py`

**Interfaces:**
- Consumes: `extractor.extract`, `settings`.
- Produces: `proxy.sign(payload:dict)->str`, `proxy.verify(token:str)->dict` (raises `ValueError` on bad/expired token).
- Produces: FastAPI app `main.app` with `GET /health`, `POST /extract` (`X-API-Key`), `GET /proxy?token=`.

- [ ] **Step 1: Write failing test `backend/tests/test_api.py`**

```python
from fastapi.testclient import TestClient
from app.main import app
from app import proxy

client = TestClient(app)

def test_health():
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["status"] == "ok"

def test_extract_requires_key():
    r = client.post("/extract", json={"url": "https://tiktok.com/x"})
    assert r.status_code == 401

def test_proxy_token_roundtrip():
    tok = proxy.sign({"url": "https://cdn/x.mp4", "headers": {}})
    data = proxy.verify(tok)
    assert data["url"] == "https://cdn/x.mp4"

def test_proxy_rejects_tampered_token():
    import pytest
    with pytest.raises(ValueError):
        proxy.verify("not.a.valid.token")
```

- [ ] **Step 2: Run test, verify it fails**

Run: `cd backend && python -m pytest tests/test_api.py -v`
Expected: FAIL (ImportError app.main)

- [ ] **Step 3: `backend/app/proxy.py`**

```python
import base64, hmac, hashlib, json, time
from .config import settings

def _b64e(b: bytes) -> str: return base64.urlsafe_b64encode(b).decode().rstrip("=")
def _b64d(s: str) -> bytes: return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def sign(payload: dict) -> str:
    body = dict(payload); body["exp"] = int(time.time()) + settings.proxy_token_ttl
    raw = json.dumps(body, separators=(",", ":")).encode()
    mac = hmac.new(settings.hmac_secret.encode(), raw, hashlib.sha256).digest()
    return f"{_b64e(raw)}.{_b64e(mac)}"

def verify(token: str) -> dict:
    try:
        raw_b64, mac_b64 = token.split(".")
        raw = _b64d(raw_b64)
        expected = hmac.new(settings.hmac_secret.encode(), raw, hashlib.sha256).digest()
        if not hmac.compare_digest(expected, _b64d(mac_b64)):
            raise ValueError("bad signature")
        data = json.loads(raw)
        if data.get("exp", 0) < int(time.time()):
            raise ValueError("expired")
        return data
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(str(e))
```

- [ ] **Step 4: `backend/app/main.py`**

```python
import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from yt_dlp.version import __version__ as ytdlp_version
from . import proxy
from .config import settings
from .extractor import extract, ExtractError

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
    result["proxy_token"] = proxy.sign({"url": v["url"], "headers": v.get("http_headers", {})})
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

    async def stream():
        async with httpx.AsyncClient(timeout=None, follow_redirects=True) as c:
            async with c.stream("GET", data["url"], headers=upstream_headers) as r:
                async for chunk in r.aiter_bytes(chunk_size=65536):
                    yield chunk

    # HEAD upstream to relay status/length/range headers
    async with httpx.AsyncClient(timeout=30, follow_redirects=True) as c:
        head = await c.request("GET", data["url"], headers={**upstream_headers, "Range": "bytes=0-0"})
    status = 206 if rng else 200
    resp_headers = {"Accept-Ranges": "bytes", "Content-Type": head.headers.get("content-type", "video/mp4")}
    if "content-range" in head.headers and rng:
        resp_headers["Content-Range"] = head.headers["content-range"]
    return StreamingResponse(stream(), status_code=status, headers=resp_headers)
```

- [ ] **Step 5: Run tests, verify pass**

Run: `cd backend && python -m pytest -v`
Expected: PASS (health, key-required, token roundtrip, tamper-reject + the 4 detection tests).

- [ ] **Step 6: Commit**

```bash
git add backend/ && git commit -m "feat(backend): fastapi app /health /extract /proxy with hmac token"
```

---

### Task 5: Backend Docker + deploy to jni-server

**Files:**
- Create: `backend/Dockerfile`, `backend/docker-compose.yml`, `backend/.env.example`

**Interfaces:**
- Produces: a running service on jni-server reachable at the chosen subdomain; `GET /health` returns 200.

- [ ] **Step 1: `backend/Dockerfile`**

```dockerfile
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 2: `backend/.env.example`**

```
API_KEY=change-me-strong-key
HMAC_SECRET=change-me-strong-secret
PROXY_TOKEN_TTL=3600
```

- [ ] **Step 3: `backend/docker-compose.yml`** (port published locally; reverse proxy added in Step 5 after inspecting the live proxy)

```yaml
services:
  video-dl-api:
    build: .
    container_name: video-dl-api
    restart: unless-stopped
    env_file: .env
    ports:
      - "127.0.0.1:8091:8000"
```

- [ ] **Step 4: Deploy to jni-server**

```bash
ssh jni-server "mkdir -p /data/docker/video-dl-api"
# sync backend/ (use the same tar-over-ssh approach as scripts/build-on-vps.ps1, or scp -r)
scp -r backend/* jni-server:/data/docker/video-dl-api/
ssh jni-server "cd /data/docker/video-dl-api && cp .env.example .env && sed -i 's/change-me-strong-key/<GENERATED_KEY>/; s/change-me-strong-secret/<GENERATED_SECRET>/' .env && docker compose up -d --build"
ssh jni-server "curl -s localhost:8091/health"
```
Expected: `{"status":"ok","ytdlp_version":"..."}`

- [ ] **Step 5: Wire into reverse proxy.** Inspect the existing proxy and pick a subdomain:

```bash
ssh jni-server "docker ps --format '{{.Names}}' | grep -Ei 'traefik|nginx|caddy|npm'; ls /data/docker | grep -Ei 'proxy|traefik|nginx|caddy'"
```
- If **Traefik**: add labels to the compose service (`traefik.enable=true`, Host rule `dl.jni.my.id`, port 8000) and attach the proxy network.
- If **Nginx Proxy Manager / nginx**: add a proxy host → `127.0.0.1:8091`, enable SSL.
Then verify: `curl -s https://dl.jni.my.id/health` → 200. Record the final URL; it must match `BACKEND_BASE_URL` used in the app build (Task 2 Step 3 / Task 11).

- [ ] **Step 6: Commit**

```bash
git add backend/Dockerfile backend/docker-compose.yml backend/.env.example && git commit -m "feat(backend): dockerized deploy for jni-server"
```

---

## Phase 2 — Android domain, data, DI

### Task 6: `UrlExtractor` + domain models (TDD)

**Files:**
- Create: `app/src/main/java/com/jni/videodownloader/domain/Platform.kt`
- Create: `app/src/main/java/com/jni/videodownloader/domain/UrlExtractor.kt`
- Test: `app/src/test/java/com/jni/videodownloader/domain/UrlExtractorTest.kt`

**Interfaces:**
- Produces: `enum Platform { TIKTOK, INSTAGRAM, FACEBOOK }`.
- Produces: `object UrlExtractor { fun firstUrl(text:String):String?; fun detectPlatform(url:String):Platform? }`.

- [ ] **Step 1: Failing test `UrlExtractorTest.kt`**

```kotlin
package com.jni.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test fun extracts_url_from_messy_share_text() {
        val t = "Check this out https://vm.tiktok.com/ZMabc123/ amazing!"
        assertEquals("https://vm.tiktok.com/ZMabc123/", UrlExtractor.firstUrl(t))
    }
    @Test fun returns_null_when_no_url() {
        assertNull(UrlExtractor.firstUrl("no links here"))
    }
    @Test fun detects_tiktok() {
        assertEquals(Platform.TIKTOK, UrlExtractor.detectPlatform("https://www.tiktok.com/@u/video/1"))
        assertEquals(Platform.TIKTOK, UrlExtractor.detectPlatform("https://vm.tiktok.com/abc/"))
    }
    @Test fun detects_instagram() {
        assertEquals(Platform.INSTAGRAM, UrlExtractor.detectPlatform("https://instagram.com/reel/Cx/"))
    }
    @Test fun detects_facebook() {
        assertEquals(Platform.FACEBOOK, UrlExtractor.detectPlatform("https://fb.watch/abc/"))
        assertEquals(Platform.FACEBOOK, UrlExtractor.detectPlatform("https://www.facebook.com/watch?v=1"))
    }
    @Test fun unknown_platform_is_null() {
        assertNull(UrlExtractor.detectPlatform("https://youtube.com/watch?v=1"))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `pwsh scripts/run-unit-tests.ps1` (define it: `ssh jni-server` build-image `./gradlew :app:testDebugUnitTest`) OR run locally if Android SDK present. Expected: FAIL (unresolved reference UrlExtractor).
> Simplest: unit tests run on the VPS build image too. Add to `scripts/build-inside-container.sh` an optional first arg `test` that runs `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 3: `Platform.kt`**

```kotlin
package com.jni.videodownloader.domain

enum class Platform { TIKTOK, INSTAGRAM, FACEBOOK }
```

- [ ] **Step 4: `UrlExtractor.kt`**

```kotlin
package com.jni.videodownloader.domain

object UrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s]+""")
    private val PATTERNS = listOf(
        Platform.TIKTOK to Regex("""(tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com)""", RegexOption.IGNORE_CASE),
        Platform.INSTAGRAM to Regex("""instagram\.com""", RegexOption.IGNORE_CASE),
        Platform.FACEBOOK to Regex("""(facebook\.com|fb\.watch|fb\.com)""", RegexOption.IGNORE_CASE),
    )
    fun firstUrl(text: String): String? = URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')')
    fun detectPlatform(url: String): Platform? =
        PATTERNS.firstOrNull { it.second.containsMatchIn(url) }?.first
}
```

- [ ] **Step 5: Run, verify pass.** Expected: 6 passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/.../domain app/src/test && git commit -m "feat: url extractor + platform detection (tdd)"
```

---

### Task 7: Room database (entity, dao, db, converters)

**Files:**
- Create: `data/db/DownloadEntity.kt`, `data/db/DownloadDao.kt`, `data/db/AppDatabase.kt`
- Create: `domain/DownloadStatus.kt`

**Interfaces:**
- Produces: `DownloadEntity` with columns matching spec §4; `enum DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }`.
- Produces: `DownloadDao` with `observeAll(): Flow<List<DownloadEntity>>`, `insert(e):Long`, `updateProgress(id,progress,status)`, `setCompleted(id,filePath)`, `setStatus(id,status)`, `getById(id):DownloadEntity?`, `delete(id)`.

- [ ] **Step 1: `DownloadStatus.kt`**

```kotlin
package com.jni.videodownloader.domain
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }
```

- [ ] **Step 2: `DownloadEntity.kt`** (columns exactly per spec)

```kotlin
package com.jni.videodownloader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "thumbnail") val thumbnail: String?,
    @ColumnInfo(name = "file_path") val filePath: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
```

- [ ] **Step 3: `DownloadDao.kt`**

```kotlin
package com.jni.videodownloader.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert fun insert(e: DownloadEntity): Long

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getById(id: Long): DownloadEntity?

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE id = :id")
    fun updateProgress(id: Long, progress: Int, status: String)

    @Query("UPDATE downloads SET status = :status, file_path = :filePath, progress = 100 WHERE id = :id")
    fun setCompleted(id: Long, status: String, filePath: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    fun setStatus(id: Long, status: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    fun delete(id: Long)
}
```

- [ ] **Step 4: `AppDatabase.kt`**

```kotlin
package com.jni.videodownloader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/.../data/db app/src/main/java/.../domain/DownloadStatus.kt && git commit -m "feat: room downloads schema"
```

---

### Task 8: Retrofit API + DTOs + repository (with mapping test)

**Files:**
- Create: `data/net/ExtractionApi.kt`, `data/net/Dtos.kt`
- Create: `domain/Models.kt`
- Create: `data/DownloadRepository.kt`
- Test: `app/src/test/java/.../data/MappersTest.kt`

**Interfaces:**
- Consumes: `DownloadDao`, `ExtractionApi`, `Platform`, `DownloadStatus`.
- Produces: `data class VideoInfo(platform, title, thumbnail, directUrl, proxyUrl, headers:Map<String,String>, ext, filesize)`.
- Produces: `ExtractionApi.extract(@Header("X-API-Key") key, @Body req): ExtractResponse`.
- Produces: `DownloadRepository` with `suspend fun resolve(url:String):VideoInfo`, `fun observeAll():Flow<List<Download>>`, `suspend fun create(...):Long`, plus dao pass-throughs. `fun ExtractResponse.toVideoInfo(baseUrl):VideoInfo`.

- [ ] **Step 1: `domain/Models.kt`**

```kotlin
package com.jni.videodownloader.domain

data class VideoInfo(
    val platform: Platform,
    val title: String?,
    val thumbnail: String?,
    val directUrl: String,
    val proxyUrl: String?,
    val headers: Map<String, String>,
    val ext: String,
    val filesize: Long?,
)

data class Download(
    val id: Long, val url: String, val platform: Platform, val title: String?,
    val thumbnail: String?, val filePath: String?, val status: DownloadStatus,
    val progress: Int, val createdAt: Long,
)
```

- [ ] **Step 2: `data/net/Dtos.kt`**

```kotlin
package com.jni.videodownloader.data.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ExtractRequest(val url: String)

@JsonClass(generateAdapter = true)
data class VideoDto(
    val url: String,
    val ext: String?,
    val filesize: Long?,
    @Json(name = "http_headers") val httpHeaders: Map<String, String>?,
)

@JsonClass(generateAdapter = true)
data class ExtractResponse(
    val platform: String,
    val title: String?,
    val thumbnail: String?,
    val duration: Double?,
    val video: VideoDto,
    @Json(name = "proxy_token") val proxyToken: String?,
)
```

- [ ] **Step 3: `data/net/ExtractionApi.kt`**

```kotlin
package com.jni.videodownloader.data.net

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ExtractionApi {
    @POST("extract")
    suspend fun extract(
        @Header("X-API-Key") apiKey: String,
        @Body req: ExtractRequest,
    ): ExtractResponse
}
```

- [ ] **Step 4: Failing test `MappersTest.kt`**

```kotlin
package com.jni.videodownloader.data

import com.jni.videodownloader.data.net.ExtractResponse
import com.jni.videodownloader.data.net.VideoDto
import com.jni.videodownloader.domain.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test fun maps_response_to_videoinfo_with_proxy_url() {
        val resp = ExtractResponse(
            platform = "TIKTOK", title = "t", thumbnail = "th", duration = 3.0,
            video = VideoDto("https://cdn/x.mp4", "mp4", 1000, mapOf("User-Agent" to "ua")),
            proxyToken = "TOK",
        )
        val info = resp.toVideoInfo("https://dl.jni.my.id/")
        assertEquals(Platform.TIKTOK, info.platform)
        assertEquals("https://cdn/x.mp4", info.directUrl)
        assertEquals("https://dl.jni.my.id/proxy?token=TOK", info.proxyUrl)
        assertEquals("ua", info.headers["User-Agent"])
    }
}
```

- [ ] **Step 5: Run, verify fail** (unresolved `toVideoInfo`).

- [ ] **Step 6: `data/DownloadRepository.kt`** (includes the mapper)

```kotlin
package com.jni.videodownloader.data

import com.jni.videodownloader.data.db.DownloadDao
import com.jni.videodownloader.data.db.DownloadEntity
import com.jni.videodownloader.data.net.ExtractRequest
import com.jni.videodownloader.data.net.ExtractResponse
import com.jni.videodownloader.data.net.ExtractionApi
import com.jni.videodownloader.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

fun ExtractResponse.toVideoInfo(baseUrl: String): VideoInfo = VideoInfo(
    platform = Platform.valueOf(platform),
    title = title,
    thumbnail = thumbnail,
    directUrl = video.url,
    proxyUrl = proxyToken?.let { "${baseUrl.trimEnd('/')}/proxy?token=$it" },
    headers = video.httpHeaders ?: emptyMap(),
    ext = video.ext ?: "mp4",
    filesize = video.filesize,
)

private fun DownloadEntity.toModel() = Download(
    id, url, Platform.valueOf(platform), title, thumbnail, filePath,
    DownloadStatus.valueOf(status), progress, createdAt,
)

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val api: ExtractionApi,
    @Named("apiKey") private val apiKey: String,
    @Named("baseUrl") private val baseUrl: String,
) {
    suspend fun resolve(url: String): VideoInfo =
        api.extract(apiKey, ExtractRequest(url)).toVideoInfo(baseUrl)

    fun observeAll(): Flow<List<Download>> = dao.observeAll().map { it.map(DownloadEntity::toModel) }

    fun create(url: String, platform: Platform, title: String?, thumbnail: String?, createdAt: Long): Long =
        dao.insert(DownloadEntity(
            url = url, platform = platform.name, title = title, thumbnail = thumbnail,
            filePath = null, status = DownloadStatus.QUEUED.name, progress = 0, createdAt = createdAt,
        ))

    fun getById(id: Long) = dao.getById(id)
    fun updateProgress(id: Long, progress: Int) = dao.updateProgress(id, progress, DownloadStatus.DOWNLOADING.name)
    fun setCompleted(id: Long, filePath: String) = dao.setCompleted(id, DownloadStatus.COMPLETED.name, filePath)
    fun setFailed(id: Long) = dao.setStatus(id, DownloadStatus.FAILED.name)
    fun delete(id: Long) = dao.delete(id)
}
```

- [ ] **Step 7: Run, verify pass** (1 passed).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/.../data app/src/main/java/.../domain/Models.kt app/src/test/java/.../data && git commit -m "feat: extraction api + repository + mappers (tdd)"
```

---

### Task 9: Hilt setup (Application, modules, BuildConfig wiring)

**Files:**
- Create: `App.kt`
- Create: `di/DatabaseModule.kt`, `di/NetworkModule.kt`
- Modify: `app/build.gradle.kts` (BuildConfig fields read from gradle properties — done in Task 2; confirm)
- Modify: `AndroidManifest.xml` (add `android:name=".App"`)

**Interfaces:**
- Consumes: `AppDatabase`, `DownloadDao`, `ExtractionApi`, `BuildConfig.BACKEND_BASE_URL/BACKEND_API_KEY`.
- Produces: `@Named("apiKey")`, `@Named("baseUrl")` strings; singletons for db/dao/api/OkHttp.

- [ ] **Step 1: `App.kt`**

```kotlin
package com.jni.videodownloader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

- [ ] **Step 2: `di/NetworkModule.kt`**

```kotlin
package com.jni.videodownloader.di

import com.jni.videodownloader.BuildConfig
import com.jni.videodownloader.data.net.ExtractionApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun okHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton fun moshi(): Moshi =
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Provides @Singleton fun retrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides @Singleton fun api(retrofit: Retrofit): ExtractionApi = retrofit.create(ExtractionApi::class.java)

    @Provides @Named("apiKey") fun apiKey(): String = BuildConfig.BACKEND_API_KEY
    @Provides @Named("baseUrl") fun baseUrl(): String = BuildConfig.BACKEND_BASE_URL
}
```

- [ ] **Step 3: `di/DatabaseModule.kt`**

```kotlin
package com.jni.videodownloader.di

import android.content.Context
import androidx.room.Room
import com.jni.videodownloader.data.db.AppDatabase
import com.jni.videodownloader.data.db.DownloadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "downloads.db").build()
    @Provides fun dao(db: AppDatabase): DownloadDao = db.downloadDao()
}
```

- [ ] **Step 4: Add `android:name=".App"` to `<application>` in the manifest.**

- [ ] **Step 5: Build check on VPS** (`assembleDebug`) — confirm Hilt/KSP graph compiles. Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: hilt application + db/network modules"
```

---

## Phase 3 — Download engine

### Task 10: NotificationHelper + DownloadWorker + controller

**Files:**
- Create: `work/NotificationHelper.kt`, `work/DownloadWorker.kt`, `work/DownloadController.kt`
- Modify: `AndroidManifest.xml` (permissions + FGS service type)

**Interfaces:**
- Consumes: `DownloadRepository`, `OkHttpClient`, `VideoInfo` data (passed via WorkData), MediaStore.
- Produces: `DownloadController.enqueue(downloadId:Long, directUrl, proxyUrl, headersJson, title)`; `DownloadWorker` keys `KEY_ID`, `KEY_DIRECT`, `KEY_PROXY`, `KEY_HEADERS`, `KEY_TITLE`.

- [ ] **Step 1: Manifest permissions + FGS.** Add to manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```
And inside `<application>`, declare the WorkManager FGS type by adding to the manifest (AndroidX provides the service; we only need the permission + `setForeground` with type). No manual `<service>` needed for `work-runtime` ≥ 2.9 when using `getForegroundInfo`. Ensure `android:foregroundServiceType` is satisfied by passing `FOREGROUND_SERVICE_TYPE_DATA_SYNC` in `ForegroundInfo`.

- [ ] **Step 2: `work/NotificationHelper.kt`**

```kotlin
package com.jni.videodownloader.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jni.videodownloader.R

object NotificationHelper {
    const val CHANNEL_ID = "downloads"
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    fun progress(ctx: Context, title: String, progress: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Memproses…" else "Mengunduh… $progress%")
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
    fun done(ctx: Context, title: String) =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Video selesai")
            .setAutoCancel(true)
            .build()
}
```

- [ ] **Step 3: `work/DownloadWorker.kt`** (OkHttp Range download → temp file → MediaStore)

```kotlin
package com.jni.videodownloader.work

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.work.NotificationHelper.CHANNEL_ID
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: DownloadRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationHelper.ensureChannel(applicationContext)
        val title = inputData.getString(KEY_TITLE) ?: "Video"
        val n = NotificationHelper.progress(applicationContext, title, 0, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1)
        val direct = inputData.getString(KEY_DIRECT) ?: return@withContext fail(id)
        val proxy = inputData.getString(KEY_PROXY)
        val title = inputData.getString(KEY_TITLE) ?: "video"
        val headers = inputData.getString(KEY_HEADERS)?.let { JSONObject(it) }
        setForeground(getForegroundInfo())

        val tmp = File(applicationContext.cacheDir, "dl_$id.mp4")
        val ok = tryDownload(direct, headers, tmp, id, title) ||
                 (proxy != null && tryDownload(proxy, null, tmp, id, title))
        if (!ok) { repo.setFailed(id); return@withContext Result.retry() }

        val saved = saveToMediaStore(tmp, "rdl_${id}_${System.currentTimeMillis()}.mp4")
        tmp.delete()
        repo.setCompleted(id, saved)
        notify(NotificationHelper.done(applicationContext, title))
        Result.success()
    }

    private fun tryDownload(url: String, headers: JSONObject?, out: File, id: Long, title: String): Boolean {
        return try {
            val start = if (out.exists()) out.length() else 0L
            val rb = Request.Builder().url(url)
            headers?.keys()?.forEach { rb.header(it, headers.getString(it)) }
            if (start > 0) rb.header("Range", "bytes=$start-")
            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val total = (resp.body?.contentLength() ?: -1L) + start
                resp.body!!.byteStream().use { input ->
                    java.io.RandomAccessFile(out, "rw").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(64 * 1024); var read: Int; var done = start
                        while (input.read(buf).also { read = it } != -1) {
                            raf.write(buf, 0, read); done += read
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt()
                                repo.updateProgress(id, p)
                                setProgressAsync(workDataOf(KEY_PROGRESS to p))
                                notify(NotificationHelper.progress(applicationContext, title, p, false))
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    private fun saveToMediaStore(file: File, name: String): String {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RDownloader")
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)!!
        resolver.openOutputStream(uri).use { os -> file.inputStream().use { it.copyTo(os!!) } }
        return uri.toString()
    }

    private fun notify(n: android.app.Notification) =
        androidx.core.app.NotificationManagerCompat.from(applicationContext).let {
            if (Build.VERSION.SDK_INT < 33 ||
                applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
                it.notify(NOTIF_ID, n)
        }

    private fun fail(id: Long): Result { if (id > 0) repo.setFailed(id); return Result.failure() }

    companion object {
        const val KEY_ID = "id"; const val KEY_DIRECT = "direct"; const val KEY_PROXY = "proxy"
        const val KEY_HEADERS = "headers"; const val KEY_TITLE = "title"; const val KEY_PROGRESS = "progress"
        const val NOTIF_ID = 1001
    }
}
```

- [ ] **Step 4: `work/DownloadController.kt`**

```kotlin
package com.jni.videodownloader.work

import android.content.Context
import androidx.work.*
import com.jni.videodownloader.domain.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

class DownloadController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun enqueue(downloadId: Long, info: VideoInfo, title: String) {
        val headers = JSONObject(info.headers as Map<*, *>).toString()
        val data = workDataOf(
            DownloadWorker.KEY_ID to downloadId,
            DownloadWorker.KEY_DIRECT to info.directUrl,
            DownloadWorker.KEY_PROXY to info.proxyUrl,
            DownloadWorker.KEY_HEADERS to headers,
            DownloadWorker.KEY_TITLE to title,
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("download_$downloadId")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("dl_$downloadId", ExistingWorkPolicy.KEEP, req)
    }
}
```

- [ ] **Step 5: Build check on VPS** (`assembleDebug`). Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: download worker with range resume + mediastore + notifications"
```

---

## Phase 4 — UI

### Task 11: ShareReceiverActivity + Preview dialog + ViewModel

**Files:**
- Create: `ui/share/PreviewViewModel.kt`, `ui/share/ShareReceiverActivity.kt`
- Modify: `AndroidManifest.xml` (register ShareReceiverActivity with ACTION_SEND filter)

**Interfaces:**
- Consumes: `DownloadRepository`, `DownloadController`, `UrlExtractor`.
- Produces: a translucent activity that resolves + previews + enqueues, then finishes.

- [ ] **Step 1: Manifest — register the share target.** Add inside `<application>`:

```xml
<activity
    android:name=".ui.share.ShareReceiverActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:theme="@style/Theme.VideoDownloader.Transparent">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

- [ ] **Step 2: `ui/share/PreviewViewModel.kt`**

```kotlin
package com.jni.videodownloader.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.domain.Platform
import com.jni.videodownloader.domain.UrlExtractor
import com.jni.videodownloader.domain.VideoInfo
import com.jni.videodownloader.work.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val info: VideoInfo) : PreviewState
    data class Error(val message: String) : PreviewState
    data object Done : PreviewState
}

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val controller: DownloadController,
) : ViewModel() {
    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state
    private var resolved: VideoInfo? = null
    private var sourceUrl: String? = null

    fun start(sharedText: String) {
        val url = UrlExtractor.firstUrl(sharedText)
        if (url == null || UrlExtractor.detectPlatform(url) == null) {
            _state.value = PreviewState.Error("Tautan tidak didukung"); return
        }
        sourceUrl = url
        viewModelScope.launch {
            _state.value = try {
                val info = withContext(Dispatchers.IO) { repo.resolve(url) }
                resolved = info; PreviewState.Ready(info)
            } catch (e: Exception) { PreviewState.Error(e.message ?: "Gagal mengekstrak") }
        }
    }

    fun confirm() {
        val info = resolved ?: return; val url = sourceUrl ?: return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                repo.create(url, info.platform, info.title, info.thumbnail, System.currentTimeMillis())
            }
            controller.enqueue(id, info, info.title ?: "Video")
            _state.value = PreviewState.Done
        }
    }
}
```

- [ ] **Step 3: `ui/share/ShareReceiverActivity.kt`** (Compose dialog + 3s auto-start)

```kotlin
package com.jni.videodownloader.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    private val vm: PreviewViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) { Toast.makeText(this, "Tidak ada tautan", Toast.LENGTH_SHORT).show(); finish(); return }
        vm.start(text)
        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state) { if (state is PreviewState.Done) { finish() } }
                when (val s = state) {
                    is PreviewState.Loading -> Dialog { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Memproses tautan…") }
                    is PreviewState.Error -> { LaunchedEffect(Unit) { Toast.makeText(this@ShareReceiverActivity, s.message, Toast.LENGTH_LONG).show(); finish() } }
                    is PreviewState.Ready -> {
                        var count by remember { mutableStateOf(3) }
                        LaunchedEffect(Unit) { while (count > 0) { delay(1000); count-- }; vm.confirm() }
                        Dialog {
                            s.info.thumbnail?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().height(160.dp)) }
                            Spacer(Modifier.height(8.dp))
                            Text(s.info.title ?: "Video", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(16.dp))
                            Row {
                                TextButton(onClick = { finish() }) { Text("Batal") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.confirm() }) { Text("Unduh ($count)") }
                            }
                        }
                    }
                    is PreviewState.Done -> {}
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Dialog(content: @androidx.compose.runtime.Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}
```

- [ ] **Step 4: Build check on VPS.** Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: share receiver activity + preview dialog with 3s auto-download"
```

---

### Task 12: MainActivity gallery/history (Compose) + ViewModel + notif permission

**Files:**
- Modify: `ui/main/MainActivity.kt`
- Create: `ui/main/MainViewModel.kt`, `ui/main/GalleryScreen.kt`

**Interfaces:**
- Consumes: `DownloadRepository`, `Download` model.
- Produces: a grid of downloads with thumbnail, platform, status/progress; tap to open completed video; requests POST_NOTIFICATIONS on 33+.

- [ ] **Step 1: `ui/main/MainViewModel.kt`**

```kotlin
package com.jni.videodownloader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.domain.Download
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: DownloadRepository,
) : ViewModel() {
    val items: StateFlow<List<Download>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun delete(id: Long) = viewModelScope.launch { withContext(Dispatchers.IO) { repo.delete(id) } }
}
```

- [ ] **Step 2: `ui/main/GalleryScreen.kt`**

```kotlin
package com.jni.videodownloader.ui.main

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jni.videodownloader.domain.Download
import com.jni.videodownloader.domain.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun GalleryScreen(vm: MainViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("R Downloader") }) }) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Bagikan video dari TikTok/Instagram/Facebook ke aplikasi ini.")
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(8.dp)) {
                items(items, key = { it.id }) { d -> DownloadCard(d) { open(ctx, d) } }
            }
        }
    }
}

private fun open(ctx: android.content.Context, d: Download) {
    if (d.status == DownloadStatus.COMPLETED && d.filePath != null) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(d.filePath.toUri(), "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

@androidx.compose.runtime.Composable
private fun DownloadCard(d: Download, onClick: () -> Unit) {
    Card(Modifier.padding(6.dp).fillMaxWidth(), onClick = onClick) {
        Column {
            d.thumbnail?.let { AsyncImage(model = it, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(120.dp)) }
            Column(Modifier.padding(8.dp)) {
                Text(d.platform.name, style = MaterialTheme.typography.labelSmall)
                Text(d.title ?: "Video", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                when (d.status) {
                    DownloadStatus.DOWNLOADING -> LinearProgressIndicator(progress = { d.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    DownloadStatus.QUEUED -> Text("Menunggu…", style = MaterialTheme.typography.labelSmall)
                    DownloadStatus.FAILED -> Text("Gagal", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                    DownloadStatus.COMPLETED -> Text("Selesai", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Replace `MainActivity.kt`** (Hilt entry + notif permission request)

```kotlin
package com.jni.videodownloader.ui.main

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { GalleryScreen(vm) } }
    }
}
```

- [ ] **Step 4: Build check on VPS.** Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: gallery/history screen + notification permission"
```

---

## Phase 5 — Integration, build, deliver

### Task 13: Full build on VPS + manual device acceptance

**Files:** none (integration)

- [ ] **Step 1: Generate strong secrets and align backend + app.**

```bash
ssh jni-server "openssl rand -hex 24"   # API_KEY
ssh jni-server "openssl rand -hex 32"   # HMAC_SECRET
```
Set these in `/data/docker/video-dl-api/.env`, `docker compose up -d`. Use the **same** API_KEY for the app build.

- [ ] **Step 2: Build the release-candidate debug APK with real backend values.**

Run: `pwsh scripts/build-on-vps.ps1 -ApiKey "<API_KEY>" -BaseUrl "https://dl.jni.my.id/"`
Expected: `dist/app-debug.apk` produced.

- [ ] **Step 3: Smoke-test the backend with a real public URL.**

```bash
ssh jni-server "curl -s -X POST https://dl.jni.my.id/extract -H 'X-API-Key: <API_KEY>' -H 'Content-Type: application/json' -d '{\"url\":\"<public tiktok url>\"}' | head -c 400"
```
Expected: JSON with `video.url`, `title`, `proxy_token`.

- [ ] **Step 4: Manual device acceptance checklist** (sideload `dist/app-debug.apk`):
  - Install APK (enable "install unknown apps").
  - Open TikTok → Share → "R Downloader" appears in the sheet.
  - Preview dialog shows title/thumbnail; auto-downloads after 3s.
  - Progress notification appears; on finish shows "Video selesai".
  - Video plays from the in-app gallery and appears in the device gallery (Movies/RDownloader).
  - Repeat for a public Instagram reel and a public Facebook video.
  - Airplane-mode mid-download → re-enable → WorkManager resumes.

- [ ] **Step 5: Write `README.md`** documenting: architecture, backend deploy, build command, install steps, and the IG/FB-private limitation. Commit.

```bash
git add README.md && git commit -m "docs: readme with build + deploy + usage"
```

- [ ] **Step 6: Deliver** `dist/app-debug.apk` to the user with install + share-test instructions.

---

## Self-Review (completed during authoring)

**Spec coverage:** Share intent (Task 11), platform auto-detect (Task 6), extraction backend (Tasks 3–5), preview dialog + 3s auto (Task 11), background download + resume + notifications (Task 10), MediaStore gallery save (Task 10), Room history exactly per schema (Task 7), gallery grid (Task 12), VPS Docker build (Tasks 1–2, 13), Hilt/MVVM/Compose/Room/WorkManager/Retrofit (throughout). `/proxy` fallback (Tasks 4, 10). Non-functional: <2s reaction handled by translucent activity + async resolve; 500 MB via streamed Range download; auto-resume via WorkManager retry + Range. ✔ all spec sections mapped.

**Placeholder scan:** No TBD/TODO in code steps; every code step contains full content. `<GENERATED_KEY>`/`<public tiktok url>`/subdomain are runtime values, not code placeholders — resolved in Task 5/13. ✔

**Type consistency:** `VideoInfo`, `ExtractResponse.toVideoInfo`, `DownloadRepository` method names, `DownloadWorker.KEY_*`, `DownloadController.enqueue(id, info, title)` are consistent across Tasks 8/10/11. `DownloadStatus`/`Platform` enums shared. ✔
