#!/usr/bin/env bash
# Adds a Cloudflare Tunnel ingress rule for the backend, validates it.
# NON-DISRUPTIVE: only edits the config file + validates. Does NOT restart cloudflared.
set -euo pipefail

CFG="$HOME/.cloudflared/config.yml"
CFD="$HOME/.local/bin/cloudflared"
HOST="rdl-api.jni.my.id"
PORT="8091"

[ -f "$CFG" ] || { echo "config not found: $CFG"; exit 1; }

cp "$CFG" "$CFG.bak.rdl-$(date +%s)"

if grep -q "$HOST" "$CFG"; then
  echo "ingress rule for $HOST already present"
else
  python3 - "$CFG" "$HOST" "$PORT" <<'PY'
import sys
cfg, host, port = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(cfg).read().splitlines()
out, inserted = [], False
for ln in lines:
    if (not inserted) and ln.strip().startswith("- service: http_status:404"):
        indent = ln[:len(ln) - len(ln.lstrip())]
        out.append(f"{indent}- hostname: {host}")
        out.append(f"{indent}  service: http://127.0.0.1:{port}")
        inserted = True
    out.append(ln)
if not inserted:
    raise SystemExit("catch-all 404 rule not found; aborting to avoid corrupting config")
open(cfg, "w").write("\n".join(out) + "\n")
print("inserted ingress rule")
PY
fi

echo "=== validating ingress ==="
"$CFD" tunnel ingress validate

echo "=== config tail ==="
tail -n 8 "$CFG"
