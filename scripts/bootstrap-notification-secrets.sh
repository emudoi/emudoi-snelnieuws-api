#!/usr/bin/env bash
#
# Bootstraps the Vault secrets the notification feature needs:
#   secret/snelnieuws/api          ← patch APNS_* + NOTIFICATIONS_API_KEY (preserves DB_*)
#   secret/snelnieuws/api/apns     ← put key_p8 (the .p8 file content)
#
# Idempotent. Safe to re-run for both first-time setup and key rotation —
# NOTIFICATIONS_API_KEY is preserved from Vault by default (the DAGs auth
# with it, so silently rotating it would break dispatch + broadcast).
#
# Usage:
#   VAULT_TOKEN=hvs.xxxx ./scripts/bootstrap-notification-secrets.sh
#
# Prompts interactively for:
#   - Vault token (unless VAULT_TOKEN is set)
#   - APNs Key ID (unless APNS_KEY_ID is set)
#   - Path to .p8 file (unless P8_PATH is set)
#
# Optional env overrides (each suppresses the matching prompt):
#   NOTIFICATIONS_API_KEY  override the API key — by default the script reads
#                          the existing value from Vault and reuses it; if
#                          Vault has no value, generates `openssl rand -hex 32`
#   APNS_KEY_ID, P8_PATH   key + private-key file (rotation rotates these)
#   APNS_TEAM_ID, APNS_BUNDLE_ID  stable across rotations; defaulted
#   VAULT_NS, VAULT_POD, VAULT_PATH

set -euo pipefail

# ── config (overrideable via env, otherwise prompted/derived) ─────────────────
VAULT_NS="${VAULT_NS:-vault}"
VAULT_POD="${VAULT_POD:-vault-0}"
VAULT_PATH="${VAULT_PATH:-secret/snelnieuws/api}"

# Stable across key rotations for the same app.
APNS_TEAM_ID="${APNS_TEAM_ID:-7PB86SYNNM}"
APNS_BUNDLE_ID="${APNS_BUNDLE_ID:-com.emudoi.snelnieuws}"

# ── input ─────────────────────────────────────────────────────────────────────
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  read -rsp "Vault token: " VAULT_TOKEN
  echo
fi
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_TOKEN is empty" >&2
  exit 1
fi

if [[ -z "${APNS_KEY_ID:-}" ]]; then
  read -rp "APNs Key ID (10 chars from developer.apple.com → Keys): " APNS_KEY_ID
fi
if [[ -z "${APNS_KEY_ID:-}" ]]; then
  echo "ERROR: APNS_KEY_ID is empty" >&2
  exit 1
fi

if [[ -z "${P8_PATH:-}" ]]; then
  read -rp "Path to .p8 file: " P8_PATH
  # Expand a leading ~ since `read` won't.
  P8_PATH="${P8_PATH/#\~/$HOME}"
fi
if [[ -z "${P8_PATH:-}" ]]; then
  echo "ERROR: P8_PATH is empty" >&2
  exit 1
fi

# Preserve NOTIFICATIONS_API_KEY from Vault by default. The DAGs sign their
# X-API-Key header with this value, so silently rotating it would break both
# /notifications/dispatch{,-sandbox} and /notifications/broadcast.
GENERATED_KEY=false
if [[ -z "${NOTIFICATIONS_API_KEY:-}" ]]; then
  set +e
  NOTIFICATIONS_API_KEY=$(kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
    vault kv get -field=NOTIFICATIONS_API_KEY "$VAULT_PATH" 2>/dev/null)
  set -e
  if [[ -z "${NOTIFICATIONS_API_KEY:-}" ]]; then
    echo "→ no existing NOTIFICATIONS_API_KEY in Vault — generating a new one"
    NOTIFICATIONS_API_KEY=$(openssl rand -hex 32)
    GENERATED_KEY=true
  else
    echo "→ preserving existing NOTIFICATIONS_API_KEY from Vault"
  fi
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
