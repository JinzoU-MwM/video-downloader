#!/usr/bin/env bash
# Diagnose why TikTok extraction fails from this server.
set -uo pipefail
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"

echo "=== raw page probe (server IP) ==="
curl -s -A "$UA" -L -o /tmp/tt.html -w "http=%{http_code} size=%{size_download} final=%{url_effective}\n" "$URL"
echo "rehydration_marker_count:"; grep -c "UNIVERSAL_DATA_FOR_REHYDRATION" /tmp/tt.html || true
echo "captcha_or_verify_hints:"; grep -ciE "captcha|verify|security check|slardar|tiktok-verify|blocked|forbidden" /tmp/tt.html || true
echo "title:"; grep -oiE "<title>[^<]*</title>" /tmp/tt.html | head -1 || true
echo "first_200_bytes:"; head -c 200 /tmp/tt.html; echo

echo "=== outbound IP of server ==="
curl -s -A "$UA" https://api.ipify.org; echo

echo "=== yt-dlp verbose tail ==="
docker exec video-dl-api yt-dlp -v --skip-download --no-warnings "$URL" 2>&1 | tail -25
