#!/usr/bin/env bash
# Safely restart the cloudflared tunnel to apply the new ingress rule.
# Designed to run fully DETACHED (via setsid) so it survives the SSH drop that
# happens when the tunnel briefly goes down (SSH to this host rides the tunnel).
# Auto-rolls-back to the pre-edit config if the tunnel fails to reconnect.
set -uo pipefail

CFD="$HOME/.local/bin/cloudflared"
CFG="$HOME/.cloudflared/config.yml"
LOG="$HOME/.cloudflared/jni-server.log"
STATUS="/tmp/cf-restart-status.txt"
TUNNEL="jni-server"

: > "$STATUS"
log() { echo "[$(date -u +%H:%M:%S)] $*" >> "$STATUS"; }

start_cf() {
  setsid nohup "$CFD" tunnel --config "$CFG" run "$TUNNEL" >> "$LOG" 2>&1 &
}

verify() {
  for _ in $(seq 1 30); do
    if pgrep -f "cloudflared tunnel .* run $TUNNEL" >/dev/null; then
      if tail -n 60 "$LOG" | grep -q "Registered tunnel connection"; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

OLDPIDS=$(pgrep -f "cloudflared tunnel .* run $TUNNEL" || true)
log "old pids: ${OLDPIDS:-none}"
[ -n "$OLDPIDS" ] && kill $OLDPIDS 2>/dev/null || true
sleep 2

echo "=== restart $(date -u) ===" >> "$LOG"
start_cf
if verify; then
  log "OK: tunnel reconnected with NEW config (rdl-api.jni.my.id live)"
  exit 0
fi

log "FAIL: new config did not reconnect; rolling back"
LATEST_BAK=$(ls -t "$CFG".bak.rdl-* 2>/dev/null | head -1 || true)
if [ -n "$LATEST_BAK" ]; then
  cp "$LATEST_BAK" "$CFG"
  log "restored backup: $LATEST_BAK"
fi
pkill -f "cloudflared tunnel .* run $TUNNEL" 2>/dev/null || true
sleep 2
echo "=== rollback restart $(date -u) ===" >> "$LOG"
start_cf
if verify; then
  log "ROLLED BACK: tunnel reconnected with OLD config (services restored)"
else
  log "CRITICAL: tunnel did NOT reconnect even after rollback — needs console access"
fi
