#!/usr/bin/env python3
"""把 lumora 的业务数据从 MySQL 搬到 PostgreSQL。一次性脚本，2026-08-09 切库用。

前置：两条 port-forward 各自开着（见 deploy/README.md「切到 PostgreSQL」一节），
且 PG 上已经跑完 Flyway 迁移（表结构已就位，且是空的）。

    pip install pymysql psycopg2-binary
    python3 deploy/migrate-mysql-to-postgres.py

三件容易出错、这里显式处理掉的事：
  1. auto_report_id 是 PG 的生成列，不能出现在 INSERT 列名里，否则报错。
  2. MySQL 的 BOOLEAN 实为 TINYINT(1)，读出来是 0/1，PG 的 BOOLEAN 不接受整数。
  3. IDENTITY 序列不会因为显式插入主键而前进，搬完必须 setval，否则下一次
     插入就撞主键冲突。
"""
import os
import sys

import pymysql
import psycopg2
import psycopg2.extras

# 先父后子：report_delivery_attempt 有指向 daily_report 的外键。
#
# 这份清单必须和 Flyway 迁移建出来的业务表一一对应。jsapi_signature_error 是
# V3 加的，而本脚本写在 V3 合并进来之前 —— 漏掉它不会报错，只会静默少搬 2 行，
# 属于最难发现的那类。新增迁移时记得回来加。
TABLES = [
    ("wechat_event", ["anomalous_timestamp", "ticket_present"]),
    ("daily_report", []),
    ("report_delivery_attempt", []),
    ("jsapi_signature_error", []),
]
GENERATED_COLUMNS = {"auto_report_id"}


def env(name, default=None):
    value = os.environ.get(name, default)
    if value is None:
        sys.exit(f"missing env var: {name}")
    return value


def main():
    my = pymysql.connect(
        host=env("MY_HOST", "127.0.0.1"), port=int(env("MY_PORT", "3306")),
        user=env("MY_USER"), password=env("MY_PASSWORD"), database=env("MY_DATABASE"),
        cursorclass=pymysql.cursors.DictCursor,
    )
    pg = psycopg2.connect(
        host=env("PG_HOST", "127.0.0.1"), port=int(env("PG_PORT", "5432")),
        user=env("PG_USER"), password=env("PG_PASSWORD"), dbname=env("PG_DATABASE"),
    )
    pg.autocommit = False

    with my.cursor() as mc, pg.cursor() as pc:
        for table, bool_columns in TABLES:
            mc.execute(f"SELECT * FROM {table} ORDER BY id")
            rows = mc.fetchall()
            if not rows:
                print(f"{table}: 0 rows, skipped")
                continue

            columns = [c for c in rows[0].keys() if c not in GENERATED_COLUMNS]
            for row in rows:
                for column in bool_columns:
                    row[column] = bool(row[column])

            values = [tuple(row[c] for c in columns) for row in rows]
            placeholders = ",".join(["%s"] * len(columns))
            psycopg2.extras.execute_batch(
                pc,
                f'INSERT INTO {table} ({",".join(columns)}) VALUES ({placeholders})',
                values,
            )

            # 序列名由 PG 自动生成：<表名>_<列名>_seq。
            pc.execute(
                f"SELECT setval(pg_get_serial_sequence('{table}', 'id'), "
                f"(SELECT MAX(id) FROM {table}))"
            )
            print(f"{table}: {len(rows)} rows copied, sequence reset")

    pg.commit()

    # 校验跑在 commit 之后，所以这里发现问题已经回滚不掉了 —— 数据是活的。
    # 这跟「插入阶段失败」是完全不同的处境，退出信息必须让人一眼看出区别，
    # 否则运维在停机窗口里的第一反应会是「重跑一遍」，而重跑必然撞主键冲突。
    mismatched = []
    with my.cursor() as mc, pg.cursor() as pc:
        for table, _ in TABLES:
            mc.execute(f"SELECT COUNT(*) AS n FROM {table}")
            source = mc.fetchone()["n"]
            pc.execute(f"SELECT COUNT(*) FROM {table}")
            target = pc.fetchone()[0]
            status = "ok" if source == target else "MISMATCH"
            print(f"verify {table}: mysql={source} postgres={target} {status}")
            if source != target:
                mismatched.append((table, source, target))

    if mismatched:
        print(
            "\n数据已经提交到 PostgreSQL，不是「什么都没发生」——不要直接重跑。\n"
            "行数对不上最常见的原因是搬运期间 MySQL 仍在被写入，也就是应用没有真正停干净。\n"
            "重跑前必须先按外键反序清空目标库，否则会撞一片主键冲突：\n"
            "  TRUNCATE report_delivery_attempt, daily_report, wechat_event RESTART IDENTITY CASCADE;\n"
            "对不上的表：" + ", ".join(f"{t}(mysql={s} pg={d})" for t, s, d in mismatched),
            file=sys.stderr,
        )
        sys.exit(2)

    print("migration complete")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:  # noqa: BLE001 - 一次性脚本，这里只负责把处境说清楚
        # 插入阶段的异常发生在 pg.commit() 之前，事务从未提交，连接销毁时
        # PostgreSQL 会整体回滚。目标库仍是空的，直接修掉原因重跑即可。
        print(f"\n迁移失败：{exception}", file=sys.stderr)
        print(
            "失败发生在提交之前，PostgreSQL 已整体回滚，目标库没有留下任何半截数据。\n"
            "修掉原因后可以直接重跑，不需要先清空。",
            file=sys.stderr,
        )
        sys.exit(1)
