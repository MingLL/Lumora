#!/usr/bin/env bash
#
# 后端发布：本地构建镜像 → 导入两台节点 → 迁移 → 滚动更新
#
#   ./deploy/deploy-backend.sh                 用当前 git commit 当镜像 tag
#   ./deploy/deploy-backend.sh v20260729       指定 tag
#   ./deploy/deploy-backend.sh --skip-build    复用已构建好的同名镜像
#
# 跟 deploy.sh（前端静态站）互不影响，两者共用 lumora 命名空间和 Traefik 入口。
#
# 为什么要 docker save / ctr import 而不是推私有仓库：
# 那两台阿里云机器直连 Docker Hub 不通，也没有可用的私有 registry。
# k3s 内置 containerd，导入本地 tar 是最省事且不引入新组件的做法。
set -euo pipefail

HOSTS=(dev1 dev2)
CONTROL_HOST=dev1
NAMESPACE=lumora
REMOTE_ENV=/opt/lumora/backend/.env        # 服务器上的凭据，不进 git，脚本只读不写
REMOTE_TMP=/tmp
PLATFORM=linux/amd64                        # 本地可能是 arm64 Mac，服务器是 x86_64

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
fail() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

skip_build=false
tag=""
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=true ;;
    -*) fail "未知参数：$arg" ;;
    *) tag="$arg" ;;
  esac
done

# 不可变 tag：默认取 git commit，脏工作区拒绝发布，免得线上镜像对不上任何一个提交。
if [[ -z "$tag" ]]; then
  [[ -z "$(git status --porcelain)" ]] || fail "工作区不干净，提交后再发或显式指定 tag"
  tag="$(git rev-parse --short=12 HEAD)"
fi
IMAGE="lumora-backend:$tag"
TAR="lumora-backend-$tag.tar"

step "预检"
command -v docker >/dev/null || fail "本地没有 docker"
for h in "${HOSTS[@]}"; do
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$h" true 2>/dev/null || fail "连不上 $h"
  # 只读检查：确认凭据文件存在且权限收紧，不读取也不打印其内容。
  ssh "$h" "test -f '$REMOTE_ENV'" || fail "$h 上缺少 $REMOTE_ENV"
  perms=$(ssh "$h" "stat -c '%a' '$REMOTE_ENV'")
  # ${} 的花括号是必要的：变量名后面直接跟中文标点会被 bash 吃进变量名。
  [[ "${perms: -1}" == "0" ]] || fail "$h 上 $REMOTE_ENV 权限是 ${perms}，应为 600"
  info "$h ✓"
done

step "构建镜像 ${IMAGE}（${PLATFORM}）"
if [[ "$skip_build" == false ]]; then
  docker build --platform "$PLATFORM" -t "$IMAGE" backend
else
  docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "本地没有 ${IMAGE}，去掉 --skip-build"
  info "跳过构建，复用已有镜像"
fi

step "校验镜像"
bash backend/deploy/verify-packaging.sh "$IMAGE"

step "分发镜像 → ${HOSTS[*]}"
docker save "$IMAGE" -o "/tmp/$TAR"
for h in "${HOSTS[@]}"; do
  printf '    %s ' "$h"
  scp -q "/tmp/$TAR" "$h:$REMOTE_TMP/$TAR"
  ssh "$h" "sudo k3s ctr images import '$REMOTE_TMP/$TAR' >/dev/null && rm -f '$REMOTE_TMP/$TAR'"
  printf '✓\n'
done
rm -f "/tmp/$TAR"

step "刷新 Secret/lumora-env"
# 从服务器上的 .env 生成，值不经过本地，也不出现在命令行参数里。
ssh "$CONTROL_HOST" "sudo k3s kubectl create namespace '$NAMESPACE' \
    --dry-run=client -o yaml | sudo k3s kubectl apply -f - >/dev/null"
ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' create secret generic lumora-env \
    --from-env-file='$REMOTE_ENV' --dry-run=client -o yaml | sudo k3s kubectl apply -f - >/dev/null"
info "已更新（未打印任何值）"

step "执行迁移（expand-only）"
# 必须在 apply 应用清单之前完成：旧版本和新版本都要能跑在迁移后的库上。
sed -e "s|__IMAGE__|$IMAGE|g" -e "s|__IMAGE_TAG__|$tag|g" \
    deploy/k8s/lumora-backend-migrate.yaml \
  | ssh "$CONTROL_HOST" "cat > /tmp/lumora-migrate.yaml && sudo k3s kubectl apply -f /tmp/lumora-migrate.yaml"
if ! ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' wait --for=condition=complete \
      job/lumora-migrate-$tag --timeout=300s" >/dev/null; then
  ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' logs job/lumora-migrate-$tag --tail=50" >&2 || true
  fail "迁移失败，未改动任何正在运行的服务"
fi
info "迁移完成"

step "校验新旧镜像都能用迁移后的库"
ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' run lumora-smoke-$tag \
    --image='$IMAGE' --restart=Never --rm -i --quiet \
    --overrides='{\"spec\":{\"containers\":[{\"name\":\"smoke\",\"image\":\"$IMAGE\",\"env\":[{\"name\":\"LUMORA_MODE\",\"value\":\"schema-smoke\"}],\"envFrom\":[{\"secretRef\":{\"name\":\"lumora-env\"}}]}]}}' \
    " >/dev/null || fail "schema-smoke 失败：候选镜像读不了迁移后的库"
info "schema-smoke 通过"

step "应用清单"
manifest_hash=$(shasum -a 256 deploy/k8s/lumora-backend.yaml | cut -c1-12)
sed -e "s|__IMAGE__|$IMAGE|g" -e "s|__MANIFEST_HASH__|$manifest_hash|g" \
    deploy/k8s/lumora-backend.yaml \
  | ssh "$CONTROL_HOST" "cat > /tmp/lumora-backend.yaml && sudo k3s kubectl apply -f /tmp/lumora-backend.yaml"

step "等待就绪"
for d in lumora-backend-web lumora-backend-ops lumora-backend-worker; do
  printf '    %s ' "$d"
  if ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' rollout status deployment/$d --timeout=180s" >/dev/null; then
    printf '✓\n'
  else
    printf '\033[1;31m✗\033[0m\n'
    ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' logs deployment/$d --tail=40" >&2 || true
    fail "$d 未就绪。旧 pod 仍在服务（web 是 maxUnavailable=0）。回滚：
      ssh $CONTROL_HOST \"sudo k3s kubectl -n $NAMESPACE rollout undo deployment/$d\""
  fi
done

step "验证"
# web 通了才算数：回调路径必须能从集群内打通。
ssh "$CONTROL_HOST" "sudo k3s kubectl -n '$NAMESPACE' exec deployment/lumora-backend-web -- \
    sh -c 'curl -fsS http://127.0.0.1:8080/actuator/health/liveness'" >/dev/null \
  || fail "web 存活探针不通"
info "web 存活 ✓"

# 公网只应放出回调路径；内部端点必须打不到。
for path in /internal/reports/2026-01-01/send /actuator/health/readiness; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://47.120.54.233$path" || true)
  if [[ "$code" == "404" || "$code" == "000" ]]; then
    info "公网 $path → $code ✓"
  else
    fail "公网能访问 ${path}（返回 ${code}），Ingress 放得太宽"
  fi
done

printf '\n\033[1;32m后端发布完成：%s\033[0m\n' "$IMAGE"
printf '手动补发（在服务器上，不走公网）：\n'
printf '  ssh %s "sudo k3s kubectl -n %s exec deployment/lumora-backend-ops -- \\\n' "$CONTROL_HOST" "$NAMESPACE"
printf '    curl -fsS -X POST http://127.0.0.1:8080/internal/reports/YYYY-MM-DD/send \\\n'
printf '    -H \x27X-Lumora-Admin-Key: <REPORT_ADMIN_KEY>\x27 -H \x27X-Request-Id: <uuid>\x27"\n'
