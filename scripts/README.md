# scripts/

仓库根的运维与分析脚本。它们不属于前端或后端的构建产物，都在本地或 dev1 上手工/定时执行。

| 脚本 | 用途 | 详细说明 |
|---|---|---|
| `setup-content.sh` | 克隆私有内容仓库并建立两条符号链接 | [根 README](../README.md#文章内容不在这个仓库) |
| `daily-report.py` | 站点访问日报（cron 在 dev1 上跑） | [deploy/README.md](../deploy/README.md#每日访问日报) |
| `dev-wechat-original-id.py`、`dev-wechat-tunnel.sh` | 微信公众号本地联调助手 | [backend/README.md](../backend/README.md#local-wechat-integration) |
| `build_ip_access_report.py`、`filter_attack_urls.py`、`build_attack_report_docx.py` | 访问日志安全分析三件套 | 下文 |
| `tests/test_daily_report.py` | `daily-report.py` 的单元测试（unittest，26 条，无外部依赖） | `python3 scripts/tests/test_daily_report.py` |

## 访问日志安全分析

一次性的取证/复盘工具，不在任何定时任务里，也不参与发布。三个脚本串成一条流水线，
中间产物是 xlsx，最终产物是 docx：

```text
访问日志归档  ──build_ip_access_report.py──→  全量.xlsx
全量.xlsx     ──filter_attack_urls.py─────→  攻击.xlsx
攻击.xlsx + 全量.xlsx ──build_attack_report_docx.py──→ 报告.docx
```

（第三步两份都要：攻击面明细来自攻击.xlsx，IP 归属地来自全量.xlsx 的「IP汇总」表。）

```bash
python3 scripts/build_ip_access_report.py /var/log/lumora out/full.xlsx \
        --geo-cache out/geo-cache.json [--mmdb GeoLite2-City.mmdb] [--count-only]
python3 scripts/filter_attack_urls.py out/full.xlsx out/attack.xlsx
python3 scripts/build_attack_report_docx.py out/attack.xlsx out/full.xlsx out/report.docx
```

依赖 `openpyxl`（前两个）、`python-docx`（第三个）、`maxminddb`（仅 `--mmdb` 时），
都不在仓库的依赖清单里，用之前自行 `pip install`。

**`build_ip_access_report.py`** 读一个目录下的两种日志并去重合并：`access-20*.log`
是 nginx 旧格式归档（由 `daily-report.py collect` 落盘），`access.log*` 是 Traefik 的
JSON 格式。**两边的时间边界写死在代码里**（旧格式只取 2026-08-03 之前，避免与
Traefik 日志重复计数）—— 换时间窗要改 `parse_logs` 里的 `cutoff`。输出的 xlsx 有
概览、IP汇总、IP与链接、链接汇总、请求明细五张表。

> ⚠️ **归属地查询会把日志里的全部公网 IP 发给第三方。** 默认走
> `http://ip-api.com/batch`，**明文 HTTP**，每批 100 个，没有条数上限 —— 这一点和
> `daily-report.py` 不同，后者用 HTTPS 且每份日报最多披露 3 个未缓存 IP。要避免外发就
> 传 `--mmdb` 走本地 MaxMind 库离线查询。结果缓存在 `--geo-cache` 指定的 JSON 文件里，
> 该文件含完整 IP 与归属地，注意权限和留存。`--count-only` 只打印请求数/独立 IP 数/
> 时间范围就退出，不做任何查询，适合先摸清数据量。

**`filter_attack_urls.py`** 读上一步的「请求明细」表，用 `RULES` 里的正则给 URL 打
攻击分类和置信度（命令注入、路径穿越、敏感文件探测等），输出概览、攻击链接汇总、
攻击请求明细。规则是基于 URL 特征的启发式：高置信度通常带明确利用载荷，中置信度
可能只是扫描器或误报，结论要结合状态码复核。

**`build_attack_report_docx.py`** 把两份 xlsx 渲染成 Word 报告。**报告标题和分析时间
范围写死在代码里**（当前是 dev1、2026-07-27 至 2026-08-14），复用时要改 `main()` 里
对应的字符串。
