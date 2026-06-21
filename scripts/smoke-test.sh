#!/usr/bin/env bash
# End-to-end smoke test for the extraction backend (local + public).
set -uo pipefail
ENVF=/data/docker/video-dl-api/.env
KEY=$(grep '^API_KEY=' "$ENVF" | cut -d= -f2)

echo "HEALTH local:"
curl -s http://127.0.0.1:8091/health; echo
echo "HEALTH public:"
curl -s https://rdl-api.jni.my.id/health; echo
echo "NOKEY (expect 401):"
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8091/extract \
  -H 'Content-Type: application/json' --data '{"url":"https://tiktok.com/x"}'
echo "BADURL (expect 422):"
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8091/extract \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  --data '{"url":"https://youtube.com/x"}'

URL="${1:-https://www.tiktok.com/@scout2015/video/6718335390845095173}"
echo "EXTRACT public ($URL):"
curl -s -X POST https://rdl-api.jni.my.id/extract \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  --data "{\"url\":\"$URL\"}" | head -c 700
echo
