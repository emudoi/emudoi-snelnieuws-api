#!/usr/bin/env bash
#
# Bootstraps the Vault secrets the Firebase ID-token verifier needs:
#   secret/snelnieuws/api          ← patch FIREBASE_PROJECT_ID + FIREBASE_SERVICE_ACCOUNT_PATH
#                                    (preserves DB_*, APNS_*, NOTIFICATIONS_API_KEY)
#   secret/snelnieuws/api/firebase ← put service_account_json (the .json file content)
#
# Idempotent. Re-running just overwrites the same keys.
#
# After running this you MUST also:
#   1. Make sure k8s/deployment.yaml mounts the JSON file at
#      /vault/secrets/firebase-service-account.json (the
#      vault.hashicorp.com/agent-inject-secret-firebase-service-account.json
#      annotation block).
#   2. Restart the pod so Vault Agent re-renders the new file:
#        kubectl -n emudoi-snelnieuws-api rollout restart deploy/snelnieuws-api
#
# Usage (just run it from the repo root — defaults match the project):
#   VAULT_TOKEN=hvs.xxxx ./scripts/bootstrap-firebase-secrets.sh
#
# Defaults read from this repo:
#   FIREBASE_PROJECT_ID         snelnieuws-6d2ae   (PROJECT_ID in iOS GoogleService-Info.plist)
#   SERVICE_ACCOUNT_JSON_PATH   <repo-root>/snelnieuws-*-firebase-adminsdk-*.json
#                               (the file you downloaded from Firebase Console; the
#                               repo's .gitignore covers this glob so it never commits)
#
# Override any of FIREBASE_PROJECT_ID / SERVICE_ACCOUNT_JSON_PATH / VAULT_NS /
# VAULT_POD / VAULT_PATH via env if you need to.

set -euo pipefail

# ── config (overrideable via env) ─────────────────────────────────────────────
VAULT_NS="${VAULT_NS:-vault}"
VAULT_POD="${VAULT_POD:-vault-0}"
VAULT_PATH="${VAULT_PATH:-secret/snelnieuws/api}"

# Project ID for this app. Lifted from iOS GoogleService-Info.plist.
FIREBASE_PROJECT_ID="${FIREBASE_PROJECT_ID:-snelnieuws-6d2ae}"

# Where the script lives, so we can resolve the default JSON path relative
# to the repo root regardless of $PWD.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Default to the file in the repo root matching the Firebase Console download
# naming. Glob-resolved so a freshly-downloaded file is picked up automatically.
if [[ -z "${SERVICE_ACCOUNT_JSON_PATH:-}" ]]; then
  shopt -s nullglob
  candidates=("$REPO_ROOT"/*-firebase-adminsdk-*.json)
  shopt -u nullglob
  if [[ ${#candidates[@]} -eq 1 ]]; then
    SERVICE_ACCOUNT_JSON_PATH="${candidates[0]}"
  elif [[ ${#candidates[@]} -gt 1 ]]; then
    echo "ERROR: multiple firebase-adminsdk JSON files in $REPO_ROOT — set SERVICE_ACCOUNT_JSON_PATH explicitly:" >&2
    printf "  %s\n" "${candidates[@]}" >&2
    exit 1
  fi
fi

# Path inside the running app pod where Vault Agent will render the JSON.
# Must match the agent-inject annotation in k8s/deployment.yaml AND the
# FIREBASE_SERVICE_ACCOUNT_PATH env var the app reads.
SERVICE_ACCOUNT_MOUNT_PATH="/vault/secrets/firebase-service-account.json"

# ── input ─────────────────────────────────────────────────────────────────────
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  read -rsp "Vault token: " VAULT_TOKEN
  echo
fi
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_TOKEN is empty" >&2
  exit 1
fi

if [[ -z "${SERVICE_ACCOUNT_JSON_PATH:-}" ]]; then
  echo "ERROR: no firebase-adminsdk JSON found in $REPO_ROOT — drop the file" >&2
  echo "       downloaded from Firebase Console there, or set SERVICE_ACCOUNT_JSON_PATH." >&2
  exit 1
fi

# ── prereqs ───────────────────────────────────────────────────────────────────
if [[ ! -f "$SERVICE_ACCOUNT_JSON_PATH" ]]; then
  echo "ERROR: service-account JSON not found at $SERVICE_ACCOUNT_JSON_PATH" >&2
  exit 1
fi
# Sanity: must be valid JSON containing a "project_id" field, and that field
# must match the FIREBASE_PROJECT_ID we're about to write — catches the easy
# "wrong file for this project" mistake.
if ! python3 -c "import json,sys; d=json.load(open(sys.argv[1])); assert d.get('project_id'), 'no project_id'; assert d['project_id']==sys.argv[2], f'project_id mismatch: file says {d[\"project_id\"]}, expected {sys.argv[2]}'" \
     "$SERVICE_ACCOUNT_JSON_PATH" "$FIREBASE_PROJECT_ID" 2>/dev/null; then
  echo "ERROR: $SERVICE_ACCOUNT_JSON_PATH is not a valid Firebase service-account JSON" >&2
  echo "       for project '$FIREBASE_PROJECT_ID'." >&2
  exit 1
fi
if ! kubectl -n "$VAULT_NS" get pod "$VAULT_POD" >/dev/null 2>&1; then
  echo "ERROR: cannot reach pod $VAULT_NS/$VAULT_POD (check kubectl context)" >&2
  exit 1
fi

# ── copy JSON into the pod ────────────────────────────────────────────────────
echo "→ copying service-account JSON to $VAULT_NS/$VAULT_POD:/tmp/firebase-sa.json"
kubectl -n "$VAULT_NS" cp "$SERVICE_ACCOUNT_JSON_PATH" "$VAULT_POD:/tmp/firebase-sa.json"

cleanup() {
  kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- rm -f /tmp/firebase-sa.json >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ── patch app config keys (preserves existing DB_*, APNS_*, etc.) ─────────────
echo "→ patching $VAULT_PATH"
kubectl -n "$VAULT_NS" exec -i "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv patch "$VAULT_PATH" \
    FIREBASE_PROJECT_ID="$FIREBASE_PROJECT_ID" \
    FIREBASE_SERVICE_ACCOUNT_PATH="$SERVICE_ACCOUNT_MOUNT_PATH" \
    >/dev/null

# ── put JSON at its own path (separate so other writes don't clobber it) ──────
echo "→ writing $VAULT_PATH/firebase service_account_json"
kubectl -n "$VAULT_NS" exec -i "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv put "$VAULT_PATH/firebase" service_account_json=@/tmp/firebase-sa.json \
    >/dev/null

# ── verify ────────────────────────────────────────────────────────────────────
echo "→ verifying"
kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv get -format=json "$VAULT_PATH" \
  | grep -E '"FIREBASE_' | sed 's/.*"\([^"]*\)".*/  \1/'

kubectl -n "$VAULT_NS" exec "$VAULT_POD" -- env VAULT_TOKEN="$VAULT_TOKEN" \
  vault kv get -field=service_account_json "$VAULT_PATH/firebase" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'  $VAULT_PATH/firebase:service_account_json — OK (project_id={d[\"project_id\"]}, client_email={d[\"client_email\"]})')" \
  || { echo "  ERROR: $VAULT_PATH/firebase:service_account_json is not valid JSON" >&2; exit 1; }

echo
echo "✓ Done."
echo
echo "Next steps:"
echo "  1. Confirm k8s/deployment.yaml has the firebase agent-inject annotation."
echo "  2. Restart the pod to pick up the new file:"
echo "       kubectl -n emudoi-snelnieuws-api rollout restart deploy/snelnieuws-api"
