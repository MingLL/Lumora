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
TABLES = [
    ("wechat_event", ["anomalous_timestamp", "ticket_present"]),
    ("daily_report", []),
    ("report_delivery_attempt", []),
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

    # 校验：逐表比对行数，不一致就整体回滚已经来不及，所以只报错让人工介入。
    with my.cursor() as mc, pg.cursor() as pc:
        for table, _ in TABLES:
            mc.execute(f"SELECT COUNT(*) AS n FROM {table}")
            source = mc.fetchone()["n"]
            pc.execute(f"SELECT COUNT(*) FROM {table}")
            target = pc.fetchone()[0]
            status = "ok" if source == target else "MISMATCH"
            print(f"verify {table}: mysql={source} postgres={target} {status}")
            if source != target:
                sys.exit(1)

    print("migration complete")


if __name__ == "__main__":
    main()
