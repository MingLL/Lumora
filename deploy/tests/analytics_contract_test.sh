#!/usr/bin/env bash
# Local-only contract checks: no servers or clusters are contacted.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export CALLS="$WORK/calls"
mkdir -p "$WORK/repo/deploy/k8s" "$WORK/repo/analytics/dist" "$WORK/bin"
cp "$ROOT/deploy/render-analytics.py" "$WORK/repo/deploy/"
cp "$ROOT/deploy/deploy-analytics.sh" "$WORK/repo/deploy/"
cp "$ROOT/deploy/k8s/lumora-analytics.yaml" "$WORK/repo/deploy/k8s/"
printf 'fixture' > "$WORK/repo/analytics/dist/index.html"
export RENDERED="$WORK/rendered"
cat > "$WORK/bin/ssh" <<'MOCK'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >> "$CALLS"
cat >> "$RENDERED"
MOCK
cat > "$WORK/bin/rsync" <<'MOCK'
#!/usr/bin/env bash
printf 'rsync %s\n' "$*" >> "$CALLS"
MOCK
cat > "$WORK/bin/curl" <<'MOCK'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$CALLS"
case "$*" in *api/analytics/summary*) printf 401 ;; *) printf 200 ;; esac
MOCK
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"
SCRIPT="$WORK/repo/deploy/deploy-analytics.sh"
for ips in '' 'not-an-ip' '0.0.0.0/0' '::/0' '203.0.113.1/24' '203.0.113.1,'; do
  if ANALYTICS_ALLOWED_IPS="$ips" bash "$SCRIPT" --render-only >/dev/null 2>&1; then
    printf 'FAIL: accepted missing or invalid allowlist %s\n' "$ips"; exit 1
  fi
  test ! -e "$CALLS"
done
printf '203.0.113.20/32\n' > "$WORK/repo/deploy/analytics-allowed-ips.local"
(unset ANALYTICS_ALLOWED_IPS; bash "$SCRIPT" --render-only) > "$WORK/local-preview"
grep -q '203.0.113.20/32' "$WORK/local-preview"
export ANALYTICS_ALLOWED_IPS='203.0.113.10,2001:db8::1,198.51.100.0/24' 
ANALYTICS_HOST=stats.example.com bash "$SCRIPT" --render-only > "$WORK/preview"
grep -q '203.0.113.10/32' "$WORK/preview"
grep -q '2001:db8::1/128' "$WORK/preview"
grep -q '198.51.100.0/24' "$WORK/preview"
test "$(grep -c '        - name: lumora-analytics-allowlist' "$WORK/preview")" -eq 2
! grep -q 'ipStrategy:' "$WORK/preview"
test ! -e "$CALLS"
grep -q 'Host(`stats.example.com`)' "$WORK/preview"
! grep -q '__ANALYTICS_HOST__\|__MANIFEST_HASH__\|__ANALYTICS_ALLOWED_IPS__' "$WORK/preview"
for host in lumora.love www.lumora.love 'x`)' 'https://stats.example.com' 'bad..example.com'; do
  if ANALYTICS_HOST="$host" bash "$SCRIPT" --render-only > /dev/null 2>&1; then
    printf 'FAIL: accepted invalid or main-site hostname %s\n' "$host"; exit 1
  fi
done
ANALYTICS_HOST=stats.example.com bash "$SCRIPT" --skip-build --allow-behind > /dev/null
grep -q 'rsync -az --delete analytics/dist/ dev1:/opt/lumora/analytics/' "$CALLS"
grep -q 'rollout status daemonset/lumora-analytics' "$CALLS"
! grep -q '/opt/lumora/site\|daemonset/lumora-web\|lumora-ingress.yaml' "$CALLS"
grep -q 'Host(`stats.example.com`)' "$RENDERED"
! grep -q '/api/analytics' "$ROOT/deploy/k8s/lumora-ingress.yaml"
test ! -e "$ROOT/frontend/src/pages/admin/analytics.astro"
test ! -e "$ROOT/frontend/src/lib/analytics.ts"
test ! -e "$ROOT/frontend/public/maps/world-land.svg"
printf 'PASS: isolated deployment, rendered hostname, main-domain rejection, authentication probe, no main-site analytics route, required IPv4/IPv6 allowlist on both routes.\n'
