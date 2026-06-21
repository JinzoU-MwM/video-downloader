#!/usr/bin/env bash
# Keep the extractor fresh: pull latest yt-dlp master into the backend container
# and restart it. TikTok/IG/FB change their anti-bot often, so run this daily.
# Install (on jni-server):
#   (crontab -l 2>/dev/null; echo "30 5 * * * /data/docker/video-dl-api/ytdlp-autoupdate.sh >> /data/docker/video-dl-api/autoupdate.log 2>&1") | crontab -
set -uo pipefail
TARBALL="yt-dlp[default] @ https://github.com/yt-dlp/yt-dlp/archive/refs/heads/master.tar.gz"
echo "[$(date -u +%FT%TZ)] updating yt-dlp..."
if docker exec video-dl-api pip install -q -U "$TARBALL"; then
  docker restart video-dl-api >/dev/null
  echo "[$(date -u +%FT%TZ)] yt-dlp updated, container restarted"
else
  echo "[$(date -u +%FT%TZ)] update failed; leaving current version running"
fi
