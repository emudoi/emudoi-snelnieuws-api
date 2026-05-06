#!/usr/bin/env bash
#
# Bootstraps the Vault secrets the notification feature needs:
#   secret/snelnieuws/api          ← patch APNS_* + NOTIFICATIONS_API_KEY (preserves DB_*)
#   secret/snelnieuws/api/apns     ← put key_p8 (the .p8 file content)
#
# Idempotent. Re-running rotates NOTIFICATIONS_API_KEY (unless you pass it
# explicitly) and updates the .p8.
#
# Usage:
#   VAULT_TOKEN=hvs.xxxx ./scripts/bootstrap-notification-secrets.sh
#
# Optional env overrides:
#   NOTIFICATIONS_API_KEY  pre-supplied dispatch shared secret (default: openssl rand -hex 32)
#   P8_PATH                path to AuthKey_*.p8 (default: ../emudoi-snelnieuws-ios/AuthKey_K9MVS7XY7S.p8)
#   APNS_KEY_ID, APNS_TEAM_ID, APNS_BUNDLE_ID, APNS_SANDBOX
#   VAULT_NS, VAULT_POD, VAULT_PATH

set -euo pipefail

# ── config (overrideable via env) ─────────────────────────────────────────────
VAULT_NS="${VAULT_NS:-vault}"
VAULT_POD="${VAULT_POD:-vault-0}"
VAULT_PATH="${VAULT_PATH:-secret/snelnieuws/api}"

P8_PATH="${P8_PATH:-/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-ios/AuthKey_K9MVS7XY7S.p8}"
APNS_KEY_ID="${APNS_KEY_ID:-K9MVS7XY7S}"
APNS_TEAM_ID="${APNS_TEAM_ID:-7PB86SYNNM}"
APNS_BUNDLE_ID="${APNS_BUNDLE_ID:-com.emudoi.snelnieuws}"
APNS_SANDBOX="${APNS_SANDBOX:-false}"

# ── input ─────────────────────────────────────────────────────────────────────
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  read -rsp "Vault token: " VAULT_TOKEN
  echo
fi
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_TOKEN is empty" >&2
  exit 1
fi

GENERATED_KEY=false
if [[ -z "${NOTIFICATIONS_API_KEY:-}" ]]; then
  NOTIFICATIONS_API_KEY=$(openssl rand -hex 32)
  GENERATED_KEY=true
fi

# ── prereqs ───────────────────────────────────────────────────────────────────
if [[ ! -f "$P8_PATH" ]]; then
  echo "ERROR: .p8 file not found at $P8_PATH" >&2
  exit 1
fi
if ! kubectl -n "$VAULT_NS" get pod "$VAULT_POD" >/dev/null 2>&1; then
  echo "ERROR: cannot reach pod $VAULT_NS/$VAULT_POD (check kubectl context)" >&2
  exit 1
fi

# ── copy .p8 into the pod ─────────────────────────────────────────────────────
echo "→ copying .p8 to $VAULT_NS/$VAULT_POD:/tmp/apns.p8"
kubectl -n "$VAULT_NS" cp "$P8_PATH" "$VAULT_POD:/tmp/apns.p8"

cleanup() {
  kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- rm -f /tmp/apns.p8 >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ── patch app config keys (preserves existing DB_*) ───────────────────────────
echo "→ patching $VAULT_PATH"
kubectl -n "$VAULT_NS" exec -i "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv patch "$VAULT_PATH" \
    APNS_KEY_ID="$APNS_KEY_ID" \
    APNS_TEAM_ID="$APNS_TEAM_ID" \
    APNS_BUNDLE_ID="$APNS_BUNDLE_ID" \
    APNS_SANDBOX="$APNS_SANDBOX" \
    NOTIFICATIONS_API_KEY="$NOTIFICATIONS_API_KEY" \
    >/dev/null

# ── put .p8 at its own path (separate so other writes don't clobber it) ───────
echo "→ writing $VAULT_PATH/apns key_p8"
kubectl -n "$VAULT_NS" exec -i "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv put "$VAULT_PATH/apns" key_p8=@/tmp/apns.p8 \
    >/dev/null

# ── verify ────────────────────────────────────────────────────────────────────
echo "→ verifying"
kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv get -format=json "$VAULT_PATH" \
  | grep -E '"APNS_|"NOTIFICATIONS_API_KEY"' | sed 's/.*"\([^"]*\)".*/  \1/'

kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv get -field=key_p8 "$VAULT_PATH/apns" \
  | head -1 | grep -q "BEGIN PRIVATE KEY" \
  && echo "  $VAULT_PATH/apns:key_p8 — OK" \
  || { echo "  ERROR: $VAULT_PATH/apns:key_p8 doesn't look like a PEM key" >&2; exit 1; }

echo
echo "✓ Done."
if [[ "$GENERATED_KEY" == "true" ]]; then
  echo
  echo "── NOTIFICATIONS_API_KEY ──────────────────────────────────────────────"
  echo "$NOTIFICATIONS_API_KEY"
  echo "──────────────────────────────────────────────────────────────────────"
  echo "Set the same value as Airflow Variable: snelnieuws_notifications_api_key"
fi
