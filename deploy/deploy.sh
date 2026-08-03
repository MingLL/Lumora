#!/usr/bin/env bash
#
# 一键发布：本地构建 → 同步到两台服务器 → 应用 k8s 清单
#
#   ./deploy/deploy.sh                只发内容（默认，先构建）
#   ./deploy/deploy.sh --skip-build   跳过构建，直接发已有的 frontend/dist/
#
# 只管前端静态站，跟 backend/ 无关 —— 后端是独立的 Spring Boot 容器。
# 站点是纯静态的，服务器上不需要 Node —— 构建在本地完成，只把 dist/ 推上去。
set -euo pipefail

HOSTS=(dev1 dev2)
CONTROL_HOST=dev1                          # 跑 kubectl 的节点（k3s control-plane）
SITE_URL=https://lumora.love               # Ingress 按 host 匹配，用 IP 探测拿到的永远是 404
REMOTE_DIR=/opt/lumora/site
FRONTEND_DIR=frontend                      # Astro 站点的根，npm 相关的都在里面
DIST=$FRONTEND_DIR/dist

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

skip_build=false
[[ "${1:-}" == "--skip-build" ]] && skip_build=true

if [[ "$skip_build" == false ]]; then
  step "本地构建"
  # 在子 shell 里进 frontend/ 构建，外面的 cwd 仍是仓库根，
  # 下面 deploy/k8s/lumora.yaml 那类相对路径才不用跟着改。
  ( cd "$FRONTEND_DIR" && npm run build )
else
  step "跳过构建，使用现有 $DIST/"
fi

[[ -f $DIST/index.html ]] || fail "$DIST/index.html 不存在，请先执行 (cd $FRONTEND_DIR && npm run build)"

step "同步静态文件 → ${HOSTS[*]}"
for h in "${HOSTS[@]}"; do
  printf '    %s ' "$h"
  # --delete 让服务器上的文件与 dist/ 严格一致，删掉的页面不会留下孤儿。
  # 不用 --chmod：macOS 自带的是 openrsync，不支持该参数；权限在下一步统一校正。
  rsync -az --delete "$DIST/" "$h:$REMOTE_DIR/"
  ssh "$h" "chmod -R a+rX '$REMOTE_DIR'"
  printf '✓\n'
done

step "应用 k8s 清单"
manifest_hash=$(shasum -a 256 deploy/k8s/lumora.yaml | cut -c1-12)
sed "s/__MANIFEST_HASH__/$manifest_hash/" deploy/k8s/lumora.yaml \
  | ssh "$CONTROL_HOST" 'cat > /tmp/lumora.yaml && k3s kubectl apply -f /tmp/lumora.yaml'

# 入口层（Middleware + IngressRoute）前后端共用，两个发布脚本都要 apply，
# 否则线上改动会被另一边的旧状态覆盖。
ssh "$CONTROL_HOST" 'cat > /tmp/lumora-ingress.yaml && k3s kubectl apply -f /tmp/lumora-ingress.yaml' \
  < deploy/k8s/lumora-ingress.yaml

step "等待 pod 就绪"
ssh "$CONTROL_HOST" 'k3s kubectl rollout status daemonset/lumora-web -n lumora --timeout=120s'

step "验证公网访问"
# curl 失败时 %{http_code} 本身就输出 000，不要再 || echo 叠加一次
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$SITE_URL/" 2>/dev/null || true)
if [[ "$code" != "200" ]]; then
  printf '    %-22s → \033[1;31m%s\033[0m\n' "$SITE_URL/" "${code:-000}"
  # pod 已经 rollout 成功了，所以公网不通几乎都是安全组或 DNS，而不是应用问题
  printf '\n\033[1;33m注意:\033[0m 站点公网不可达。\n'
  printf '      pod 已就绪，通常是阿里云安全组未放行 80/443 入方向，或 DNS 未指向 dev1。\n'
  printf '      排查：ssh %s "k3s kubectl -n lumora get pod -o wide"\n' "$CONTROL_HOST"
  exit 1
fi
printf '    %-22s → \033[1;32m200\033[0m\n' "$SITE_URL/"

# 80 端口应当 301 到 https。这是加固项，回归了要当场看见，但不阻断发布。
redirect=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://lumora.love/ 2>/dev/null || true)
if [[ "$redirect" == "301" ]]; then
  printf '    %-22s → \033[1;32m301 → https\033[0m\n' "http://lumora.love/"
else
  printf '    %-22s → \033[1;33m%s（预期 301）\033[0m\n' "http://lumora.love/" "${redirect:-000}"
fi
printf '\n\033[1;32m发布完成。\033[0m\n'
