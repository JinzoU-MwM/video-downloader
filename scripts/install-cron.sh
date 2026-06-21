#!/usr/bin/env bash
# Install the daily yt-dlp auto-update cron (idempotent).
set -uo pipefail
LINE='30 5 * * * /data/docker/video-dl-api/ytdlp-autoupdate.sh >> /data/docker/video-dl-api/autoupdate.log 2>&1'
F=$(mktemp)
crontab -l 2>/dev/null | grep -v 'ytdlp-autoupdate' > "$F" || true
echo "$LINE" >> "$F"
crontab "$F"
rm -f "$F"
echo "INSTALLED:"
crontab -l | grep ytdlp-autoupdate
