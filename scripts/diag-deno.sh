#!/usr/bin/env bash
# Test hypothesis: yt-dlp needs a JS runtime (Deno) to solve TikTok's JS challenge.
set -uo pipefail
URL="${1:-https://www.tiktok.com/@tiktok/video/7651469280621514004}"

echo "=== install deno into running container (temp) ==="
docker exec video-dl-api bash -c 'command -v deno >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl unzip ca-certificates >/dev/null 2>&1; curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh >/dev/null 2>&1; }; /usr/local/bin/deno --version 2>/dev/null | head -1 || echo DENO_INSTALL_FAILED'

echo "=== yt-dlp retry with deno on PATH ==="
docker exec video-dl-api bash -c "export PATH=/usr/local/bin:\$PATH; yt-dlp --skip-download --no-warnings --print '%(title)s :: %(url)s' '$URL' 2>&1 | tail -20"
