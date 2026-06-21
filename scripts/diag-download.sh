#!/usr/bin/env bash
# Validate the full download path: extract -> direct CDN download + proxy fallback.
set -uo pipefail
ENVF=/data/docker/video-dl-api/.env
KEY=$(grep '^API_KEY=' "$ENVF" | cut -d= -f2)
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"

RESP=$(curl -s -X POST https://rdl-api.jni.my.id/extract \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  --data "{\"url\":\"$URL\"}")

echo "$RESP" | python3 -c 'import sys,json
d=json.load(sys.stdin)
print("title :", d.get("title"))
print("vurl  :", d["video"]["url"][:90])
print("token :", (d.get("proxy_token") or "")[:24], "...")'

VURL=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["video"]["url"])')
TOKEN=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["proxy_token"])')

echo "=== direct CDN download (first 200KB) ==="
curl -s -o /tmp/d.mp4 -r 0-204800 -w "http=%{http_code} bytes=%{size_download}\n" "$VURL" || echo "direct failed"
echo "=== proxy download (first 200KB) ==="
curl -s -o /tmp/p.mp4 -r 0-204800 -w "http=%{http_code} bytes=%{size_download}\n" "https://rdl-api.jni.my.id/proxy?token=$TOKEN" || echo "proxy failed"
echo "=== file check ==="
file /tmp/d.mp4 /tmp/p.mp4 2>/dev/null || ls -l /tmp/d.mp4 /tmp/p.mp4
