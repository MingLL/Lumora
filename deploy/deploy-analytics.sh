#!/usr/bin/env bash
# Deploy only the independent analytics frontend. Backend migrations are separate.
# ANALYTICS_HOST=analytics.lumora.love ./deploy/deploy-analytics.sh
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
ANALYTICS_HOST="${ANALYTICS_HOST:-analytics.lumora.love}"
REMOTE_DIR=/opt/lumora/analytics
DIST=analytics/dist
MANIFEST=deploy/k8s/lumora-analytics.yaml
skip_build=false
allow_behind=false
render_only=false
fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=true ;;
    --allow-behind) allow_behind=true ;;
    --render-only) render_only=true ;;
    *) fail "未知参数：$arg" ;;
  esac
done
# Validate before interpolating the hostname into YAML or commands.
[[ ${#ANALYTICS_HOST} -le 253 && "$ANALYTICS_HOST" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}$ ]] \
  || fail 'ANALYTICS_HOST 必须是有效的小写域名，不带协议或路径'
[[ "$ANALYTICS_HOST" != lumora.love && "$ANALYTICS_HOST" != www.lumora.love ]] \
  || fail '统计站不能使用主站域名'
[[ "$ANALYTICS_HOST" != *..* && "$ANALYTICS_HOST" != *.-* && "$ANALYTICS_HOST" != *-.* ]] \
  || fail '域名标签格式无效'
# Local file is intentionally ignored by git; explicit env (including empty)
# takes precedence. Read it as data, never source it as shell code.
if [[ -z "${ANALYTICS_ALLOWED_IPS+x}" && -f deploy/analytics-allowed-ips.local ]]; then
  ANALYTICS_ALLOWED_IPS=$(cat deploy/analytics-allowed-ips.local)
  export ANALYTICS_ALLOWED_IPS
fi
manifest_hash=$(shasum -a 256 "$MANIFEST" | cut -c1-12)
# Validate the required allowlist before builds, rsync or any remote changes.
rendered_manifest=$(python3 deploy/render-analytics.py "$ANALYTICS_HOST" "$manifest_hash") \
  || fail '请设置有效的 ANALYTICS_ALLOWED_IPS，多个 IP 或 CIDR 用逗号分隔'
render() { printf '%s\n' "$rendered_manifest"; }
if [[ "$render_only" == true ]]; then render; exit 0; fi
if [[ "$allow_behind" == false ]] && upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null); then
  git fetch --quiet origin || fail '无法检查远端版本；确认后可使用 --allow-behind'
  [[ $(git rev-list --count "HEAD..$upstream") -eq 0 ]] || fail '本地落后远端，请先更新代码'
fi
if [[ "$skip_build" == false ]]; then (cd analytics && npm run build); fi
[[ -f "$DIST/index.html" ]] || fail 'analytics/dist/index.html 不存在，请先构建'
printf '发布统计站：https://%s\n' "$ANALYTICS_HOST"
ssh dev1 "mkdir -p '$REMOTE_DIR'"
rsync -az --delete "$DIST/" "dev1:$REMOTE_DIR/"
ssh dev1 "chmod -R a+rX '$REMOTE_DIR'"
render | ssh dev1 'cat > /tmp/lumora-analytics.yaml && k3s kubectl apply -f /tmp/lumora-analytics.yaml'
ssh dev1 'k3s kubectl rollout status daemonset/lumora-analytics -n lumora --timeout=120s'
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "https://$ANALYTICS_HOST/" || true)
[[ "$code" == 200 ]] || fail "统计站返回 ${code:-000}；检查 DNS、证书、入口路由及当前公网出口是否在白名单（403 表示被拒绝）"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
  -H 'X-Request-Id: analytics-deploy-check' "https://$ANALYTICS_HOST/api/analytics/summary" || true)
[[ "$code" == 401 ]] || fail "统计接口匿名访问应返回 401，实际为 ${code:-000}"
printf '统计站发布完成。\n'
