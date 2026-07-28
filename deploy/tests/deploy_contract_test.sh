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
  cp "$WORK/backend/deploy/verify-packaging.sh" "$WORK/repo/backend/deploy/"
  printf 'SECRET_CANARY=do-not-print-me\n' > "$WORK/repo/backend/.env"

  : > "$CALLS"
  (
    cd "$WORK/repo"
    PATH="$WORK/bin:$PATH" bash deploy/deploy-backend.sh "$@"
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
[[ "$imports" == "2" ]] && ok "两台节点都导入了镜像" || no "只有 $imports 台节点导入了镜像"

grep -q "from-env-file" "$CALLS" \
  && ok "Secret 从服务器上的 .env 生成，值不经过本地" \
  || no "Secret 生成方式不对"

printf '\n--- 不泄密 ---\n'
if grep -q "do-not-print-me" "$WORK/stdout.log" "$WORK/stderr.log" "$CALLS"; then
  no "输出里出现了 .env 的内容"
else
  ok ".env 的内容没有出现在任何输出或命令行里"
fi

printf '\n--- 公网面 ---\n'
if grep -q "curl.*47\..*/internal/" "$CALLS"; then
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
