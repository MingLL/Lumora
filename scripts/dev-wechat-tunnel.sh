#!/usr/bin/env bash
#
# 微信公众号本地联调隧道：把本机后端回调端口暴露成公网 HTTPS 地址。
#
#   ./scripts/dev-wechat-tunnel.sh [port]
#
# 默认指向 127.0.0.1:8080（compose 的 web 容器或本机直跑的 jar）。
# 用微信公众号「测试号」时，把这个脚本打印的 https://xxx.trycloudflare.com
# 填进测试号后台的 URL，路径补 /wechat/callback/{你的 AppId}。
#
# 依赖 cloudflared（免费、无需注册）：
#   macOS:  brew install cloudflared
#   Linux:  参见 https://pkg.cloudflare.com
#
# 这个脚本是开发联调用，不要用于生产。隧道地址每次启动都会变；
# 想固定地址请用 cloudflared 的 named tunnel（需 Cloudflare 账号）。
set -euo pipefail

PORT="${1:-8080}"
TARGET="http://127.0.0.1:${PORT}"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

if ! command -v cloudflared >/dev/null 2>&1; then
  fail "未找到 cloudflared。macOS 可执行：brew install cloudflared"
fi

step "检查本机后端"
if ! curl -fsS "${TARGET}/actuator/health/liveness" >/dev/null 2>&1; then
  fail "在 ${TARGET} 没探测到后端健康检查。先启动后端：docker compose up -d web（或本机跑 jar）"
fi

step "启动隧道（指向 ${TARGET}）"
printf '    Ctrl-C 退出，退出后回调地址立即失效。\n\n'

# trycloudflare 无需登录，直接出一个临时 HTTPS 域名。
# 把输出同时打印到终端和捕获，从中解析分配的公网域名。
cloudflared tunnel --url "${TARGET}" 2>&1 | while IFS= read -r line; do
  printf '%s\n' "$line"
  if [[ $line == *"trycloudflare.com"* && -z ${PRINTED+x} ]]; then
    # 行内形如： https://some-words-xxxx.trycloudflare.com
    host=$(printf '%s' "$line" | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | head -1)
    if [[ -n $host ]]; then
      export PRINTED=1
      printf '\n\033[1;32m== 公网回调地址就绪 ==\033[0m\n'
      printf '    隧道:  %s\n' "$host"
      printf '    回调:  %s/wechat/callback/{你的AppId}\n\n'
      printf '把上面的「回调」填进微信公众号测试号后台的 URL。\n'
      printf '明文模式只填 Token；安全模式还要填 EncodingAESKey。\n\n'
    fi
  fi
done
