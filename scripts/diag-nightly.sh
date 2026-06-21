#!/usr/bin/env bash
# Test hypothesis: yt-dlp master (bleeding edge) fixes the TikTok extractor.
set -uo pipefail
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"

echo "=== current ==="
docker exec video-dl-api yt-dlp --version 2>&1 | tail -1
echo "=== install master ==="
docker exec video-dl-api pip install -q -U "yt-dlp[default] @ https://github.com/yt-dlp/yt-dlp/archive/refs/heads/master.tar.gz" 2>&1 | tail -2
docker exec video-dl-api yt-dlp --version 2>&1 | tail -1
echo "=== retry (default flow) ==="
docker exec video-dl-api yt-dlp --skip-download --no-warnings --print "OK %(title)s" "$URL" 2>&1 | tail -10
