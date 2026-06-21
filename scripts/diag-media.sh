#!/usr/bin/env bash
# Verify the /media endpoint downloads + serves real video bytes.
set -uo pipefail
ENVF=/data/docker/video-dl-api/.env
KEY=$(grep '^API_KEY=' "$ENVF" | cut -d= -f2)
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"

RESP=$(curl -s -X POST https://rdl-api.jni.my.id/extract \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' --data "{\"url\":\"$URL\"}")
echo "$RESP" | python3 -c 'import sys,json
d=json.load(sys.stdin)
print("title    :", d.get("title"))
print("media_url:", d["video"]["url"][:85])'

MEDIA=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["video"]["url"])')
echo "=== GET /media (range 0-300KB) ==="
curl -s -o /tmp/m.mp4 -r 0-307200 \
  -w "http=%{http_code} bytes=%{size_download} ctype=%{content_type}\n" "$MEDIA"
echo "magic bytes (expect ftyp for mp4):"
head -c 12 /tmp/m.mp4 | od -c | head -1
ls -lh /tmp/m.mp4
