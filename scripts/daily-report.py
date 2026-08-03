#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
远方有温度 —— 每日访问日报。

在 dev1（k3s control-plane）上运行。站点的 nginx 跑在两个节点上，日志各写各的，
而 dev1 没法 ssh 到 dev2，所以日志统一通过 `kubectl logs -l app=lumora-web` 收，
它会把两个节点的日志一起拉过来。

两个子命令，分别对应两条 cron：

    daily-report.py collect     每小时跑，把新日志追加到磁盘归档
    daily-report.py report      每天 07:00 跑，统计前一天并发邮件

为什么要分开：kubectl logs 读的是容器的日志文件，pod 一重启就只剩新容器的内容。
每小时增量归档，pod 重启最多影响一小时，而不是整份日报。

    daily-report.py report --dry-run        只生成 HTML，不发信（调试用）
    daily-report.py report --date 2026-07-26  补发指定日期
    daily-report.py test-mail               发一封测试信，验证 SMTP 配置

Python 3.6 兼容（Alibaba Cloud Linux 3 自带的版本），别用 dataclasses / walrus。
"""

from __future__ import print_function

import argparse
import glob
import html
import json
import os
import re
import smtplib
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from email.header import Header
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

# ---------------------------------------------------------------- 配置

NAMESPACE = "lumora"
SELECTOR = "app=lumora-web"
SITE_ROOT = "/opt/lumora/site"          # 静态文件，用来把 URL 反查成文章标题
LOG_DIR = "/var/log/lumora"             # 归档目录
STATE_FILE = os.path.join(LOG_DIR, ".collect-state")
STATS_DIR = "/var/lib/lumora/stats"     # 每日汇总，用于算环比
ENV_FILE = "/etc/lumora/report.env"
KUBECTL = ["k3s", "kubectl"]

CST = timezone(timedelta(hours=8))
ARCHIVE_KEEP_DAYS = 90

SITE_NAME = "远方有温度"

# nginx 的 log_format main，见 deploy/k8s/lumora.yaml
LOG_RE = re.compile(
    r'^(?P<remote>\S+) '
    r'"(?P<xff>[^"]*)" '
    r'\[(?P<time>[^\]]+)\] '
    r'"(?P<request>[^"]*)" '
    r'(?P<status>\d{3}) '
    r'(?P<bytes>\d+) '
    r'"(?P<referer>[^"]*)" '
    r'"(?P<ua>[^"]*)"'
)

BOT_RE = re.compile(
    r'bot|spider|crawler|slurp|bingpreview|facebookexternalhit|headless|'
    r'curl|wget|python-requests|go-http-client|scrapy|semrush|ahrefs|mj12|'
    r'dataprovider|censys|zgrab|masscan|nmap|palo alto|expanse',
    re.I,
)
MOBILE_RE = re.compile(r'iphone|android|ipad|mobile|harmonyos', re.I)

# 认出来是谁在爬。顺序有讲究：具体的排前面，宽泛的兜底。
BOT_NAMES = [
    ("Googlebot", r'googlebot'),
    ("Bingbot", r'bingbot|msnbot'),
    ("百度蜘蛛", r'baiduspider'),
    ("搜狗蜘蛛", r'sogou'),
    ("360 蜘蛛", r'360spider|haosouspider'),
    ("字节跳动", r'bytespider|toutiao'),
    ("Yandex", r'yandex'),
    ("Apple", r'applebot'),
    ("Facebook", r'facebookexternalhit'),
    ("Ahrefs（SEO）", r'ahrefsbot'),
    ("Semrush（SEO）", r'semrushbot'),
    ("MJ12（SEO）", r'mj12bot'),
    ("Censys 扫描器", r'censys'),
    ("ZGrab 扫描器", r'zgrab'),
    ("Expanse 扫描器", r'expanse|palo alto'),
    ("Nmap 扫描器", r'nmap|masscan'),
    ("curl", r'curl'),
    ("wget", r'wget'),
    ("Python 脚本", r'python-requests|python-urllib'),
    ("Go 脚本", r'go-http-client'),
    ("Scrapy", r'scrapy'),
    ("无头浏览器", r'headless'),
]

# 单个 IP 一天内请求超过这个数，即使 UA 伪装成浏览器也值得看一眼
SUSPICIOUS_HITS = 60

SEARCH_ENGINES = [
    ("百度", r'baidu\.'),
    ("Google", r'google\.'),
    ("必应", r'bing\.'),
    ("搜狗", r'sogou\.'),
    ("360", r'so\.com|360\.cn'),
    ("神马", r'sm\.cn'),
    ("头条", r'toutiao\.'),
    ("DuckDuckGo", r'duckduckgo\.'),
]


def log(msg):
    print(msg, file=sys.stderr)


def run(cmd, **kwargs):
    """subprocess.run 在 3.6 上没有 capture_output/text，手动兜住。"""
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs
    )
    out, err = proc.communicate()
    return proc.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


# ---------------------------------------------------------------- 采集

def archive_path(day):
    return os.path.join(LOG_DIR, "access-%s.log" % day.strftime("%Y-%m-%d"))


def read_state():
    try:
        with open(STATE_FILE) as f:
            return f.read().strip()
    except IOError:
        return None


def write_state(value):
    with open(STATE_FILE, "w") as f:
        f.write(value)


def collect():
    """把上次收集之后的新日志追加到当天的归档文件。"""
    os.makedirs(LOG_DIR, exist_ok=True)

    since = read_state()
    cmd = KUBECTL + [
        "logs", "-n", NAMESPACE, "-l", SELECTOR,
        "--tail=-1", "--timestamps=true",
    ]
    if since:
        cmd.append("--since-time=%s" % since)
    else:
        # 首次运行没有基准点，先拿最近一天，别把容器里的全部历史都灌进来
        cmd.append("--since=24h")

    # 记录发起时刻而不是结束时刻：宁可下次重叠几秒（后面会去重），也不要漏
    fetched_at = datetime.now(timezone.utc).replace(microsecond=0)

    code, out, err = run(cmd)
    if code != 0:
        log("kubectl logs 失败: %s" % err.strip())
        return 1

    # --timestamps 会在每行前面加 kubelet 记录的 RFC3339 时间，用完就丢，
    # 统计一律以 nginx 自己写的时间为准。
    lines = []
    for raw in out.splitlines():
        if not raw.strip():
            continue
        parts = raw.split(" ", 1)
        line = parts[1] if len(parts) == 2 and "T" in parts[0] else raw
        if "kube-probe" in line:      # 健康检查，不是访客
            continue
        lines.append(line)

    added = 0
    by_day = defaultdict(list)
    for line in lines:
        entry = parse_line(line)
        if entry is None:
            continue
        by_day[entry["time"].astimezone(CST).date()].append(line)

    for day, day_lines in by_day.items():
        path = os.path.join(LOG_DIR, "access-%s.log" % day.strftime("%Y-%m-%d"))
        existing = set()
        if os.path.exists(path):
            with open(path, "r", errors="replace") as f:
                existing = set(f.read().splitlines())
        fresh = [l for l in day_lines if l not in existing]
        if fresh:
            with open(path, "a") as f:
                f.write("\n".join(fresh) + "\n")
            added += len(fresh)

    write_state(fetched_at.strftime("%Y-%m-%dT%H:%M:%SZ"))
    prune_archives()
    log("收集完成：新增 %d 行" % added)
    return 0


def prune_archives():
    cutoff = datetime.now(CST).date() - timedelta(days=ARCHIVE_KEEP_DAYS)
    for path in glob.glob(os.path.join(LOG_DIR, "access-*.log")):
        name = os.path.basename(path)[len("access-"):-len(".log")]
        try:
            day = datetime.strptime(name, "%Y-%m-%d").date()
        except ValueError:
            continue
        if day < cutoff:
            os.remove(path)


# ---------------------------------------------------------------- 解析

def parse_line(line):
    m = LOG_RE.match(line)
    if not m:
        return None
    try:
        ts = datetime.strptime(m.group("time"), "%d/%b/%Y:%H:%M:%S %z")
    except ValueError:
        return None

    request = m.group("request").split(" ")
    method = request[0] if request else ""
    path = request[1] if len(request) > 1 else ""

    xff = m.group("xff")
    # 经过 Traefik 后 remote_addr 是集群内地址，真实来源在 X-Forwarded-For 的第一段
    if xff and xff != "-":
        ip = xff.split(",")[0].strip()
    else:
        ip = m.group("remote")

    return {
        "ip": ip,
        "time": ts,
        "method": method,
        "path": path.split("?")[0],
        "status": int(m.group("status")),
        "bytes": int(m.group("bytes")),
        "referer": m.group("referer"),
        "ua": m.group("ua"),
    }


def load_day(day):
    """读取某天的归档；没有归档就直接问 kubectl 要（首次运行的兜底）。"""
    path = os.path.join(LOG_DIR, "access-%s.log" % day.strftime("%Y-%m-%d"))
    lines = []
    if os.path.exists(path):
        with open(path, "r", errors="replace") as f:
            lines = f.read().splitlines()
    else:
        code, out, _ = run(KUBECTL + [
            "logs", "-n", NAMESPACE, "-l", SELECTOR, "--tail=-1", "--since=48h",
        ])
        if code == 0:
            lines = [l for l in out.splitlines() if "kube-probe" not in l]

    entries = []
    for line in lines:
        e = parse_line(line)
        if e is None or "kube-probe" in e["ua"]:
            continue
        if e["time"].astimezone(CST).date() != day:
            continue
        entries.append(e)
    return entries


# ---------------------------------------------------------------- 归类

def is_bot(ua):
    return bool(BOT_RE.search(ua)) or not ua or ua == "-"


def bot_name(ua):
    if not ua or ua == "-":
        return "未声明 UA"
    for name, pattern in BOT_NAMES:
        if re.search(pattern, ua, re.I):
            return name
    # 认不出来就把 UA 截一段带上，至少能看出是什么东西在爬
    return (ua[:36] + "…") if len(ua) > 38 else ua


def classify_referer(ref):
    if not ref or ref == "-":
        return ("直接访问", None)
    for name, pattern in SEARCH_ENGINES:
        if re.search(pattern, ref, re.I):
            return ("搜索引擎", name)
    m = re.match(r'https?://([^/]+)', ref)
    host = m.group(1) if m else ref
    if re.search(r'47\.120\.54\.233|47\.120\.64\.186|localhost', host):
        return ("站内跳转", None)
    return ("外部链接", host)


_title_cache = {}


def page_title(path):
    """把 URL 反查成文章标题 —— 服务器上就有构建产物，直接读 <title>。"""
    if path in _title_cache:
        return _title_cache[path]

    rel = path.strip("/")
    candidates = [
        os.path.join(SITE_ROOT, rel, "index.html"),
        os.path.join(SITE_ROOT, rel + ".html"),
    ]
    if not rel:
        candidates.insert(0, os.path.join(SITE_ROOT, "index.html"))

    title = None
    for cand in candidates:
        if os.path.isfile(cand):
            try:
                with open(cand, "r", errors="replace") as f:
                    head = f.read(8192)
                m = re.search(r'<title>(.*?)</title>', head, re.S | re.I)
                if m:
                    title = html.unescape(m.group(1)).strip()
                    # 标题里带了站名后缀，列表里显示纯标题更清爽
                    title = re.sub(r'[｜|]\s*%s\s*$' % re.escape(SITE_NAME), '', title).strip()
                break
            except IOError:
                pass

    _title_cache[path] = title
    return title


# ---------------------------------------------------------------- 统计

def summarize(entries):
    human = [e for e in entries if not is_bot(e["ua"])]
    bots = [e for e in entries if is_bot(e["ua"])]

    pages = [e for e in human if e["status"] in (200, 304)]

    stats = {
        "pv": len(pages),
        "uv": len(set(e["ip"] for e in human)),
        "bot_hits": len(bots),
        "total_hits": len(entries),
        "bytes": sum(e["bytes"] for e in human),
    }

    top = Counter(e["path"] for e in pages).most_common(10)
    stats["top_pages"] = [
        {"path": p, "title": page_title(p), "hits": n,
         "visitors": len(set(e["ip"] for e in pages if e["path"] == p))}
        for p, n in top
    ]

    ref_kinds = Counter()
    ref_detail = Counter()
    for e in pages:
        kind, detail = classify_referer(e["referer"])
        ref_kinds[kind] += 1
        if detail:
            ref_detail[detail] += 1
    stats["referers"] = ref_kinds.most_common()
    stats["referer_detail"] = ref_detail.most_common(5)

    devices = Counter()
    for e in pages:
        devices["手机" if MOBILE_RE.search(e["ua"]) else "电脑"] += 1
    stats["devices"] = devices.most_common()

    hourly = [0] * 24
    for e in pages:
        hourly[e["time"].astimezone(CST).hour] += 1
    stats["hourly"] = hourly

    notfound = Counter(e["path"] for e in entries if e["status"] == 404)
    stats["not_found"] = notfound.most_common(10)
    errors = Counter(
        "%s %d" % (e["path"], e["status"]) for e in entries if e["status"] >= 500
    )
    stats["errors"] = errors.most_common(10)

    stats["visitor_ips"] = len(set(e["ip"] for e in pages))

    # 爬虫明细：按 IP 归并，带上它自称是谁、爬了多少、翻了几个页面
    bot_rows = {}
    for e in bots:
        row = bot_rows.setdefault(
            e["ip"], {"ip": e["ip"], "names": Counter(), "hits": 0, "paths": set(), "codes": Counter()}
        )
        row["hits"] += 1
        row["paths"].add(e["path"])
        row["names"][bot_name(e["ua"])] += 1
        row["codes"][e["status"]] += 1
    bot_list = []
    for r in bot_rows.values():
        name = r["names"].most_common(1)[0][0]
        # 同一个 IP 换着 UA 来，本身就是值得注意的信号，别把它折叠掉
        if len(r["names"]) > 1:
            name = "%s 等 %d 种 UA" % (name, len(r["names"]))
        bot_list.append({
            "ip": r["ip"],
            "name": name,
            "hits": r["hits"],
            "paths": len(r["paths"]),
            "notfound": r["codes"].get(404, 0),
        })
    stats["bots"] = sorted(bot_list, key=lambda r: -r["hits"])[:15]
    stats["bot_ips"] = len(bot_rows)

    # UA 装成浏览器、但请求量高得不像真人的 IP。扫描器通常就藏在这里。
    human_by_ip = Counter(e["ip"] for e in human)
    stats["heavy_ips"] = [
        {"ip": ip, "hits": n, "paths": len(set(e["path"] for e in human if e["ip"] == ip))}
        for ip, n in human_by_ip.most_common(5)
        if n >= SUSPICIOUS_HITS
    ]
    return stats


def load_previous(day):
    path = os.path.join(STATS_DIR, "%s.json" % day.strftime("%Y-%m-%d"))
    try:
        with open(path) as f:
            return json.load(f)
    except (IOError, ValueError):
        return None


def save_stats(day, stats):
    os.makedirs(STATS_DIR, exist_ok=True)
    keep = {k: stats[k] for k in ("pv", "uv", "bot_hits", "bytes")}
    with open(os.path.join(STATS_DIR, "%s.json" % day.strftime("%Y-%m-%d")), "w") as f:
        json.dump(keep, f)


# ---------------------------------------------------------------- 渲染

def delta_badge(now, before):
    if before is None:
        return '<span style="color:#9a9285">—</span>'
    if before == 0:
        return '<span style="color:#3f7d5c">新增</span>' if now else '<span style="color:#9a9285">—</span>'
    pct = (now - before) * 100.0 / before
    if abs(pct) < 0.5:
        return '<span style="color:#9a9285">持平</span>'
    color = "#3f7d5c" if pct > 0 else "#a14b3d"
    arrow = "↑" if pct > 0 else "↓"
    return '<span style="color:%s">%s%.0f%%</span>' % (color, arrow, abs(pct))


def bar(value, peak, width=22):
    if peak <= 0:
        return ""
    filled = int(round(value * width / float(peak)))
    return "█" * filled + "·" * (width - filled)


def human_bytes(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return "%.0f %s" % (n, unit)
        n /= 1024.0
    return "%.1f TB" % n


def render(day, stats, prev):
    e = html.escape
    css_card = "background:#fff;border:1px solid #e8e2d8;border-radius:10px;padding:18px 20px;margin-bottom:16px"
    css_h2 = "margin:0 0 12px;font-size:15px;color:#2f2a24;font-weight:600"
    css_td = "padding:7px 10px;border-bottom:1px solid #f0ebe3;font-size:13px;color:#4a443c"
    css_th = "padding:7px 10px;text-align:left;font-size:12px;color:#9a9285;font-weight:500;border-bottom:1px solid #e8e2d8"

    parts = []
    parts.append(
        '<div style="max-width:660px;margin:0 auto;padding:24px 16px;'
        'font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,\'Helvetica Neue\','
        '\'PingFang SC\',\'Hiragino Sans GB\',\'Microsoft YaHei\',sans-serif;'
        'background:#faf8f4;color:#2f2a24">'
    )
    parts.append(
        '<div style="margin-bottom:20px">'
        '<div style="font-size:12px;color:#9a9285;letter-spacing:.1em">%s · 访问日报</div>'
        '<div style="font-size:22px;font-weight:600;margin-top:4px">%s</div>'
        '</div>' % (e(SITE_NAME), day.strftime("%Y 年 %m 月 %d 日"))
    )

    # 概览
    pv_prev = prev.get("pv") if prev else None
    uv_prev = prev.get("uv") if prev else None
    parts.append('<div style="%s">' % css_card)
    parts.append('<table style="width:100%;border-collapse:collapse"><tr>')
    for label, value, badge in (
        ("浏览量 PV", stats["pv"], delta_badge(stats["pv"], pv_prev)),
        ("访客 UV", stats["uv"], delta_badge(stats["uv"], uv_prev)),
        ("流量", human_bytes(stats["bytes"]), ""),
    ):
        parts.append(
            '<td style="text-align:center;padding:4px">'
            '<div style="font-size:12px;color:#9a9285">%s</div>'
            '<div style="font-size:26px;font-weight:600;margin:4px 0">%s</div>'
            '<div style="font-size:12px">%s</div></td>' % (e(label), value, badge)
        )
    parts.append('</tr></table>')
    if stats["bot_hits"]:
        parts.append(
            '<div style="margin-top:10px;font-size:12px;color:#9a9285;text-align:center">'
            '另有 %d 次爬虫/机器人访问（未计入以上数字）</div>' % stats["bot_hits"]
        )
    parts.append('</div>')

    # 热门页面
    parts.append('<div style="%s"><h2 style="%s">热门页面</h2>' % (css_card, css_h2))
    if stats["top_pages"]:
        parts.append('<table style="width:100%;border-collapse:collapse">')
        parts.append('<tr><th style="%s">页面</th><th style="%s;text-align:right">浏览</th>'
                     '<th style="%s;text-align:right">访客</th></tr>' % (css_th, css_th, css_th))
        for item in stats["top_pages"]:
            label = item["title"] or item["path"]
            sub = ('<div style="font-size:11px;color:#b3aca1;margin-top:2px">%s</div>' % e(item["path"])) \
                if item["title"] else ""
            parts.append(
                '<tr><td style="%s">%s%s</td>'
                '<td style="%s;text-align:right">%d</td>'
                '<td style="%s;text-align:right;color:#9a9285">%d</td></tr>'
                % (css_td, e(label), sub, css_td, item["hits"], css_td, item["visitors"])
            )
        parts.append('</table>')
    else:
        parts.append('<div style="font-size:13px;color:#9a9285">今天没有页面访问。</div>')
    parts.append('</div>')

    # 来源与设备
    parts.append('<div style="%s"><h2 style="%s">访客从哪来</h2>' % (css_card, css_h2))
    if stats["referers"]:
        parts.append('<table style="width:100%;border-collapse:collapse">')
        for kind, count in stats["referers"]:
            pct = count * 100.0 / max(stats["pv"], 1)
            parts.append(
                '<tr><td style="%s">%s</td>'
                '<td style="%s;text-align:right">%d<span style="color:#9a9285"> · %.0f%%</span></td></tr>'
                % (css_td, e(kind), css_td, count, pct)
            )
        parts.append('</table>')
        if stats["referer_detail"]:
            detail = "、".join("%s (%d)" % (e(k), v) for k, v in stats["referer_detail"])
            parts.append('<div style="margin-top:8px;font-size:12px;color:#9a9285">具体来源：%s</div>' % detail)
    if stats["devices"]:
        dev = "　".join("%s %d" % (e(k), v) for k, v in stats["devices"])
        parts.append('<div style="margin-top:12px;padding-top:12px;border-top:1px solid #f0ebe3;'
                     'font-size:13px;color:#4a443c">设备：%s</div>' % dev)
    parts.append('</div>')

    # 时段分布
    peak = max(stats["hourly"]) if stats["hourly"] else 0
    if peak:
        parts.append('<div style="%s"><h2 style="%s">时段分布</h2>' % (css_card, css_h2))
        parts.append('<pre style="margin:0;font-family:SFMono-Regular,Menlo,Consolas,monospace;'
                     'font-size:12px;line-height:1.55;color:#4a443c">')
        for hour, count in enumerate(stats["hourly"]):
            if count or (peak and hour % 3 == 0):
                parts.append('%02d 时 %s %d' % (hour, bar(count, peak), count))
        parts.append('</pre></div>')

    # 爬虫明细
    if stats["bots"]:
        parts.append('<div style="%s"><h2 style="%s">爬虫与机器人</h2>' % (css_card, css_h2))
        parts.append(
            '<div style="font-size:12px;color:#9a9285;margin:-4px 0 10px">'
            '共 %d 个 IP、%d 次请求，均未计入上面的 PV / UV</div>'
            % (stats["bot_ips"], stats["bot_hits"])
        )
        parts.append('<table style="width:100%;border-collapse:collapse">')
        parts.append(
            '<tr><th style="%s">IP</th><th style="%s">来源</th>'
            '<th style="%s;text-align:right">请求</th>'
            '<th style="%s;text-align:right">页面</th></tr>'
            % (css_th, css_th, css_th, css_th)
        )
        for b in stats["bots"]:
            note = ('<span style="color:#a14b3d;font-size:11px"> · %d 个 404</span>' % b["notfound"]) \
                if b["notfound"] else ""
            parts.append(
                '<tr><td style="%s;font-family:SFMono-Regular,Menlo,monospace;font-size:12px">%s</td>'
                '<td style="%s">%s%s</td>'
                '<td style="%s;text-align:right">%d</td>'
                '<td style="%s;text-align:right;color:#9a9285">%d</td></tr>'
                % (css_td, e(b["ip"]), css_td, e(b["name"]), note,
                   css_td, b["hits"], css_td, b["paths"])
            )
        parts.append('</table>')
        if len(stats["bots"]) >= 15 and stats["bot_ips"] > 15:
            parts.append('<div style="margin-top:8px;font-size:12px;color:#9a9285">'
                         '仅显示请求量最高的 15 个，其余 %d 个已略去</div>'
                         % (stats["bot_ips"] - 15))
        parts.append('</div>')

    # UA 看着像真人、但量大得不正常的 IP
    if stats["heavy_ips"]:
        parts.append('<div style="%s"><h2 style="%s">高频访问 IP</h2>' % (css_card, css_h2))
        parts.append('<div style="font-size:12px;color:#9a9285;margin:-4px 0 10px">'
                     'UA 声称是普通浏览器，但请求量不像真人，可能是伪装的爬虫或扫描器</div>')
        parts.append('<table style="width:100%;border-collapse:collapse">')
        for h in stats["heavy_ips"]:
            parts.append(
                '<tr><td style="%s;font-family:SFMono-Regular,Menlo,monospace;font-size:12px">%s</td>'
                '<td style="%s;text-align:right">%d 次请求</td>'
                '<td style="%s;text-align:right;color:#9a9285">%d 个页面</td></tr>'
                % (css_td, e(h["ip"]), css_td, h["hits"], css_td, h["paths"])
            )
        parts.append('</table></div>')

    # 异常
    if stats["not_found"] or stats["errors"]:
        parts.append('<div style="%s"><h2 style="%s">需要留意</h2>' % (css_card, css_h2))
        if stats["not_found"]:
            parts.append('<div style="font-size:13px;color:#4a443c;margin-bottom:6px">404 死链</div>')
            parts.append('<table style="width:100%;border-collapse:collapse">')
            for path, count in stats["not_found"]:
                parts.append('<tr><td style="%s;font-family:monospace;font-size:12px">%s</td>'
                             '<td style="%s;text-align:right">%d</td></tr>'
                             % (css_td, e(path), css_td, count))
            parts.append('</table>')
        if stats["errors"]:
            parts.append('<div style="font-size:13px;color:#a14b3d;margin:12px 0 6px">服务器错误</div>')
            parts.append('<table style="width:100%;border-collapse:collapse">')
            for item, count in stats["errors"]:
                parts.append('<tr><td style="%s;font-family:monospace;font-size:12px">%s</td>'
                             '<td style="%s;text-align:right">%d</td></tr>'
                             % (css_td, e(item), css_td, count))
            parts.append('</table>')
        parts.append('</div>')

    parts.append(
        '<div style="font-size:11px;color:#b3aca1;text-align:center;margin-top:18px;line-height:1.7">'
        'https://lumora.love<br>'
        '由 dev1 上的 daily-report.py 于每日 07:00 生成</div>'
    )
    parts.append('</div>')
    return "\n".join(parts)


# ---------------------------------------------------------------- 发信

def load_env():
    conf = {}
    try:
        with open(ENV_FILE) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                conf[k.strip()] = v.strip().strip('"').strip("'")
    except IOError:
        return None
    return conf


def send_mail(subject, body_html, conf):
    host = conf.get("SMTP_HOST", "smtp.qq.com")
    port = int(conf.get("SMTP_PORT", "465"))
    user = conf.get("SMTP_USER")
    password = conf.get("SMTP_PASS")
    sender = conf.get("MAIL_FROM", user)
    to = [x.strip() for x in conf.get("MAIL_TO", "").split(",") if x.strip()]

    if not (user and password and to):
        raise SystemExit("配置不完整：请检查 %s 里的 SMTP_USER / SMTP_PASS / MAIL_TO" % ENV_FILE)

    msg = MIMEMultipart("alternative")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = "%s <%s>" % (Header(SITE_NAME, "utf-8").encode(), sender)
    msg["To"] = ", ".join(to)
    msg.attach(MIMEText(body_html, "html", "utf-8"))

    # 阿里云 ECS 默认封禁 25 端口出站，必须走 SSL(465) 或 STARTTLS(587)
    if port == 465:
        server = smtplib.SMTP_SSL(host, port, timeout=30)
    else:
        server = smtplib.SMTP(host, port, timeout=30)
        server.starttls()
    try:
        server.login(user, password)
        server.sendmail(sender, to, msg.as_string())
    finally:
        try:
            server.quit()
        except Exception:
            pass


# ---------------------------------------------------------------- 入口

def cmd_report(args):
    if args.date:
        day = datetime.strptime(args.date, "%Y-%m-%d").date()
    else:
        day = (datetime.now(CST) - timedelta(days=1)).date()

    entries = load_day(day)
    stats = summarize(entries)
    prev = load_previous(day - timedelta(days=1))
    body = render(day, stats, prev)

    if args.dry_run:
        out = args.out or "/tmp/lumora-report-%s.html" % day.strftime("%Y-%m-%d")
        with open(out, "w") as f:
            f.write(body)
        log("报告已生成: %s" % out)
        log("PV %d · UV %d · 爬虫 %d · 日志行 %d"
            % (stats["pv"], stats["uv"], stats["bot_hits"], len(entries)))
        return 0

    conf = load_env()
    if conf is None:
        raise SystemExit("找不到配置文件 %s" % ENV_FILE)

    subject = "%s 日报 · %s · PV %d / UV %d" % (
        SITE_NAME, day.strftime("%m-%d"), stats["pv"], stats["uv"])
    send_mail(subject, body, conf)
    save_stats(day, stats)
    log("已发送: %s" % subject)
    return 0


def cmd_test_mail(args):
    conf = load_env()
    if conf is None:
        raise SystemExit("找不到配置文件 %s" % ENV_FILE)
    body = (
        '<div style="font-family:sans-serif;padding:20px">'
        '<h2 style="color:#2f2a24">SMTP 配置正常</h2>'
        '<p style="color:#4a443c">这封信来自 dev1 上的 daily-report.py。'
        '收到它说明发信链路通了，日报会在每天早上 7:00 送达。</p>'
        '<p style="color:#9a9285;font-size:12px">发信时间：%s</p></div>'
        % datetime.now(CST).strftime("%Y-%m-%d %H:%M:%S CST")
    )
    send_mail("%s · 日报配置测试" % SITE_NAME, body, conf)
    log("测试邮件已发送")
    return 0


def main():
    parser = argparse.ArgumentParser(description="站点访问日报")
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("collect", help="增量归档访问日志（每小时）")

    p_report = sub.add_parser("report", help="生成并发送日报（每天 07:00）")
    p_report.add_argument("--date", help="指定日期 YYYY-MM-DD，默认昨天")
    p_report.add_argument("--dry-run", action="store_true", help="只生成 HTML，不发信")
    p_report.add_argument("--out", help="--dry-run 时的输出路径")

    sub.add_parser("test-mail", help="发送一封测试邮件")

    args = parser.parse_args()
    if args.cmd == "collect":
        return collect()
    if args.cmd == "report":
        return cmd_report(args)
    if args.cmd == "test-mail":
        return cmd_test_mail(args)
    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
