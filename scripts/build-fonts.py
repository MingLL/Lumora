#!/usr/bin/env python3
"""
按站点实际用到的字符子集化字体，供自托管使用。

为什么要自托管：Google Fonts 在国内不可达（域名被 DNS 污染到本地地址后，
Chrome 的 Private Network Access 策略还会把它当成内网请求直接拦掉），
字体加载必然失败。所以把字体跟站点一起发。

为什么要子集化：中文字库完整包每个字重 1.4MB，三个字重就是 4MB+。
站点实际只用到 2000 来个汉字，子集化后能压到每字重几百 KB。

用法:
    python3 scripts/build-fonts.py

新增文章后跑一次，把 public/fonts/ 和 src/styles/fonts.css 的产物一起提交。
脚本会报告哪些字符在字体里找不到（缺字会自动回退到系统字体，不会显示成方框）。
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "src"
CACHE = REPO / ".font-cache"
OUT_DIR = REPO / "public" / "fonts"
CSS_OUT = SRC / "styles" / "fonts.css"
# 供 SiteLayout 生成 preload 标签 —— 文件名带 hash，不能在模板里写死
MANIFEST_OUT = SRC / "data" / "fonts.json"

FONTSOURCE_VERSION = "5.3.0"
UNPKG = "https://unpkg.com/@fontsource/{pkg}@{ver}/files/{file}"

# 从源码里收集字符的文件类型
SCAN_SUFFIXES = {".md", ".mdx", ".astro", ".ts", ".tsx", ".json"}

# 这两个是本脚本自己的产物。不排除的话会自我引用 —— 生成的 CSS 里那几行中文注释
# 会被下一次扫描收进字符集，导致每跑一次 hash 都在变，永远收敛不了。
GENERATED = {CSS_OUT.resolve(), MANIFEST_OUT.resolve()}

# 注释里的中文永远不会显示出来，收进去纯属浪费，还会让"改个注释"变成"字体 hash 变化、
# 缓存全部失效"。行尾 // 注释要避开 URL 里的 //，所以要求前面不是冒号。
COMMENT_PATTERNS = [
    re.compile(r"/\*.*?\*/", re.S),        # 块注释，同时覆盖 .astro 的 {/* */}
    re.compile(r"<!--.*?-->", re.S),       # HTML 注释
    re.compile(r"(?<!:)//[^\n]*"),         # 行注释，但不误伤 https://
]


class Font:
    def __init__(self, family, pkg, weight, subset_name, stem, scope="full", preload=False):
        self.family = family
        self.pkg = pkg
        self.weight = weight
        self.subset_name = subset_name
        self.stem = stem
        # 正文字重值得抢跑，标题用的字重晚一点到不影响首屏
        self.preload = preload
        # 带内容 hash 的最终文件名，子集化后回填
        self.out_name = f"{stem}.woff2"
        # full  = 正文字体，需要全站字符
        # hand  = 装饰手写体，只用在几句固定文案上，带全字库纯属浪费
        # latin = 等宽字体，只承担代码和数字，不需要汉字
        self.scope = scope

    @property
    def source_file(self) -> str:
        return f"{self.pkg}-{self.subset_name}-{self.weight}-normal.woff2"

    @property
    def url(self) -> str:
        return UNPKG.format(pkg=self.pkg, ver=FONTSOURCE_VERSION, file=self.source_file)


# chinese-simplified 分片已包含全部 95 个 ASCII 字形，所以中文字体不必再合并 latin 分片
FONTS = [
    Font("Noto Serif SC", "noto-serif-sc", 400, "chinese-simplified", "noto-serif-sc-400", preload=True),
    Font("Noto Serif SC", "noto-serif-sc", 600, "chinese-simplified", "noto-serif-sc-600"),
    Font("Noto Serif SC", "noto-serif-sc", 900, "chinese-simplified", "noto-serif-sc-900"),
    Font("Ma Shan Zheng", "ma-shan-zheng", 400, "chinese-simplified", "ma-shan-zheng-400", scope="hand"),
    Font("IBM Plex Mono", "ibm-plex-mono", 400, "latin", "ibm-plex-mono-400", scope="latin"),
    Font("IBM Plex Mono", "ibm-plex-mono", 500, "latin", "ibm-plex-mono-500", scope="latin"),
]

# 带这些 class 的元素会用手写体渲染
HAND_CLASS_RE = re.compile(
    r'<([a-z0-9]+)[^>]*class="[^"]*(?:font-hand|hand-note)[^"]*"[^>]*>(.*?)</\1>',
    re.S | re.I,
)


def log(msg: str = "") -> None:
    print(msg, flush=True)


def collect_chars() -> tuple[set[str], set[str]]:
    """从源码收集字符，返回 (站点实际用到的, 加上保底范围后的全集)。

    两者要分开：子集化用全集（宁可多带几个字形，体积代价很小），
    但缺字告警只看实际用到的 —— 否则保底范围里的生僻符号会刷屏，把真问题淹没。
    """
    used: set[str] = set()
    scanned = 0
    for path in SRC.rglob("*"):
        if not path.is_file() or path.suffix not in SCAN_SUFFIXES:
            continue
        if path.resolve() in GENERATED:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        # markdown 是纯内容，正文里出现的 // 和 /* 应当保留
        if path.suffix not in {".md", ".mdx"}:
            for pattern in COMMENT_PATTERNS:
                text = pattern.sub(" ", text)
        used.update(text)
        scanned += 1
    used = {c for c in used if c.isprintable() and not c.isspace()}

    padded = set(used)
    padded.update(chr(c) for c in range(0x20, 0x7F))          # ASCII 可打印全集
    padded.update(chr(c) for c in range(0x3000, 0x3040))      # CJK 标点（。、《》等）
    padded.update(chr(c) for c in range(0xFF00, 0xFF61))      # 全角 ASCII
    padded.update("℃×÷—…‘’“”·•°±≈≤≥←→↑↓※§¶")
    padded = {c for c in padded if c.isprintable() and not c.isspace()}

    log(f"  扫描 {scanned} 个源文件：实际用到 {len(used)} 个字符，加保底后 {len(padded)} 个")
    return used, padded


def collect_hand_chars() -> set[str] | None:
    """从构建产物里挑出真正用手写体渲染的文字。

    手写体只用在几句固定文案上（作者名、首页题词），带整个中文字库要 800KB+，
    而实际只需要十几个字。这个只能从渲染结果看得准，所以依赖 dist/。
    """
    dist = REPO / "dist"
    if not dist.exists():
        return None

    chars: set[str] = set()
    for path in dist.rglob("*.html"):
        html = path.read_text(encoding="utf-8", errors="ignore")
        for _tag, inner in HAND_CLASS_RE.findall(html):
            text = re.sub(r"<[^>]+>", "", inner)
            chars.update(text)
    chars = {c for c in chars if c.isprintable() and not c.isspace()}
    return chars or None


def download(font: Font) -> Path:
    CACHE.mkdir(exist_ok=True)
    dst = CACHE / font.source_file
    if dst.exists() and dst.stat().st_size > 0:
        return dst
    log(f"  下载 {font.source_file} ...")
    # unpkg 是当前网络环境下少数可达的 npm CDN（GitHub / jsDelivr 都不通），
    # 但连接不太稳定，中文字库又是 1.4MB 起步，所以必须重试。
    for attempt in range(1, 6):
        result = subprocess.run(
            [
                "curl", "-fL", "--silent", "--show-error",
                "--connect-timeout", "20",
                "--max-time", "600",
                # 速度低于 1KB/s 持续 30 秒就判定卡死，重来一次比干等有用
                "--speed-limit", "1024", "--speed-time", "30",
                "-o", str(dst), font.url,
            ],
            capture_output=True,
        )
        if result.returncode == 0 and dst.exists() and dst.stat().st_size > 0:
            return dst
        dst.unlink(missing_ok=True)
        if attempt < 5:
            log(f"    第 {attempt} 次失败，重试 ...")

    raise SystemExit(
        f"下载失败（重试 5 次）: {font.url}\n"
        f"{result.stderr.decode(errors='ignore')}\n"
        f"网络到 unpkg 不稳定，可以稍后重跑脚本，已下好的文件会复用缓存。"
    )


def subset(font: Font, src_path: Path, target: set[str], used: set[str]) -> tuple[int, list[str]]:
    """子集化，返回 (输出字节数, 站点实际用到但字体没有的字符)。"""
    from fontTools.ttLib import TTFont
    from fontTools import subset as ft_subset

    available = set(TTFont(str(src_path)).getBestCmap().keys())

    keep = {c for c in target if ord(c) in available}
    # 只有"站点真的要显示、而字体给不出字形"才算缺字，保底范围缺了无所谓
    missing = sorted(c for c in (target & used) if ord(c) not in available)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"{font.stem}.woff2"

    with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8", delete=False) as tf:
        tf.write("".join(sorted(keep)))
        text_file = tf.name

    try:
        ft_subset.main([
            str(src_path),
            f"--output-file={out_path}",
            "--flavor=woff2",
            f"--text-file={text_file}",
            "--layout-features=*",      # 保留 OpenType 特性（标点压缩等中文排版需要）
            "--no-hinting",             # woff2 下 hinting 收益有限，去掉省体积
            "--desubroutinize",
        ])
    finally:
        Path(text_file).unlink(missing_ok=True)

    # 文件名带内容 hash：字体可以按 immutable 永久缓存，重新生成时 URL 自动变，
    # 老访客立刻拿到新文件，不用等缓存过期。
    data = out_path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()[:8]
    final_path = OUT_DIR / f"{font.stem}.{digest}.woff2"
    out_path.replace(final_path)
    font.out_name = final_path.name

    return len(data), missing


def write_manifest(fonts: list[Font]) -> None:
    preload = [f"/fonts/{f.out_name}" for f in fonts if f.preload]
    MANIFEST_OUT.write_text(
        json.dumps({"preload": preload}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_css(results: list[tuple[Font, int]]) -> None:
    lines = [
        "/*",
        " * 自托管字体 —— 由 scripts/build-fonts.py 生成，请勿手改。",
        " *",
        " * Google Fonts 在国内不可达，字体改为跟站点一起发布。",
        " * 这里的文件是按站点实际用到的字符子集化过的，新增文章后重新跑一次脚本。",
        " */",
        "",
    ]
    for font, size in results:
        lines += [
            "@font-face {",
            f"  font-family: '{font.family}';",
            "  font-style: normal;",
            f"  font-weight: {font.weight};",
            # swap：字体没到位时先用系统字体渲染，避免首屏空白
            "  font-display: swap;",
            f"  src: url('/fonts/{font.out_name}') format('woff2');",
            "}",
            "",
        ]
    CSS_OUT.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    if not shutil.which("curl"):
        raise SystemExit("需要 curl")
    try:
        import fontTools  # noqa: F401
        import brotli     # noqa: F401  woff2 的压缩后端，缺了会在写文件时才报错
    except ImportError as exc:
        raise SystemExit(
            f"缺少依赖: {exc.name}\n"
            f"安装: python3 -m pip install --user fonttools brotli\n"
            f"（注意用的是哪个 python —— pyenv 环境下 npm 脚本里的 python3 可能不是你以为的那个）"
        )

    log("收集站点字符")
    used, padded = collect_chars()

    # 手写体只用在几句固定文案上，带全字库要 800KB，实际只需要十几个字。
    # 但"哪些字用手写体"只能从渲染结果反推，而正则解析 HTML 本身就不牢靠，
    # 所以再拿 site.ts 的全部字符兜底 —— 站点文案都在那儿，多带两百个字也才几十 KB，
    # 换来的是改文案后即使忘了重跑脚本也不会掉字。
    hand = collect_hand_chars() or set()
    site_data = SRC / "data" / "site.ts"
    fallback: set[str] = set()
    if site_data.exists():
        text = site_data.read_text(encoding="utf-8", errors="ignore")
        for pattern in COMMENT_PATTERNS:
            text = pattern.sub(" ", text)
        fallback = {c for c in text if c.isprintable() and not c.isspace()}

    hand_target = hand | fallback | set("，。！？、；：“”‘’…—0123456789")
    if hand:
        log(f"  手写体渲染的文字：{''.join(sorted(hand))}（另按 site.ts 兜底 {len(fallback)} 个字符）")
    else:
        log(f"  ⚠️  没找到 dist/，手写体仅按 site.ts 的 {len(fallback)} 个字符处理")
        log("      先跑 npm run build 再执行本脚本，结果更准")

    scopes = {
        "full": padded,
        "hand": hand_target,
        "latin": {c for c in padded if ord(c) < 0x0250},
    }

    # 文件名带 hash，旧产物不会被同名覆盖，先清干净免得越攒越多
    if OUT_DIR.exists():
        for stale in OUT_DIR.glob("*.woff2"):
            stale.unlink()

    log("\n处理字体")
    results: list[tuple[Font, int]] = []
    all_missing: dict[str, list[str]] = {}
    total_before = total_after = 0

    for font in FONTS:
        src_path = download(font)
        before = src_path.stat().st_size
        after, missing = subset(font, src_path, scopes[font.scope], used)
        total_before += before
        total_after += after
        results.append((font, after))
        if missing:
            all_missing[f"{font.family} {font.weight}"] = missing
        log(f"  {font.out_name:<26} {before/1024:>8.0f}KB → {after/1024:>6.0f}KB")

    write_css(results)
    write_manifest(FONTS)

    log(f"\n合计 {total_before/1024/1024:.1f}MB → {total_after/1024:.0f}KB "
        f"（省了 {100 * (1 - total_after / total_before):.0f}%）")
    log(f"字体文件: {OUT_DIR.relative_to(REPO)}/")
    log(f"样式文件: {CSS_OUT.relative_to(REPO)}")
    log(f"预加载清单: {MANIFEST_OUT.relative_to(REPO)}")

    if all_missing:
        log("\n以下字符站点用到了、但字体里没有字形，会回退到系统字体：")
        for name, missing in all_missing.items():
            shown = "".join(missing[:40])
            more = f" …等 {len(missing)} 个" if len(missing) > 40 else ""
            log(f"  {name}: {shown}{more}")
    else:
        log("\n站点用到的字符全部有字形，没有缺字。")


if __name__ == "__main__":
    main()
