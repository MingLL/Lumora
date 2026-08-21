#!/usr/bin/env bash
#
# 只读安全巡检：汇总 dev1 上 Lumora 访问归档中的漏洞扫描来源，并检查 SSH 认证事件。
#
# 用法：
#   ./deploy/audit-high-risk-ips.sh
#   ./deploy/audit-high-risk-ips.sh --days 30
#   ./deploy/audit-high-risk-ips.sh --host dev1 --days 14
#
# 不会写日志、修改防火墙或封禁 IP。封禁需要人工核对后另行操作。
set -euo pipefail

HOST=dev1
DAYS=7

usage() {
  sed -n '2,11p' "$0"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)
      [[ $# -ge 2 ]] || { echo "错误：--host 缺少值" >&2; exit 2; }
      HOST=$2
      shift 2
      ;;
    --days)
      [[ $# -ge 2 && $2 =~ ^[1-9][0-9]*$ ]] || { echo "错误：--days 必须是正整数" >&2; exit 2; }
      DAYS=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "错误：未知参数 $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

ssh -o BatchMode=yes "$HOST" bash -s -- "$DAYS" <<'REMOTE'
set -euo pipefail

days=$1
log_dir=/var/log/lumora
logs=$(find "$log_dir" -maxdepth 1 -type f -name 'access-*.log' -mtime "-$days" -print | sort)

if [[ -z "$logs" ]]; then
  echo "错误：最近 $days 天没有找到 $log_dir/access-*.log" >&2
  exit 1
fi

echo "巡检时间（UTC）：$(date -u '+%F %T')"
echo "日志范围：最近 $days 天，$(printf '%s\n' "$logs" | wc -l | tr -d ' ') 个归档文件"

echo
echo '== 高风险 Web 路径扫描（按来源 IP） =='
# nginx 格式见 deploy/k8s/lumora.yaml：$2 是带引号的 X-Forwarded-For，$6 是路径，$8 是状态码。
awk '
function client_ip(value, parts) {
  gsub(/"/, "", value)
  split(value, parts, ",")
  return parts[1]
}
function sensitive(path) {
  return path ~ /(^|\/)(\.env|\.git|wp-admin|wp-login|xmlrpc\.php|phpmyadmin|pma|cgi-bin|actuator|internal|admin|login|config|vendor|\.aws|server-status|HNAP1|boaform|solr|shell|passwd)(\/|$|\?|\.)/ || path ~ /\.(php|asp|aspx|jsp)(\?|$)/
}
sensitive($6) {
  ip = client_ip($2)
  hits[ip]++
  paths[ip SUBSEP $6] = 1
}
END {
  for (ip in hits) {
    unique = 0
    for (entry in paths) {
      split(entry, pair, SUBSEP)
      if (pair[1] == ip) unique++
    }
    print hits[ip] "\t" unique "\t" ip
  }
}' $logs | sort -rn -k1,1 | head -30 | awk -F '\t' '{printf "%-16s %5d 请求  %4d 个敏感路径\n", $3, $1, $2}'

echo
echo '== 敏感路径的非 4xx 响应 =='
non_4xx=$(awk '
function client_ip(value, parts) { gsub(/"/, "", value); split(value, parts, ","); return parts[1] }
function sensitive(path) { return path ~ /(^|\/)(\.env|\.git|wp-admin|wp-login|xmlrpc\.php|phpmyadmin|pma|cgi-bin|actuator|internal|admin|login|config|vendor|\.aws|server-status|HNAP1|boaform|solr|shell|passwd)(\/|$|\?|\.)/ || path ~ /\.(php|asp|aspx|jsp)(\?|$)/ }
sensitive($6) && $8 !~ /^4/ { print client_ip($2), $8, $6 }
' $logs | sort -u)
if [[ -n "$non_4xx" ]]; then
  printf '%s\n' "$non_4xx"
else
  echo '无（所有命中的敏感路径均返回 4xx）'
fi

echo
echo '== SSH 失败或无效用户（按来源 IP） =='
failed=$(journalctl --since "$days days ago" --no-pager 2>/dev/null \
  | grep -E 'sshd.*(Failed password|Invalid user)' \
  | sed -nE 's/.* from ([0-9a-fA-F:.]+) port.*/\1/p' \
  | sort | uniq -c | sort -rn || true)
if [[ -n "$failed" ]]; then
  printf '%s\n' "$failed"
else
  echo '无'
fi

echo
echo '== SSH 已接受公钥（按来源 IP，需确认均为已知设备） =='
accepted=$(journalctl --since "$days days ago" --no-pager 2>/dev/null \
  | grep 'sshd.*Accepted publickey' \
  | sed -nE 's/.* from ([0-9a-fA-F:.]+) port.*/\1/p' \
  | sort | uniq -c | sort -rn || true)
if [[ -n "$accepted" ]]; then
  printf '%s\n' "$accepted"
else
  echo '无'
fi
REMOTE
