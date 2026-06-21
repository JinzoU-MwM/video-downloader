#!/usr/bin/env bash
# Test whether a real Chrome UA / API extractor-args fixes TikTok extraction.
set -uo pipefail
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

echo "### TEST 1: Chrome User-Agent"
docker exec video-dl-api yt-dlp --skip-download --no-warnings \
  --user-agent "$UA" --print "OK %(title)s" "$URL" 2>&1 | tail -6

echo "### TEST 2: force mobile API (extractor-args)"
docker exec video-dl-api yt-dlp --skip-download --no-warnings \
  --extractor-args "tiktok:api_hostname=api22-normal-c-useast2a.tiktokv.com" \
  --print "OK %(title)s" "$URL" 2>&1 | tail -6

echo "### TEST 3: Chrome UA + Referer header"
docker exec video-dl-api yt-dlp --skip-download --no-warnings \
  --user-agent "$UA" --add-header "Referer:https://www.tiktok.com/" \
  --print "OK %(title)s :: %(url).60s" "$URL" 2>&1 | tail -6
