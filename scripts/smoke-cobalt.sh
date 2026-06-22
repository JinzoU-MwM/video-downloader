#!/usr/bin/env bash
# End-to-end smoke test for the Cobalt-backed downloader API.
#
# Run on a host with working media-CDN egress (the VPS / a residential IP) after
# `docker compose up -d` in backend/. A datacenter sandbox often can't fetch
# TikTok/YouTube media (anti-bot), so this is the place to confirm real downloads.
#
# Usage:
#   API=http://127.0.0.1:8091 KEY=dev-key ./scripts/smoke-cobalt.sh "https://www.tiktok.com/@user/video/123"
set -euo pipefail
API="${API:-http://127.0.0.1:8091}"
KEY="${KEY:-dev-key}"
URL="${1:?usage: smoke-cobalt.sh <video-url>}"

echo "== /health =="
curl -fsS "$API/health"; echo

echo "== /extract =="
RESP=$(curl -fsS -X POST "$API/extract" -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"url\":\"$URL\"}")
echo "$RESP"
MEDIA=$(printf '%s' "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["video"]["url"])')

dl () { # name  query  outfile
  echo "== /media ($1) =="
  code=$(curl -s -o "/tmp/smoke_$3" -w '%{http_code} | %{content_type} | %{size_download} bytes' "$MEDIA&$2")
  echo "  -> $code  (saved /tmp/smoke_$3)"
}
dl video    "quality=720&mode=video"      v.mp4
dl whatsapp "quality=720&mode=video&wa=1" wa.mp4
dl audio    "quality=720&mode=audio"      a.mp3
echo "done — inspect /tmp/smoke_v.mp4, /tmp/smoke_wa.mp4, /tmp/smoke_a.mp3"
