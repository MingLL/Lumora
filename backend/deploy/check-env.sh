#!/usr/bin/env bash
#
# Validates that an env file defines every variable the application requires.
#
#   bash deploy/check-env.sh .env
#
# Reports names only. Values are never printed, compared against, or echoed --
# the whole point is that this can run on a production host without leaking.
set -euo pipefail

ENV_FILE="${1:-.env}"

REQUIRED=(
  WECHAT_APP_ID
  WECHAT_ORIGINAL_ID
  WECHAT_TOKEN
  WECHAT_AES_KEY
  MYSQL_HOST
  MYSQL_DATABASE
  MYSQL_USERNAME
  MYSQL_PASSWORD
  MAIL_USERNAME
  MAIL_AUTH_CODE
  REPORT_RECIPIENTS
  REPORT_ADMIN_KEY
)

fail() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || fail "$ENV_FILE not found"

# Refuse a world-readable env file. It holds the AES key and the mail auth code.
# Templates are exempt: .env.example is committed and holds no values.
if [[ "$ENV_FILE" != *.example ]]; then
  perms=$(stat -f '%Lp' "$ENV_FILE" 2>/dev/null || stat -c '%a' "$ENV_FILE")
  if [[ "${perms: -1}" != "0" ]]; then
    fail "$ENV_FILE is readable by others (mode $perms); run: chmod 600 $ENV_FILE"
  fi
fi

missing=()
for name in "${REQUIRED[@]}"; do
  # Match assignment lines only, and treat an empty value as missing. Never
  # capture the value itself into a variable that could end up in a trace.
  if ! grep -qE "^[[:space:]]*${name}=[^[:space:]]" "$ENV_FILE"; then
    missing+=("$name")
  fi
done

if (( ${#missing[@]} )); then
  printf 'missing or empty in %s:\n' "$ENV_FILE" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

printf 'ok: %s defines all %d required variables\n' "$ENV_FILE" "${#REQUIRED[@]}"
