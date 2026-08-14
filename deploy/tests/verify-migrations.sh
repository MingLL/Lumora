#!/usr/bin/env bash
#
# 在 k3s 里起一个一次性 PostgreSQL，把全部迁移按版本号真跑一遍，验证 DDL 成立。
# 本机 Docker 停着，所以借集群验证。跑完自动删干净，不碰 lumora 命名空间。
#
#   bash verify-ddl.sh /path/to/Lumora-postgres
set -euo pipefail

REPO="${1:?用法: verify-ddl.sh <worktree 路径>}"
MIG="$REPO/backend/src/main/resources/db/migration"
POD=lumora-ddl-check
K=/usr/local/bin/k3s
IMAGE=public.ecr.aws/docker/library/postgres:17-alpine

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m失败:\033[0m %s\n' "$*" >&2; exit 1; }

# 按版本号排序取全部迁移，不写死文件名 —— 写死的话每新增一个迁移就漏一个，
# 而漏掉的恰恰是最新、最没被验证过的那个。sort -V 让 V10 排在 V9 之后而不是 V1 之后。
MIGRATIONS=()
while IFS= read -r file; do MIGRATIONS+=("$file"); done < <(ls "$MIG"/V*.sql 2>/dev/null | sort -V)
(( ${#MIGRATIONS[@]} )) || fail "在 $MIG 下找不到任何迁移"

cleanup() {
  ssh -o BatchMode=yes dev1 "sudo $K kubectl delete pod $POD --ignore-not-found --force --grace-period=0" >/dev/null 2>&1 || true
}
trap cleanup EXIT

step "起一次性 PostgreSQL（dev2，default 命名空间，不碰 lumora）"
ssh -o BatchMode=yes dev1 "sudo $K kubectl delete pod $POD --ignore-not-found >/dev/null 2>&1; \
  sudo $K kubectl run $POD --image=$IMAGE --restart=Never \
    --env=POSTGRES_PASSWORD=ddlcheck --env=POSTGRES_DB=lumora --env=POSTGRES_USER=lumora \
    --overrides='{\"spec\":{\"nodeSelector\":{\"kubernetes.io/hostname\":\"dev2\"}}}'"

ssh -o BatchMode=yes dev1 "sudo $K kubectl wait --for=condition=ready pod/$POD --timeout=120s"

# initdb 之后 postgres 还会重启一次，pg_isready 才是真正可用的信号
ssh -o BatchMode=yes dev1 "for i in \$(seq 30); do \
  sudo $K kubectl exec $POD -- pg_isready -q -U lumora && exit 0; sleep 2; done; exit 1" \
  || fail "PostgreSQL 起不来"

step "依次应用 ${#MIGRATIONS[@]} 个迁移"
for migration in "${MIGRATIONS[@]}"; do
  name="${migration##*/}"
  printf '    %s ' "$name"
  ssh -o BatchMode=yes dev1 "sudo $K kubectl exec -i $POD -- psql -v ON_ERROR_STOP=1 -U lumora -d lumora" \
    < "$migration" >/dev/null || fail "$name 执行失败"
  printf '✓\n'
done

step "核对结果"
ssh -o BatchMode=yes dev1 "sudo $K kubectl exec -i $POD -- psql -U lumora -d lumora -X" <<'SQL'
\echo '--- 表 ---'
SELECT table_name FROM information_schema.tables
 WHERE table_schema='public' ORDER BY table_name;

\echo '--- 索引 ---'
SELECT indexname FROM pg_indexes WHERE schemaname='public' ORDER BY indexname;

\echo '--- id 是否为 IDENTITY（应为 BY DEFAULT）---'
SELECT table_name, is_identity, identity_generation
  FROM information_schema.columns
 WHERE table_schema='public' AND column_name='id' ORDER BY table_name;

\echo '--- 生成列 auto_report_id ---'
SELECT column_name, is_generated, generation_expression
  FROM information_schema.columns
 WHERE table_name='report_delivery_attempt' AND column_name='auto_report_id';

\echo '--- 触发器 ---'
SELECT trigger_name, event_manipulation, action_timing
  FROM information_schema.triggers WHERE trigger_schema='public';

\echo '--- raw_event_key 是否已被 V2 扩到 2048 ---'
SELECT character_maximum_length FROM information_schema.columns
 WHERE table_name='wechat_event' AND column_name='raw_event_key';

\echo '--- JSONB 列 ---'
SELECT table_name, column_name, data_type FROM information_schema.columns
 WHERE data_type='jsonb' ORDER BY table_name;
SQL

step "行为验证：触发器真的会刷新 updated_at，生成列真的按 trigger_type 取值"
ssh -o BatchMode=yes dev1 "sudo $K kubectl exec -i $POD -- psql -v ON_ERROR_STOP=1 -U lumora -d lumora -X" <<'SQL'
INSERT INTO daily_report (report_date, version, window_start, window_end, data_cutoff_at, snapshot_json)
VALUES ('2026-08-09', 1, now(), now(), now(), '{}'::jsonb);

INSERT INTO report_delivery_attempt (delivery_id, report_id, trigger_type, status, recipient_masked, recipient_sha256)
VALUES ('11111111-1111-1111-1111-111111111111', 1, 'AUTO', 'PENDING', 'a***@b.com', repeat('a',64));

INSERT INTO report_delivery_attempt (delivery_id, report_id, request_id, trigger_type, status, recipient_masked, recipient_sha256)
VALUES ('22222222-2222-2222-2222-222222222222', 1, 'req-1', 'MANUAL', 'PENDING', 'a***@b.com', repeat('b',64));

\echo '--- auto_report_id：AUTO 行应为 1，MANUAL 行应为 NULL ---'
SELECT trigger_type, auto_report_id FROM report_delivery_attempt ORDER BY id;

\echo '--- 触发器：UPDATE 后 updated_at 必须 > created_at ---'
SELECT pg_sleep(1);
UPDATE report_delivery_attempt SET status='SENT' WHERE trigger_type='AUTO';
SELECT status, (updated_at > created_at) AS updated_at_moved FROM report_delivery_attempt WHERE trigger_type='AUTO';

\echo '--- 显式插入主键后 setval 能否让序列继续（数据迁移依赖这个）---'
INSERT INTO wechat_event (id, app_id, open_id, event_type, raw_msg_type, original_occurred_at,
  effective_occurred_at, received_at, deduplication_key, safe_summary, normalized_message_sha256)
VALUES (500, 'wx', 'o1', 'subscribe', 'event', now(), now(), now(), 'dedup-500', '{}'::jsonb, repeat('d',64));
SELECT setval(pg_get_serial_sequence('wechat_event','id'), (SELECT MAX(id) FROM wechat_event));
INSERT INTO wechat_event (app_id, open_id, event_type, raw_msg_type, original_occurred_at,
  effective_occurred_at, received_at, deduplication_key, safe_summary, normalized_message_sha256)
VALUES ('wx', 'o2', 'subscribe', 'event', now(), now(), now(), 'dedup-501', '{}'::jsonb, repeat('e',64));
SELECT id, deduplication_key FROM wechat_event ORDER BY id;
SQL

step "负向验证：第二条 AUTO 行必须被 uq_auto_report 拦住（这里期望看到报错）"
# 故意不加 ON_ERROR_STOP：这条 INSERT 报错才是正确结果。
if ssh -o BatchMode=yes dev1 "sudo $K kubectl exec -i $POD -- psql -U lumora -d lumora -X" <<'SQL' 2>&1 | grep -q "uq_auto_report"
INSERT INTO report_delivery_attempt (delivery_id, report_id, trigger_type, status, recipient_masked, recipient_sha256)
VALUES ('33333333-3333-3333-3333-333333333333', 1, 'AUTO', 'PENDING', 'a***@b.com', repeat('c',64));
SQL
then
  printf '    重复 AUTO 行被 uq_auto_report 拒绝 ✓\n'
else
  fail "第二条 AUTO 行竟然插进去了 —— 生成列或唯一约束没生效"
fi

step "负向验证：JSONB 列不接受 varchar 绑定（证明 ::jsonb 必须加）"
if ssh -o BatchMode=yes dev1 "sudo $K kubectl exec -i $POD -- psql -U lumora -d lumora -X" <<'SQL' 2>&1 | grep -q "is of type jsonb"
PREPARE p (varchar) AS INSERT INTO daily_report (report_date, version, window_start, window_end, data_cutoff_at, snapshot_json)
  VALUES ('2026-08-10', 1, now(), now(), now(), $1);
EXECUTE p('{}');
SQL
then
  printf '    varchar → jsonb 被拒绝 ✓（mapper 必须显式 ::jsonb）\n'
else
  fail "varchar 竟然能直接写进 jsonb —— 与此前实测不符，需重新确认"
fi

printf '\n\033[1;32mDDL 验证通过。\033[0m\n'
