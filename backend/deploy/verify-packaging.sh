#!/usr/bin/env bash
#
# Inspects a built image before it is allowed anywhere near a server.
#
#   bash deploy/verify-packaging.sh lumora:test
#
# Checks: the image runs as a non-root user, and no secret-shaped value was baked
# into its layers, environment, labels, or build commands.
set -euo pipefail

IMAGE="${1:-}"
[[ -n "$IMAGE" ]] || { printf 'usage: %s <image>\n' "$0" >&2; exit 2; }

pass() { printf '\033[1;32mok\033[0m   %s\n' "$*"; }
fail() { printf '\033[1;31mfail\033[0m %s\n' "$*" >&2; failures=$((failures + 1)); }
failures=0

# --- image runs as a non-root user -----------------------------------------
user=$(docker image inspect --format '{{.Config.User}}' "$IMAGE")
if [[ -z "$user" || "$user" == "root" || "$user" == "0" ]]; then
  fail "image user is '${user:-root}'; expected a non-root user"
else
  pass "image user is '$user'"
fi

# --- no secret-shaped values in metadata ------------------------------------
# Build args, ENV lines and labels all survive into the image and are readable by
# anyone who can pull it. Env *names* are expected; values are what must not appear.
SECRET_PATTERN='(sk-[A-Za-z0-9_-]{20,}|(PASSWORD|AUTH_CODE|SECRET|TOKEN|AES_KEY|ADMIN_KEY)=[^$[:space:]"'"'"']{6,})'

metadata=$(
  docker image inspect --format '{{json .Config.Env}} {{json .Config.Labels}}' "$IMAGE"
  docker history --no-trunc --format '{{.CreatedBy}}' "$IMAGE"
)

if matches=$(printf '%s' "$metadata" | grep -oE "$SECRET_PATTERN" || true); [[ -n "$matches" ]]; then
  # Print the matched *names* only, never the full match.
  fail "secret-shaped values in image metadata: $(printf '%s' "$matches" | cut -d= -f1 | sort -u | tr '\n' ' ')"
else
  pass "no secret-shaped values in env, labels, or build history"
fi

# --- the env file must not have been copied in -------------------------------
if docker history --no-trunc --format '{{.CreatedBy}}' "$IMAGE" | grep -qE 'COPY .*\.env|ADD .*\.env'; then
  fail ".env appears to be copied into the image"
else
  pass "no .env copied into the image"
fi

# --- compose and shell syntax ------------------------------------------------
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if docker compose --project-directory "$here" --env-file "$here/.env.example" config --quiet 2>/dev/null; then
  pass "compose.yaml is valid"
else
  fail "compose.yaml failed validation"
fi

for script in "$here"/deploy/*.sh "$here"/deploy/dev2/*.sh; do
  [[ -f "$script" ]] || continue
  if bash -n "$script"; then
    pass "$(basename "$script") parses"
  else
    fail "$(basename "$script") has a syntax error"
  fi
done

if (( failures )); then
  printf '\n%d check(s) failed.\n' "$failures" >&2
  exit 1
fi
printf '\nAll packaging checks passed.\n'
