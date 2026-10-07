#!/usr/bin/env bash
# Turns a GCS service-account key file into the one-line value for GCP_CREDENTIALS_JSON.
#
# The value is base64 of the key JSON: plain letters/digits, so env-var editors on managed
# hosts (DigitalOcean App Platform etc.) cannot choke on the braces, quotes and \n escapes that
# the raw JSON is full of. GcsFileStorageService accepts it as-is.
#
# Usage:
#   ./scripts/gcs-key-to-env.sh path/to/key.json            # copies to the clipboard (macOS)
#   ./scripts/gcs-key-to-env.sh path/to/key.json --stdout   # prints it (pipe it, don't share it)
#
# The key is never echoed unless you pass --stdout. Treat the output like the key itself.
set -euo pipefail

KEY_FILE="${1:-}"
MODE="${2:-clipboard}"

if [[ -z "$KEY_FILE" || ! -f "$KEY_FILE" ]]; then
  echo "usage: $0 <service-account-key.json> [--stdout]" >&2
  exit 2
fi

# Validate before encoding so a wrong file fails here, not as a boot error on the server.
python3 - "$KEY_FILE" <<'PY' || { echo "error: not a service-account key JSON" >&2; exit 1; }
import json, sys
d = json.load(open(sys.argv[1]))
missing = [k for k in ("type", "private_key", "client_email") if k not in d]
if d.get("type") != "service_account" or missing:
    sys.exit(1)
PY

ENCODED="$(python3 -c "import base64,sys;sys.stdout.write(base64.b64encode(open(sys.argv[1],'rb').read()).decode())" "$KEY_FILE")"

if [[ "$MODE" == "--stdout" ]]; then
  printf '%s' "$ENCODED"
elif command -v pbcopy >/dev/null 2>&1; then
  printf '%s' "$ENCODED" | pbcopy
  echo "Copied ${#ENCODED} characters to the clipboard. Paste it as GCP_CREDENTIALS_JSON (mark it Encrypted)."
else
  echo "pbcopy not found; re-run with --stdout and pipe it where you need it." >&2
  exit 1
fi
