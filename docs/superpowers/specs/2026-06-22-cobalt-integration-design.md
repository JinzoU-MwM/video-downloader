# Design — Migrasi Ekstraksi ke Cobalt (Cobalt-only) + Dialog Opsi Unduh

**Date:** 2026-06-22
**Status:** Approved (brainstorming) → implementation planning
**Source:** Tindak lanjut dari `docs/superpowers/specs/2026-06-21-video-downloader-design.md`

## 1. Goal

Mengganti mesin ekstraksi/unduh backend dari **yt-dlp** ke **Cobalt** yang
di-self-host (alasan utama: keandalan anti-bot TikTok/IG/FB yang selama ini jadi
pain point — lihat riwayat commit). Sekaligus mengubah dialog "pratinjau" di app
Android dari "tampilkan thumbnail/judul" menjadi **dialog opsi unduh** (pilih
kualitas, audio-saja MP3, ingat pilihan terakhir), karena Cobalt tidak
mengembalikan metadata judul/thumbnail.

## 2. Keputusan dari brainstorming

| Pertanyaan | Keputusan |
|---|---|
| Peran Cobalt vs yt-dlp | **Cobalt saja** — yt-dlp dibuang (tanpa fallback) |
| Dialog pratinjau (Cobalt tak punya judul/thumbnail) | Ubah jadi **dialog opsi unduh** |
| Opsi di dialog | Pilih kualitas video; Audio-saja (MP3); Ingat pilihan terakhir |
| Pemilih format video | **Tidak** — video selalu MP4 (H.264), paling kompatibel di Android |
| Optimalkan untuk WhatsApp | **Ya, toggle opsional** — transcode server-side ke profil ramah-WhatsApp (H.264 high/yuv420p + AAC + faststart, cap 720p, bitrate terkontrol) agar tahan kompresi WhatsApp. **Caveat jujur:** WhatsApp selalu mengompres ulang video inline; ini meminimalkan "pecah", bukan menonaktifkan kompresi. |
| Auto-download 3 detik | **Dihapus** (tidak dipilih user); "ingat pilihan terakhir" menjaga alur tetap ~1 ketuk |

## 3. Pendekatan terpilih

**Pendekatan A — Cobalt sebagai "mesin unduh" di balik endpoint `/media` yang
sudah ada.** Cobalt dijalankan sebagai **sidecar container internal-only**;
backend FastAPI memanggilnya, mengambil file hasil, lalu cache & stream ke HP
dengan dukungan Range (resume) — persis seperti `/media` sekarang.

**Alternatif yang ditolak:**
- **B (app fetch URL Cobalt langsung):** URL `tunnel` Cobalt internal & berumur
  pendek; URL `redirect` CDN sering menolak IP HP (justru alasan `/media` dibuat);
  mengekspos Cobalt ke publik mengundang abuse; kehilangan lapisan cache.
- **C (yt-dlp utama, Cobalt fallback):** bertentangan dengan keputusan "Cobalt saja".

**Kenapa A:** memakai ulang infrastruktur tahan-banting yang sudah jadi (cache,
Range/resume, solusi "HP tak bisa fetch CDN langsung"), dan worker Android nyaris
tak berubah selain merakit URL dan path simpan untuk audio.

## 4. Arsitektur

```
HP (app Android)
  │  1) POST /extract {url}                  → { video.url = ".../media?token=XYZ", title:null, thumbnail:null }
  │  2) [dialog opsi: kualitas / audio]
  │  3) GET  /media?token=XYZ&quality=720&mode=video   ← stream MP4 (Range/resume)
  ▼
FastAPI (video-dl-api, kontainer Python)
  │  POST http://cobalt-api:9000/  {url, videoQuality, downloadMode, audioFormat}
  │      ← { status:"tunnel", url:"http://cobalt-api:9000/tunnel?id=...", filename }
  │  fetch byte dari tunnel/redirect → cache /tmp/media/<hash>.mp4|.mp3 → FileResponse
  ▼
Cobalt (cobalt-api, kontainer Node — ghcr.io/imputnet/cobalt)
       internal-only (tanpa port publik), tanpa Turnstile/API-key
```

Kedua service berada di `docker-compose.yml` yang sama dan satu jaringan default
compose; FastAPI menjangkau Cobalt via nama service `cobalt-api`.

## 5. Cobalt (sidecar)

- **Image:** `ghcr.io/imputnet/cobalt` — pin ke tag stabil; **versi tag diverifikasi
  saat implementasi** (knowledge cutoff: Cobalt v10 = rewrite besar; kemungkinan
  v11+ saat ini).
- **Port:** mendengarkan `9000` di dalam jaringan compose; **tanpa** mapping ke
  host (internal-only).
- **Env minimum:** `API_URL=http://cobalt-api:9000/` (Cobalt memakai ini untuk
  membentuk URL `tunnel`; karena yang mengambil tunnel adalah backend lewat
  jaringan internal, alamat internal ini valid).
- **Auth:** tidak ada (Turnstile/API-key tidak perlu) karena tidak terekspos ke
  internet — isolasi jaringan docker sudah cukup. Jika kelak diekspos, wajib
  tambah API-key.
- **TikTok:** Cobalt mengunduh **tanpa watermark** secara default. `tiktokH265`
  dibiarkan mati agar output H.264 (kompatibel Android).

## 6. Backend (video-dl-api)

### 6.1 Kontrak API (sesudah perubahan)

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/health` | — | `{status:"ok", cobalt:{reachable, version}}` |
| `POST` | `/extract` | `{url}` | `{platform, title:null, thumbnail:null, duration:null, video:{url:"<base>/media?token=…", ext:"mp4", filesize:null, http_headers:{}}, proxy_token:null}` |
| `GET` | `/media?token=…&quality=…&mode=…&wa=…` | token bertanda-tangan (HMAC) atas `url` + param opsi | stream media (`video/mp4` atau `audio/mpeg`), **Range didukung** |

- **Bentuk respons `/extract` dipertahankan** (kompatibilitas Dtos app), tetapi
  `title`/`thumbnail`/`duration` selalu `null` dan `video.url` adalah base URL
  `/media`. App menambahkan `&quality=…&mode=…` saat konfirmasi.
- **Param `/media`:**
  - `quality` ∈ allowlist `{max, 2160, 1440, 1080, 720, 480, 360}` (default `1080`).
  - `mode` ∈ `{video, audio}` (default `video`).
  - `wa` ∈ `{0, 1}` (default `0`). Bila `1` dan `mode=video` → transcode ramah-WhatsApp (§6.6). Diabaikan untuk `mode=audio`.
  - Param divalidasi server-side; nilai di luar allowlist → 400.

### 6.2 Klien Cobalt — `app/cobalt.py` (baru)

- `resolve(url, quality, mode) -> {kind, url, filename}` di mana `kind` ∈
  `{tunnel, redirect, picker, error}`.
- Body POST ke Cobalt:
  - selalu: `{"url": <url>}`, header `Accept: application/json`,
    `Content-Type: application/json`.
  - `mode=video` → `videoQuality=<quality>`, `downloadMode="auto"`.
  - `mode=audio` → `downloadMode="audio"`, `audioFormat="mp3"`.
- Pemetaan status respons Cobalt:
  - `tunnel`/`redirect` → kembalikan `url` (file final).
  - `picker` → lihat §8 (slideshow).
  - `error` → angkat exception → `/media` balas 422.

### 6.3 `/media` (rewrite isi)

1. `proxy.verify(token)` → dapatkan `url` sumber (HMAC tetap dipakai).
2. Validasi `quality`+`mode`.
3. `cobalt.resolve(...)` → URL file final (tunnel/redirect).
4. Cache di disk: key = `sha256(f"{url}|{quality}|{mode}|{wa}")[:24]`, ekstensi
   `.mp4` (video) / `.mp3` (audio). Lock per-key (seperti sekarang) agar request
   bersamaan mengunduh sekali.
5. Fetch byte dari URL final → tulis ke file sementara (httpx, sudah ada).
6. Jika `wa=1` dan `mode=video` → transcode file sementara ke `path` via ffmpeg
   (§6.6); selain itu `os.replace(tmp, path)`.
7. `FileResponse(path, media_type=…, filename=…)` — Range/resume tetap.
8. TTL cache & cleanup tetap seperti sekarang.

### 6.4 Penghapusan / migrasi

- **Hapus yt-dlp:** dari `requirements.txt`; hapus seluruh pemakaian yt-dlp di
  `extractor.py` (`_pick_format`, `_extract_once`, `_download_once`, retry).
  **`detect_platform()` + pola regex dipertahankan** (masih dipakai `/extract`
  untuk validasi & set field `platform`) — sisakan di `extractor.py` ramping atau
  pindah ke util kecil.
- **ffmpeg:** **tetap dipasang** di image backend — diperlukan untuk transcode
  opsi "Optimalkan untuk WhatsApp" (§6.6). Tanpa opsi WA aktif, ffmpeg tak
  terpakai (Cobalt memproses sendiri).
- **`/proxy`:** sudah tidak dipakai app (proxy_token selalu null) → hapus.
- **`/health`:** ganti `ytdlp_version` → cek reachability+versi Cobalt
  (GET ke Cobalt `/`).
- **Auto-update:** `scripts/ytdlp-autoupdate.sh` + `scripts/install-cron.sh`
  pensiun; diganti auto-update Cobalt (Watchtower atau cron `docker compose pull
  cobalt-api && docker compose up -d`).

### 6.5 `docker-compose.yml`

Tambah service `cobalt-api` (image Cobalt, `restart: unless-stopped`,
`environment: API_URL=http://cobalt-api:9000/`, tanpa `ports`). Service
`video-dl-api` mendapat env `COBALT_API_URL=http://cobalt-api:9000/`.

### 6.6 Transcode "Optimalkan untuk WhatsApp" — `app/transcode.py` (baru)

- Hanya berjalan saat `wa=1` **dan** `mode=video`.
- Profil ffmpeg ramah-WhatsApp (meminimalkan kompresi ulang WhatsApp):
  - Video: `libx264`, `-profile:v high`, `-pix_fmt yuv420p`, `-crf 23`,
    `-preset veryfast` (batasi beban CPU VPS).
  - Skala turun ke "kelas 720p" tanpa upscale & menjaga rasio: sisi minor di-cap
    720 (landscape → tinggi 720; portrait → lebar 720), dimensi dibulatkan genap.
  - Audio: `aac`, `-b:a 128k`. Container: `-movflags +faststart`.
- Fungsi `whatsapp_args(src, dst) -> list[str]` (pure, mudah ditest) + runner
  `transcode_whatsapp(src, dst)` (cek `ffmpeg` ada, `subprocess.run(..., check=True)`).
- **Caveat jujur (diulang di UI):** WhatsApp tetap mengompres ulang video inline;
  ini membuat file "tahan kompresi", bukan menonaktifkan kompresi.

## 7. Android (app)

| Berkas | Perubahan |
|---|---|
| `ui/share/ShareReceiverActivity.kt` | Dialog pratinjau → **dialog opsi**: chip kualitas (Max/1080/720/480) + switch "Audio (MP3)" + switch "Optimalkan untuk WhatsApp (HD)" (nonaktif saat Audio aktif) + tombol Unduh/Batal. Hapus countdown 3 dtk & `AsyncImage` thumbnail. |
| `ui/share/PreviewViewModel.kt` | `resolve()` jadi ringan (sekadar dapat media base URL bertoken). State `Ready` membawa base URL + opsi terakhir (dari Prefs). `confirm(options)` merakit URL final `&quality=…&mode=…&wa=…`. |
| `domain/DownloadOptions.kt` (baru) | `DownloadOptions(quality, mode, whatsapp)` + `mediaUrl(base)` (tambah `&wa=1` hanya bila `whatsapp && mode=video`). |
| `work/DownloadController.kt` + `work/DownloadWorker.kt` | Teruskan flag `mode`. Mode audio → simpan ke `MediaStore.Audio` (`.mp3`, `audio/mpeg`, `Music/RDownloader`); video tetap `MediaStore.Video` (`Movies/RDownloader`). Temp file ekstensi mengikuti mode. (Worker tak peduli `wa` — file final selalu MP4.) |
| `data/Prefs.kt` (baru, SharedPreferences) | Simpan kualitas + mode + flag WhatsApp terakhir → pra-isi dialog ("ingat pilihan terakhir"). |
| `domain/Models.kt`, `data/net/Dtos.kt`, `data/DownloadRepository.kt` | Sesuaikan dengan respons `/extract` minimal (title/thumbnail null). Judul lokal untuk galeri/notifikasi dibangkitkan dari platform + timestamp (mis. "TikTok • 22 Jun 2026 14:30"). |

Tidak ada perubahan skema Room (kolom `title`/`thumbnail` sudah nullable).

## 8. Penanganan error & edge case

- **Cobalt error / tak terjangkau:** `/media` balas 422; worker retry (backoff 3×
  sudah ada) lalu `FAILED`.
- **TikTok slideshow** (Cobalt `status:"picker"` — kumpulan gambar + audio, bukan
  video tunggal): v1 balas error ramah "slideshow belum didukung". Pengecualian:
  jika `mode=audio`, ambil track audio dari picker. **Dukungan slideshow penuh =
  out of scope v1.**
- **Range/resume, cache TTL, lock per-URL:** dipertahankan.

## 9. Testing

- **Backend `pytest`:** `/health`; `/extract` mengembalikan media URL bertoken;
  `/media` dengan **mock Cobalt** → tunnel→file (200, Range), picker→422,
  error→422; validasi allowlist `quality`/`mode`/`wa` (400 untuk nilai ilegal);
  `transcode.whatsapp_args` memuat flag kunci (`libx264`, `yuv420p`, `aac`,
  `+faststart`).
- **Android unit:** perakitan URL final dari pilihan opsi (termasuk `&wa=1` hanya
  untuk video); pemetaan `mode`→target MediaStore (audio vs video); Prefs
  menyimpan/memuat pilihan terakhir.
- **Manual (akhir):** install APK di perangkat nyata → share TikTok/IG/FB → pilih
  kualitas & audio → verifikasi file tersimpan di galeri (video) / musik (audio),
  TikTok tanpa watermark, resume saat jaringan putus; aktifkan "Optimalkan untuk
  WhatsApp" lalu kirim ke WhatsApp → bandingkan ketajaman vs tanpa opsi.

## 10. Out of scope (v1)

Slideshow TikTok penuh; konten privat IG/FB (login/cookies); pemilih codec/format
video (tetap MP4 H.264); ekspos Cobalt ke publik; platform selain TikTok/IG/FB.

## 11. Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| RAM VPS terbatas (riwayat build OOM) | Yang berat adalah **build Android** (Gradle), bukan runtime; Cobalt (Node) ~150 MB. Pantau; tambah swap bila perlu. |
| Tag image Cobalt berubah/`latest` tak stabil | Pin tag stabil; verifikasi tag saat implementasi. |
| Cobalt rusak saat platform berubah (tanpa fallback) | Auto-update Cobalt; konsekuensi sadar dari pilihan "Cobalt saja". |
| Slideshow / format tak terduga | Tangani `picker` eksplisit; error ramah untuk yang tak didukung. |
| Beban CPU VPS saat transcode WhatsApp | `-preset veryfast` + cap 720p; hanya jalan saat opsi WA aktif; klip sosial pendek. Pantau pada video besar (hingga 500 MB). |
| Ekspektasi keliru "WhatsApp tak akan kompres" | UI menampilkan caveat jujur: opsi ini meminimalkan kerusakan, bukan menonaktifkan kompresi WhatsApp. |
