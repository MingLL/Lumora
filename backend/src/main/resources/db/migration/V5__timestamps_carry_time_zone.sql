-- 全部时间列 TIMESTAMP(6) → TIMESTAMPTZ(6)。
--
-- 起因：库里查出来的时间比北京时间少 8 小时。数值本身没错（存的是 UTC），但追下去
-- 发现「统一按 UTC 存取」这个不变量实际由两个互不相干的开关决定，而只钉住了一个：
--
--   1. 数据库默认值和 lumora_set_updated_at() 触发器里的 CURRENT_TIMESTAMP，按
--      「会话时区」折算成 timestamp。会话时区由 application.yml 的
--      connection-init-sql 钉成 UTC。
--   2. 应用经 MyBatis InstantTypeHandler 写入的列，走
--      ps.setTimestamp(i, Timestamp.from(instant)) —— 不带 Calendar，pgjdbc 于是用
--      「JVM 默认时区」把它渲染成本地墙钟字符串。会话时区对这条路完全无效，
--      Clock.system(Asia/Shanghai) 那个 Bean 也无关（clock.instant() 与时区无关）。
--
-- 两条路此前一致，纯粹是因为 eclipse-temurin 基础镜像默认时区恰好是 UTC，而
-- Dockerfile / compose / k8s manifest / JAVA_OPTS 里都没有设过 TZ。谁给容器加一个
-- TZ=Asia/Shanghai（想让日志显示北京时间，很自然的举动），就会同时踩到：同一行里
-- received_at 与 created_at 差 8 小时；EventRetentionMapper 的 cutoff 与 UTC 存的
-- 列比较，多删 8 小时数据；日报窗口口径整体偏移。
--
-- timestamptz 把偏移量存进值里，pgjdbc 两个方向都按绝对时刻处理，JVM 时区就此不再
-- 参与正确性判定。这是根治，不是把 TZ 钉死那种「靠约定维持」的补丁。
--
-- 存量数据的换算前提：现有值全部是 UTC 墙钟。生产库成立（web/worker/migrate 三个
-- 容器都是 UTC，两条写入路径落的都是 UTC）。若某个开发库是本机 JVM（TZ=Asia/Shanghai）
-- 直接写出来的，那里应用写的那几列本来就是北京墙钟，这个迁移会把它们再当成 UTC 读，
-- 结果偏 8 小时 —— 开发库数据可弃，重建即可，不为此在迁移里加分支。
--
-- USING 子句显式写死 'UTC'，不依赖执行迁移时的会话时区：migrate 容器的 Flyway 用的是
-- 自建 DriverManager 数据源，走不到 Hikari 的 connection-init-sql，会话时区取决于
-- pgjdbc 上报的 JVM 默认时区 —— 正是本次要消除的那个变量，不能拿它当前提。
--
-- ALTER COLUMN ... TYPE 的附带影响已在一次性 PG 17 容器里实测：DEFAULT CURRENT_TIMESTAMP
-- 原样保留（不会被套上多余的 cast），NOT NULL 保留，列上的索引自动重建，
-- 触发器继续生效。表会被整体重写并持有 ACCESS EXCLUSIVE 锁，当前数据量下可忽略。

ALTER TABLE wechat_event
    ALTER COLUMN original_occurred_at  TYPE TIMESTAMPTZ(6) USING original_occurred_at  AT TIME ZONE 'UTC',
    ALTER COLUMN effective_occurred_at TYPE TIMESTAMPTZ(6) USING effective_occurred_at AT TIME ZONE 'UTC',
    ALTER COLUMN received_at           TYPE TIMESTAMPTZ(6) USING received_at           AT TIME ZONE 'UTC',
    ALTER COLUMN created_at            TYPE TIMESTAMPTZ(6) USING created_at            AT TIME ZONE 'UTC';

ALTER TABLE daily_report
    ALTER COLUMN window_start   TYPE TIMESTAMPTZ(6) USING window_start   AT TIME ZONE 'UTC',
    ALTER COLUMN window_end     TYPE TIMESTAMPTZ(6) USING window_end     AT TIME ZONE 'UTC',
    ALTER COLUMN data_cutoff_at TYPE TIMESTAMPTZ(6) USING data_cutoff_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ(6) USING created_at     AT TIME ZONE 'UTC';

ALTER TABLE report_delivery_attempt
    ALTER COLUMN claimed_at   TYPE TIMESTAMPTZ(6) USING claimed_at   AT TIME ZONE 'UTC',
    ALTER COLUMN lease_until  TYPE TIMESTAMPTZ(6) USING lease_until  AT TIME ZONE 'UTC',
    ALTER COLUMN completed_at TYPE TIMESTAMPTZ(6) USING completed_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at   TYPE TIMESTAMPTZ(6) USING created_at   AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at   TYPE TIMESTAMPTZ(6) USING updated_at   AT TIME ZONE 'UTC';

ALTER TABLE jsapi_signature_error
    ALTER COLUMN received_at TYPE TIMESTAMPTZ(6) USING received_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ(6) USING created_at  AT TIME ZONE 'UTC';

ALTER TABLE client_event
    ALTER COLUMN received_at TYPE TIMESTAMPTZ(6) USING received_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ(6) USING created_at  AT TIME ZONE 'UTC';
