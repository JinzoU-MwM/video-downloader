#!/usr/bin/env bash
# Measure TikTok extraction reliability from the server.
set -uo pipefail
ENVF=/data/docker/video-dl-api/.env
KEY=$(grep '^API_KEY=' "$ENVF" | cut -d= -f2)
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"
N="${2:-8}"
ok=0
for i in $(seq 1 "$N"); do
  R=$(curl -s -X POST https://rdl-api.jni.my.id/extract \
    -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
    --data "{\"url\":\"$URL\"}")
  if echo "$R" | grep -q '"video"'; then
    ok=$((ok+1)); echo "[$i] OK"
  else
    echo "[$i] FAIL: $(echo "$R" | head -c 130)"
  fi
  sleep 1
done
echo "SUCCESS: $ok / $N"
