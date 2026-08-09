#!/usr/bin/env bash
#
# 后端发布：本地构建镜像 → 导入后端节点 → 迁移 → 滚动更新
#
#   ./deploy/deploy-backend.sh                 用当前 git commit 当镜像 tag
#   ./deploy/deploy-backend.sh v20260729       指定 tag
#   ./deploy/deploy-backend.sh --skip-build    复用已构建好的同名镜像
#   ./deploy/deploy-backend.sh --allow-behind  本地落后远端时仍然发布（见下方预检）
#
# 跟 deploy.sh（前端静态站）互不影响，两者共用 lumora 命名空间和 Traefik 入口。
#
# 为什么要 docker save / ctr import 而不是推私有仓库：
# 那两台阿里云机器直连 Docker Hub 不通，也没有可用的私有 registry。
# k3s 内置 containerd，导入本地 tar 是最省事且不引入新组件的做法。
set -euo pipefail

# 只有真正调度后端 Pod 的节点才需要镜像，控制面故意不在其中。
#
# 2026-08-04 17:22 往 dev1 导这个镜像（147 MB）时，解包和 k3s 的 kine(SQLite)
# 抢同一块盘，kine 单条 INSERT 从毫秒涨到 3.5 秒，apiserver 开始 i/o timeout，
# kubelet 续不上 node lease，17:32:09 dev1 自判 NotReady，kubelet 顺手重启了
# 它管的 Pod —— 其中包括 Traefik（唯一入口），整站断了几分钟，dev2 也因为
# 到 supervisor 的隧道断开跟着 NotReady。dev1 上根本不跑后端 Pod，这份 I/O
# 是纯浪费。三周的 journal 里 NotReady 只出现过两次，两次都在 import 期间。
IMAGE_HOSTS=(dev2)
BACKEND_NODE=dev2                          # 三个 Deployment、迁移 Job、schema-smoke 都钉在这里
CONTROL_HOST=dev1                          # 跑 kubectl 的节点（k3s control-plane），不导镜像
NAMESPACE=lumora
SITE_URL=https://lumora.love                # Ingress 按 host 匹配，探测必须带对域名才有意义
REMOTE_ENV=/opt/lumora/backend/.env        # 服务器上的凭据，不进 git，脚本只读不写
REMOTE_TMP=/tmp
PLATFORM=linux/amd64                        # 本地可能是 arm64 Mac，服务器是 x86_64
K3S=/usr/local/bin/k3s                     # sudo 的 secure_path 不包含 /usr/local/bin

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
fail() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

skip_build=false
allow_behind=false
[[ -n "${ALLOW_BEHIND:-}" ]] && allow_behind=true    # 供契约测试等非交互场景跳过联网检查
tag=""
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=true ;;
    --allow-behind) allow_behind=true ;;
    -*) fail "未知参数：$arg" ;;
    *) tag="$arg" ;;
  esac
done

# 清单同样取自当前工作树，落后远端就等于拿旧清单覆盖线上。
# 事故经过见 deploy.sh 里同一处注释（2026-08-09，前端发布删掉了微信验证文件）。
# ALLOW_BEHIND=1 与 --allow-behind 等价，且在 fetch 之前就短路 —— 契约测试用假 ssh
# 打桩，真去 fetch 会卡死在那个假 ssh 上，测试不该联网。
step "预检：本地是否落后远端"
if [[ "$allow_behind" == true ]]; then
  printf '    \033[1;33m已显式放行，跳过检查\033[0m\n'
elif upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null); then
  # 取不到远端就是瞎的，而「判断不了却照发」正是出事那天的状态，所以拒绝发布。
  git fetch --quiet origin 2>/dev/null \
    || fail "git fetch 失败，无法判断本地是否落后远端。
      网络恢复后重试；确认无碍可加 --allow-behind。"
  behind=$(git rev-list --count "HEAD..$upstream" 2>/dev/null || echo 0)
  [[ "$behind" -eq 0 ]] \
    || fail "本地落后 $upstream $behind 个提交，发布会用旧清单覆盖线上。
      先 git rebase $upstream 再发；确认无碍可加 --allow-behind。"
  info "与 $upstream 一致"
else
  info "当前分支没有上游，跳过"
fi

# 不可变 tag：默认取 git commit，脏工作区拒绝发布，免得线上镜像对不上任何一个提交。
if [[ -z "$tag" ]]; then
  [[ -z "$(git status --porcelain)" ]] || fail "工作区不干净，提交后再发或显式指定 tag"
  tag="$(git rev-parse --short=12 HEAD)"
fi
IMAGE="lumora-backend:$tag"
TAR="lumora-backend-$tag.tar"

step "预检"
command -v docker >/dev/null || fail "本地没有 docker"
# 控制面要跑 kubectl，后端节点要收镜像，两边都得连得上。去重是因为
# 将来若把后端挪回控制面，两个变量会指向同一台，不该检查两次。
for h in $(printf '%s\n' "$CONTROL_HOST" "${IMAGE_HOSTS[@]}" | awk '!seen[$0]++'); do
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$h" true 2>/dev/null || fail "连不上 $h"
  info "$h ✓"
done
# Secret 只由控制节点上的文件生成，工作节点不需要复制生产密钥。
ssh "$CONTROL_HOST" "test -f '$REMOTE_ENV'" || fail "$CONTROL_HOST 上缺少 $REMOTE_ENV"
perms=$(ssh "$CONTROL_HOST" "stat -c '%a' '$REMOTE_ENV'")
# ${} 的花括号是必要的：变量名后面直接跟中文标点会被 bash 吃进变量名。
[[ "${perms: -1}" == "0" ]] || fail "$CONTROL_HOST 上 $REMOTE_ENV 权限是 ${perms}，应为 600"

step "构建镜像 ${IMAGE}（${PLATFORM}）"
if [[ "$skip_build" == false ]]; then
  docker build --platform "$PLATFORM" -t "$IMAGE" backend
else
  docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "本地没有 ${IMAGE}，去掉 --skip-build"
  info "跳过构建，复用已有镜像"
fi

step "校验镜像"
bash backend/deploy/verify-packaging.sh "$IMAGE"

step "分发镜像 → ${IMAGE_HOSTS[*]}"
docker save "$IMAGE" -o "/tmp/$TAR"
for h in "${IMAGE_HOSTS[@]}"; do
  printf '    %s ' "$h"
  scp -q "/tmp/$TAR" "$h:$REMOTE_TMP/$TAR"
  # ionice -c3（idle）+ nice -n19：解包只在磁盘和 CPU 空闲时推进。即使将来
  # 后端挪回控制面，也不至于把 kine 的写入拖到超时 —— 见文件头 IMAGE_HOSTS 的注释。
  ssh "$h" "sudo ionice -c3 nice -n19 $K3S ctr images import '$REMOTE_TMP/$TAR' >/dev/null && rm -f '$REMOTE_TMP/$TAR'"
  printf '✓\n'
done
rm -f "/tmp/$TAR"

step "刷新 Secret/lumora-env"
# 从服务器上的 .env 生成，值不经过本地，也不出现在命令行参数里。
ssh "$CONTROL_HOST" "sudo $K3S kubectl create namespace '$NAMESPACE' \
    --dry-run=client -o yaml | sudo $K3S kubectl apply -f - >/dev/null"
ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' create secret generic lumora-env \
    --from-env-file='$REMOTE_ENV' --dry-run=client -o yaml | sudo $K3S kubectl apply -f - >/dev/null"
info "已更新（未打印任何值）"

step "执行迁移（expand-only）"
# 必须在 apply 应用清单之前完成：旧版本和新版本都要能跑在迁移后的库上。
sed -e "s|__IMAGE__|$IMAGE|g" -e "s|__IMAGE_TAG__|$tag|g" \
    deploy/k8s/lumora-backend-migrate.yaml \
  | ssh "$CONTROL_HOST" "cat > /tmp/lumora-migrate.yaml && sudo $K3S kubectl apply -f /tmp/lumora-migrate.yaml"
if ! ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' wait --for=condition=complete \
      job/lumora-migrate-$tag --timeout=300s" >/dev/null; then
  ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' logs job/lumora-migrate-$tag --tail=50" >&2 || true
  fail "迁移失败，未改动任何正在运行的服务"
fi
info "迁移完成"

step "校验新旧镜像都能用迁移后的库"
# nodeSelector 不能省：镜像只在 BACKEND_NODE 上，而 imagePullPolicy 默认对
# 带 tag 的镜像是 IfNotPresent，集群又没有可用 registry。不钉节点的话调度器
# 可能把它放到控制面，然后卡在 ImagePullBackOff 而不是真的校验了什么。
#
# labels.app=lumora-backend 不能省：lumora-mysql-ingress NetworkPolicy 只放行
# 带这个 label 的 pod 访问 MySQL:3306。kubectl run 默认只打 run=<name>，
# 不带 app=lumora-backend 会被 NetworkPolicy 拦成 Connection refused，
# 看起来像数据库挂了，其实是网络策略。
ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' run lumora-smoke-$tag \
    --image='$IMAGE' --restart=Never --rm -i --quiet \
    --overrides='{\"metadata\":{\"labels\":{\"app\":\"lumora-backend\"}},\"spec\":{\"nodeSelector\":{\"kubernetes.io/hostname\":\"$BACKEND_NODE\"},\"enableServiceLinks\":false,\"containers\":[{\"name\":\"smoke\",\"image\":\"$IMAGE\",\"env\":[{\"name\":\"LUMORA_MODE\",\"value\":\"schema-smoke\"}],\"envFrom\":[{\"secretRef\":{\"name\":\"lumora-env\"}}]}]}}' \
    " >/dev/null || fail "schema-smoke 失败：候选镜像读不了迁移后的库"
info "schema-smoke 通过"

step "应用清单"
manifest_hash=$(shasum -a 256 deploy/k8s/lumora-backend.yaml | cut -c1-12)
sed -e "s|__IMAGE__|$IMAGE|g" -e "s|__MANIFEST_HASH__|$manifest_hash|g" \
    deploy/k8s/lumora-backend.yaml \
  | ssh "$CONTROL_HOST" "cat > /tmp/lumora-backend.yaml && sudo $K3S kubectl apply -f /tmp/lumora-backend.yaml"

# 入口层（Middleware + IngressRoute）前后端共用，两个发布脚本都要 apply，
# 否则线上改动会被另一边的旧状态覆盖。
ssh "$CONTROL_HOST" "cat > /tmp/lumora-ingress.yaml && sudo $K3S kubectl apply -f /tmp/lumora-ingress.yaml" \
  < deploy/k8s/lumora-ingress.yaml

step "等待就绪"
for d in lumora-backend-web lumora-backend-ops lumora-backend-worker; do
  printf '    %s ' "$d"
  if ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' rollout status deployment/$d --timeout=180s" >/dev/null; then
    printf '✓\n'
  else
    printf '\033[1;31m✗\033[0m\n'
    ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' logs deployment/$d --tail=40" >&2 || true
    fail "$d 未就绪。旧 pod 仍在服务（web 是 maxUnavailable=0）。回滚：
      ssh $CONTROL_HOST \"sudo $K3S kubectl -n $NAMESPACE rollout undo deployment/$d\""
  fi
done

step "验证"
# web 通了才算数：回调路径必须能从集群内打通。
ssh "$CONTROL_HOST" "sudo $K3S kubectl -n '$NAMESPACE' exec deployment/lumora-backend-web -- \
    sh -c 'curl -fsS http://127.0.0.1:8080/actuator/health/liveness'" >/dev/null \
  || fail "web 存活探针不通"
info "web 存活 ✓"

# 公网只应放出回调路径；内部端点必须打不到。
for path in /internal/reports/2026-01-01/send /actuator/health/readiness; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$SITE_URL$path" || true)
  if [[ "$code" == "404" || "$code" == "000" ]]; then
    info "公网 $path → $code ✓"
  else
    fail "公网能访问 ${path}（返回 ${code}），Ingress 放得太宽"
  fi
done

printf '\n\033[1;32m后端发布完成：%s\033[0m\n' "$IMAGE"
printf '手动补发（在服务器上，不走公网）：\n'
printf '  ssh %s "sudo %s kubectl -n %s exec deployment/lumora-backend-ops -- \\\n' "$CONTROL_HOST" "$K3S" "$NAMESPACE"
printf '    curl -fsS -X POST http://127.0.0.1:8080/internal/reports/YYYY-MM-DD/send \\\n'
printf '    -H \x27X-Lumora-Admin-Key: <REPORT_ADMIN_KEY>\x27 -H \x27X-Request-Id: <uuid>\x27"\n'
