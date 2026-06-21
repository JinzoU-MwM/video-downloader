#!/usr/bin/env bash
# Verify yt-dlp can download the actual video bytes server-side (retry flaky extraction).
set -uo pipefail
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"
docker exec video-dl-api bash -c '
  U="'"$URL"'"
  for i in $(seq 1 10); do
    rm -f /tmp/t.*
    if yt-dlp --no-warnings -f "mp4/best" -o "/tmp/t.%(ext)s" "$U" >/tmp/yt.log 2>&1; then
      echo "DOWNLOADED on attempt $i"
      ls -lh /tmp/t.*
      head -c 8 /tmp/t.mp4 | xxd 2>/dev/null || true
      exit 0
    fi
    echo "attempt $i: extract failed, retrying"
  done
  echo "ALL ATTEMPTS FAILED"
  tail -4 /tmp/yt.log
'
