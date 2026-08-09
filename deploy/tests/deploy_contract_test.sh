#!/usr/bin/env bash
#
# deploy-backend.sh 的契约测试。
#
#   bash deploy/tests/deploy_contract_test.sh
#
# 用假的 docker/ssh/scp/curl/git 占住 PATH，跑一遍发布脚本，然后断言它「做了什么、
# 按什么顺序做的」。不碰任何真实服务器、镜像或集群。
#
# 这里锁住的是几条一旦破坏就会在生产上出事的性质：
#   1. 迁移必须早于 apply —— 否则新 pod 会打到没迁移的库
#   2. 校验镜像必须早于分发 —— 否则带密钥的镜像已经上了服务器
#   3. 预检失败必须在任何变更之前停下
#   4. 脚本任何时候都不能把 .env 的内容打印出来
#   5. 工作区脏时必须拒绝发布（tag 必须对得上一个提交）
set -uo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 假命令把调用写到这里；run_deploy 在子 shell 里跑，所以路径必须在顶层定好。
export CALLS="$WORK/calls.log"

pass=0
fail=0
ok()   { printf '\033[1;32mok\033[0m   %s\n' "$*"; pass=$((pass + 1)); }
no()   { printf '\033[1;31mfail\033[0m %s\n' "$*" >&2; fail=$((fail + 1)); }

# ---------------------------------------------------------------- 伪造的外部命令
# 每个假命令把自己的调用追加到 $CALLS，顺序断言就靠这个文件。
make_fakes() {
  local bin="$1" mode="${2:-happy}"
  mkdir -p "$bin"

  cat > "$bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
case "$1" in
  image) exit 0 ;;
  save) shift; while [[ $# -gt 0 ]]; do [[ "$1" == "-o" ]] && { shift; : > "$1"; }; shift; done; exit 0 ;;
esac
exit 0
EOF

  cat > "$bin/ssh" <<'EOF'
#!/usr/bin/env bash
echo "ssh $*" >> "$CALLS"
# 预检里读权限位的那次调用要返回 600，否则脚本会判定权限不安全。
[[ "$*" == *"stat -c"* ]] && { echo 600; exit 0; }
# 脚本里管道进来的 manifest 要吃掉，别让它污染断言。
cat >/dev/null 2>&1 || true
exit 0
EOF

  cat > "$bin/scp" <<'EOF'
#!/usr/bin/env bash
echo "scp $*" >> "$CALLS"
exit 0
EOF

  cat > "$bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$CALLS"
# 公网探测内部端点：必须表现为打不到，脚本才应该放行。
for arg in "$@"; do
  case "$arg" in
    *"/internal/"*|*"/actuator/health/readiness"*) echo "404"; exit 0 ;;
  esac
done
echo "200"
exit 0
EOF

  cat > "$bin/shasum" <<'EOF'
#!/usr/bin/env bash
echo "deadbeefcafe0000  -"
EOF

  cat > "$bin/git" <<EOF
#!/usr/bin/env bash
echo "git \$*" >> "\$CALLS"
case "\$1 \$2" in
  "status --porcelain") $( [[ "$mode" == dirty ]] && echo 'echo " M backend/pom.xml"' || echo ':' ) ;;
  "rev-parse "*) echo "abc123def456" ;;
esac
exit 0
EOF

  # verify-packaging.sh 是被 deploy 脚本调用的真实脚本，这里用假的顶掉，
  # 它自身的行为由 backend/deploy/verify-packaging.sh 直接跑来覆盖。
  mkdir -p "$WORK/backend/deploy"
  cat > "$WORK/backend/deploy/verify-packaging.sh" <<'EOF'
#!/usr/bin/env bash
echo "verify-packaging $*" >> "$CALLS"
exit 0
EOF

  chmod +x "$bin"/* "$WORK/backend/deploy/verify-packaging.sh"
}

# 在隔离目录里跑发布脚本，返回它的退出码，调用序列留在 $CALLS。
run_deploy() {
  local mode="${1:-happy}"; shift || true
  rm -rf "$WORK/bin" "$WORK/repo"
  mkdir -p "$WORK/bin" "$WORK/repo"
  make_fakes "$WORK/bin" "$mode"

  # 只复制脚本需要的文件，保证测试不依赖仓库其它部分。
  mkdir -p "$WORK/repo/deploy/k8s" "$WORK/repo/backend/deploy" "$WORK/repo/.git"
  cp "$TEST_ROOT/deploy/deploy-backend.sh" "$WORK/repo/deploy/"
  cp "$TEST_ROOT/deploy/k8s/lumora-backend.yaml" "$WORK/repo/deploy/k8s/"
  cp "$TEST_ROOT/deploy/k8s/lumora-backend-migrate.yaml" "$WORK/repo/deploy/k8s/"
  # 入口层清单前后端共用，deploy-backend.sh 也会 apply 一次，少了它脚本会直接失败。
  cp "$TEST_ROOT/deploy/k8s/lumora-ingress.yaml" "$WORK/repo/deploy/k8s/"
  cp "$WORK/backend/deploy/verify-packaging.sh" "$WORK/repo/backend/deploy/"
  printf 'SECRET_CANARY=do-not-print-me\n' > "$WORK/repo/backend/.env"

  : > "$CALLS"
  (
    cd "$WORK/repo"
    # ALLOW_BEHIND 让发布脚本跳过「是否落后远端」的预检。那一步要 git fetch，而这里
    # 的 ssh 是假的（$WORK/bin/ssh），真去 fetch 会卡死在假 ssh 上。测试不该联网。
    ALLOW_BEHIND=1 PATH="$WORK/bin:$PATH" bash deploy/deploy-backend.sh "$@"
  ) > "$WORK/stdout.log" 2> "$WORK/stderr.log"
  echo $?
}

# 断言 $1 在 $2 之前被调用。
before() {
  local first="$1" second="$2" label="$3"
  local a b
  a=$(grep -n -- "$first" "$CALLS" | head -1 | cut -d: -f1)
  b=$(grep -n -- "$second" "$CALLS" | head -1 | cut -d: -f1)
  # 注意 ${var} 的花括号：变量名后面直接跟中文标点时，bash 会把多字节字符
  # 当成变量名的一部分，set -u 下直接报 unbound variable。
  if [[ -z "$a" ]]; then no "${label}（没找到 '${first}'）"; return; fi
  if [[ -z "$b" ]]; then no "${label}（没找到 '${second}'）"; return; fi
  if (( a < b )); then ok "$label"; else no "${label}（'${first}' 在第 ${a} 行，'${second}' 在第 ${b} 行）"; fi
}

# ------------------------------------------------------------------------ 用例

printf '\n--- 资源配置 ---\n'
# IngressRoute lumora-love-tls 曾经在 lumora.yaml 里，e503260「移除重复 IngressRoute」
# 把它挪到了入口层清单 lumora-ingress.yaml，但这两条断言当时没跟着改，于是它们从那次
# 提交起就一直是红的 —— 断言找不到目标时报的是「未配置」，看起来像配置真的丢了，
# 掩盖了「测试自己找错了文件」。改成读入口层清单。
tls_resolver=$(
  awk '
    $0 == "kind: IngressRoute" { ingress_route = 1; next }
    ingress_route && $0 == "  name: lumora-love-tls" { lumora_tls = 1; next }
    lumora_tls && $1 == "certResolver:" { print $2; exit }
  ' "$TEST_ROOT/deploy/k8s/lumora-ingress.yaml"
)
[[ "$tls_resolver" == "le-prod" ]] \
  && ok "lumora.love 使用线上存在的 le-prod 证书解析器" \
  || no "lumora.love 的证书解析器是 ${tls_resolver:-未配置}，预期为 le-prod"

tls_entrypoints=$(
  awk '
    $0 == "kind: IngressRoute" { ingress_route = 1; next }
    ingress_route && $0 == "  name: lumora-love-tls" { lumora_tls = 1; next }
    lumora_tls && $0 == "  entryPoints:" { entrypoints = 1; next }
    entrypoints && /^    - / { print $2; next }
    entrypoints { exit }
  ' "$TEST_ROOT/deploy/k8s/lumora-ingress.yaml" | paste -sd, -
)
[[ "$tls_entrypoints" == "websecure" ]] \
  && ok "TLS 路由只占用 websecure，保留 web 给 ACME HTTP challenge" \
  || no "TLS 路由 entryPoints 是 ${tls_entrypoints:-未配置}，预期只包含 websecure"

frontend_node=$(
  awk '
    $0 == "kind: DaemonSet" { daemonset = 1; next }
    daemonset && $0 == "  name: lumora-web" { web = 1; next }
    web && $1 == "kubernetes.io/hostname:" { print $2; exit }
  ' "$TEST_ROOT/deploy/k8s/lumora.yaml"
)
[[ "$frontend_node" == "dev1" ]] \
  && ok "前端 Pod 只调度到有公网入口的 dev1" \
  || no "前端 Pod 调度节点是 ${frontend_node:-未指定}，预期为 dev1"

frontend_hosts=$(
  awk -F'[()]' '$1 == "HOSTS=" { print $2; exit }' "$TEST_ROOT/deploy/deploy.sh"
)
[[ "$frontend_hosts" == "dev1" ]] \
  && ok "前端静态文件只同步到 dev1" \
  || no "前端静态文件同步目标是 ${frontend_hosts:-未找到}，预期仅 dev1"

web_replicas=$(
  awk '
    $0 == "kind: Deployment" { deployment = 1; next }
    deployment && $0 == "  name: lumora-backend-web" { web = 1; next }
    web && $1 == "replicas:" { print $2; exit }
  ' "$TEST_ROOT/deploy/k8s/lumora-backend.yaml"
)
[[ "$web_replicas" == "1" ]] \
  && ok "低内存集群默认只运行一个 Web 副本" \
  || no "Web 副本数是 ${web_replicas:-未找到}，预期为 1"

# 两种角色都必须落在 dev2。这不只是资源摆放——镜像只分发到 dev2（控制面
# 不导后端镜像，见 deploy-backend.sh 的 IMAGE_HOSTS），任何一个角色跑到 dev1
# 都会卡在 ImagePullBackOff。改节点必须同时改 IMAGE_HOSTS，下面的用例会一起验。
for role in web worker; do
  role_node=$(
    awk -v target="lumora-backend-${role}" '
      $0 == "kind: Deployment" { deployment = 1; matched = 0; next }
      deployment && $0 == "  name: " target { matched = 1; next }
      matched && $1 == "kubernetes.io/hostname:" { print $2; exit }
    ' "$TEST_ROOT/deploy/k8s/lumora-backend.yaml"
  )
  [[ "$role_node" == "dev2" ]] \
    && ok "${role} Pod 固定调度到 dev2" \
    || no "${role} Pod 调度节点是 ${role_node:-未指定}，预期为 dev2"
done

migrate_node=$(
  awk '$1 == "kubernetes.io/hostname:" { print $2; exit }' \
    "$TEST_ROOT/deploy/k8s/lumora-backend-migrate.yaml"
)
[[ "$migrate_node" == "dev2" ]] \
  && ok "迁移 Job 钉在有镜像的 dev2" \
  || no "迁移 Job 调度节点是 ${migrate_node:-未指定}，预期为 dev2（否则 ImagePullBackOff）"

# PostgreSQL 必须钉在节点上，而不是让调度器随便放。两个理由：
#   1. 它用 hostPath，数据不跟着 pod 漂移，换节点就等于换了一个空库；
#   2. 下面的内存预算把 backend 和 PostgreSQL 的 limits 加在一起，这个加法只有在
#      两者同处一台机器时才成立。
# 清单里是 __PG_NODE__ 占位符，apply 时才由 sed 替换，所以静态能查的是「有没有
# 钉」而不是「钉在哪」。替换成 dev1 会把数据库 I/O 放回控制面节点 —— 那正是
# 2026-08-04 把 kine 拖垮、两台节点一起 NotReady 的成因，务必替换成 dev2。
pg_node=$(
  awk '$1 == "kubernetes.io/hostname:" { print $2; exit }' \
    "$TEST_ROOT/deploy/k8s/lumora-postgres.yaml"
)
[[ -n "$pg_node" ]] \
  && ok "PostgreSQL 通过 nodeSelector 钉死在节点上（占位符 ${pg_node}）" \
  || no "lumora-postgres.yaml 没有 nodeSelector —— hostPath 一旦漂移就是空库"

# dev2 物理内存 1870Mi，上面跑 web/worker 两个 JVM 加集群内 PostgreSQL。limits
# 合计一旦明显超过物理内存，三个容器同时冲顶就会惊动内核 OOM killer —— 它不看
# limits 挑谁杀，很可能连 PostgreSQL 一起带走。2026-08-08/09 两次断站都是节点级
# OOM kill：被杀掉的容器 RSS 远低于自己的 cgroup limit，是别的容器把节点撑爆的，
# 单看某个容器自己的 limit 发现不了这类问题，必须守住合计。收紧后最坏是单个
# Pod 被 OOMKill 再拉起。
mem_budget=1152
mem_limits_total=$(
  awk '
    /^ *limits:/    { in_limits = 1; next }
    /^ *requests:/  { in_limits = 0 }
    in_limits && $1 == "memory:" {
      v = $2
      if (v ~ /Gi$/) { sub(/Gi$/, "", v); total += v * 1024 }
      else           { sub(/Mi$/, "", v); total += v }
      in_limits = 0
    }
    END { print total + 0 }
  ' "$TEST_ROOT/deploy/k8s/lumora-backend.yaml" "$TEST_ROOT/deploy/k8s/lumora-postgres.yaml"
)
(( mem_limits_total <= mem_budget )) \
  && ok "dev2 上三个容器 limits 合计 ${mem_limits_total}Mi，未超出 ${mem_budget}Mi 预算" \
  || no "limits 合计 ${mem_limits_total}Mi 超出 ${mem_budget}Mi（节点只有 1870Mi），内核 OOM 风险"

service_links_disabled=$(
  grep -h -c "enableServiceLinks: false" \
    "$TEST_ROOT/deploy/k8s/lumora-backend.yaml" \
    "$TEST_ROOT/deploy/k8s/lumora-backend-migrate.yaml" \
    | awk '{ total += $1 } END { print total + 0 }'
)
[[ "$service_links_disabled" == "3" ]] \
  && ok "三种后端 Pod 都禁用 Kubernetes Service 环境变量注入" \
  || no "只有 ${service_links_disabled} 个后端 Pod 禁用 Service Links，预期为 3"

printf '\n--- 正常发布路径 ---\n'
status=$(run_deploy happy)
[[ "$status" == "0" ]] && ok "正常路径退出码 0" || no "正常路径退出码 $status"

before "verify-packaging" "scp" "校验镜像早于分发到服务器"
before "lumora-migrate" "lumora-backend.yaml" "迁移早于应用清单"
before "wait --for=condition=complete" "rollout status" "等迁移完成早于等滚动更新"
before "create secret generic lumora-env" "lumora-migrate" "Secret 早于迁移（迁移要读库凭据）"
before "schema-smoke" "kubectl apply -f /tmp/lumora-backend.yaml" "schema-smoke 早于切流量"

grep -q -- "--platform linux/amd64" "$CALLS" \
  && ok "按 linux/amd64 构建（本地可能是 arm64）" \
  || no "构建没有指定 platform"

grep -q "k3s ctr images import" "$CALLS" \
  && ok "镜像通过 ctr import 分发（两台机器连不上 Docker Hub）" \
  || no "没有走 ctr import"

imports=$(grep -c "k3s ctr images import" "$CALLS")
[[ "$imports" == "1" ]] \
  && ok "镜像只导入真正跑后端 Pod 的节点" \
  || no "导入了 ${imports} 次，预期 1 次（只有 dev2）"

# 2026-08-04 17:22 的事故：往控制面导 147MB 镜像，解包跟 kine(SQLite) 抢盘，
# INSERT 慢到 3.5s → apiserver i/o timeout → node lease 续不上 → dev1 NotReady
# → kubelet 重启 Traefik → 整站断。dev1 上根本不跑后端 Pod，这份 I/O 是白付的。
if grep "ctr images import" "$CALLS" | grep -q "^ssh dev1 "; then
  no "镜像被导入了控制面 dev1，会跟 kine 抢盘（重演 2026-08-04 的 NotReady）"
else
  ok "控制面 dev1 完全不接触后端镜像"
fi

if grep "ctr images import" "$CALLS" | grep -q "ionice -c3 nice -n19"; then
  ok "镜像解包降到 idle I/O + 最低 CPU 优先级"
else
  no "ctr import 没有 ionice/nice 兜底，磁盘一忙就可能拖垮 kine"
fi

grep -q "from-env-file" "$CALLS" \
  && ok "Secret 从服务器上的 .env 生成，值不经过本地" \
  || no "Secret 生成方式不对"

env_checks=$(grep -c "test -f '/opt/lumora/backend/.env'" "$CALLS")
[[ "$env_checks" == "1" ]] && grep -q "ssh dev1 test -f" "$CALLS" \
  && ok "只在控制节点检查 Secret 源文件" \
  || no "应只检查 dev1 上的 Secret 源文件，实际检查 ${env_checks} 次"

if grep -q "sudo /usr/local/bin/k3s" "$CALLS"; then
  ok "sudo 使用 k3s 绝对路径（服务器 secure_path 不含 /usr/local/bin）"
else
  no "sudo 没有使用 /usr/local/bin/k3s"
fi

if grep "schema-smoke" "$CALLS" | grep -q '"enableServiceLinks":false'; then
  ok "临时 schema-smoke Pod 禁用 Service Links"
else
  no "临时 schema-smoke Pod 未禁用 Service Links"
fi

# 不钉节点的话调度器可能把它放到没有镜像的 dev1，结果是 ImagePullBackOff，
# 而不是「候选镜像读不了迁移后的库」——错误信息会指向完全错误的方向。
if grep "schema-smoke" "$CALLS" | grep -q '"nodeSelector":{"kubernetes.io/hostname":"dev2"}'; then
  ok "临时 schema-smoke Pod 钉在有镜像的 dev2"
else
  no "schema-smoke Pod 没钉到 dev2，可能调度到没有镜像的节点"
fi

printf '\n--- 不泄密 ---\n'
if grep -q "do-not-print-me" "$WORK/stdout.log" "$WORK/stderr.log" "$CALLS"; then
  no "输出里出现了 .env 的内容"
else
  ok ".env 的内容没有出现在任何输出或命令行里"
fi

printf '\n--- 公网面 ---\n'
# 探测地址已从裸 IP 换成域名（Ingress 按 host 匹配），这里只认路径不认主机。
if grep -q "curl.*/internal/" "$CALLS"; then
  ok "验证阶段探测了公网对 /internal/ 的可达性"
else
  no "没有验证 /internal/ 在公网不可达"
fi

printf '\n--- 拒绝发布的情况 ---\n'
status=$(run_deploy dirty)
[[ "$status" != "0" ]] && ok "工作区脏时拒绝发布" || no "工作区脏时仍然发布了"
if grep -q "ctr images import\|kubectl apply" "$CALLS"; then
  no "拒绝发布前已经产生了变更"
else
  ok "拒绝发布时没有产生任何变更"
fi

printf '\n--- 汇总 ---\n'
printf '%d 通过, %d 失败\n' "$pass" "$fail"
(( fail == 0 ))
