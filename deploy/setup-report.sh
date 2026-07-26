#!/usr/bin/env bash
#
# 在 dev1 上安装每日访问日报：脚本 + 目录 + 凭证模板 + cron。
#
#   ./deploy/setup-report.sh
#
# 反复执行是安全的：已存在的凭证文件不会被覆盖。
set -euo pipefail

HOST=dev1
BIN_DIR=/opt/lumora/bin
ENV_FILE=/etc/lumora/report.env
LOG_DIR=/var/log/lumora
STATS_DIR=/var/lib/lumora/stats

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

step "创建目录"
ssh "$HOST" "mkdir -p '$BIN_DIR' '$LOG_DIR' '$STATS_DIR' /etc/lumora && chmod 700 /etc/lumora"

step "上传脚本"
scp -q scripts/daily-report.py "$HOST:$BIN_DIR/daily-report.py"
ssh "$HOST" "chmod 755 '$BIN_DIR/daily-report.py'"

step "凭证文件"
# 里面是邮箱授权码，只有 root 可读，且永远不进仓库
if ssh "$HOST" "test -f $ENV_FILE"; then
  echo "    已存在，保留不动：$ENV_FILE"
else
  # 本地写好再传，避免 heredoc 嵌在 ssh 的引号里被二次解析
  tmp=$(mktemp)
  cat > "$tmp" <<'ENVTEMPLATE'
# 每日访问日报的发信配置。
#
# QQ 邮箱授权码申请：登录 mail.qq.com → 设置 → 账户 →
# 开启「IMAP/SMTP 服务」→ 生成授权码。填在下面 SMTP_PASS，
# 注意那是授权码，不是 QQ 登录密码。
#
# 阿里云 ECS 封禁 25 端口出站，所以这里必须用 465（SSL）或 587（STARTTLS）。

SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_USER=你的QQ号@qq.com
SMTP_PASS=在这里填授权码
MAIL_FROM=你的QQ号@qq.com
MAIL_TO=ambition1314@icloud.com
ENVTEMPLATE
  scp -q "$tmp" "$HOST:$ENV_FILE"
  ssh "$HOST" "chmod 600 $ENV_FILE"
  rm -f "$tmp"
  echo "    已创建模板：$ENV_FILE（需要填写授权码）"
fi

step "注册 cron"
# cron 的 PATH 很窄，不显式声明的话找不到 k3s，任务会静默失败
ssh "$HOST" bash -s <<'REMOTE'
set -euo pipefail
TMP=$(mktemp)
crontab -l 2>/dev/null | grep -v 'daily-report.py' | grep -v '^PATH=/usr/local/bin:/usr/bin:/bin$' > "$TMP" || true
cat >> "$TMP" <<'CRON'
PATH=/usr/local/bin:/usr/bin:/bin
# 每小时增量归档访问日志（pod 重启会清空容器日志，所以要及时落盘）
5 * * * * /opt/lumora/bin/daily-report.py collect >> /var/log/lumora/cron.log 2>&1
# 每天早上 7:00 统计前一天并发邮件
0 7 * * * /opt/lumora/bin/daily-report.py report >> /var/log/lumora/cron.log 2>&1
CRON
crontab "$TMP"
rm -f "$TMP"
echo "    已注册："
crontab -l | grep -E 'daily-report|^PATH' | sed 's/^/      /'
REMOTE

step "立即收集一次日志"
ssh "$HOST" "$BIN_DIR/daily-report.py collect" 2>&1 | sed 's/^/    /'

printf '\n\033[1;32m安装完成。\033[0m\n\n'
cat <<EOF
还差一步 —— 填写发信凭证：

    ssh $HOST
    vi $ENV_FILE          # 填 SMTP_USER 和 SMTP_PASS（QQ 邮箱授权码）

填好后自检：

    ssh $HOST '$BIN_DIR/daily-report.py test-mail'     # 发一封测试信
    ssh $HOST '$BIN_DIR/daily-report.py report --dry-run'  # 只生成不发送

之后每天 07:00 自动发送，日志在 $LOG_DIR/cron.log。
EOF
