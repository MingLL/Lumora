#!/usr/bin/env bash
#
# 准备文章内容：克隆私有内容仓库并挂回站点里的原位。
#
#   ./scripts/setup-content.sh
#
# 文章正文与配图不在这个公开仓库里，而在私有的 MingLL/lumora-content。
# 本脚本把它克隆到 content/，再用两条符号链接挂回 Astro 期望的位置，
# 这样 content.config.ts、deploy.sh 和文章里的 /images/... 绝对路径都不用改。
# 可重复执行：已经就位的部分会跳过。
set -euo pipefail

CONTENT_REPO=git@github.com:MingLL/lumora-content.git
CONTENT_DIR=content

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

step "内容仓库"
if [[ -d $CONTENT_DIR/.git ]]; then
  printf '    %s/ 已存在，拉取更新\n' "$CONTENT_DIR"
  git -C "$CONTENT_DIR" pull --ff-only
else
  git clone "$CONTENT_REPO" "$CONTENT_DIR"
fi

# $1 = 链接路径，$2 = 相对目标（从链接所在目录解析）
link() {
  local path=$1 target=$2
  mkdir -p "$(dirname "$path")"          # 目录可能不存在：内容被剥离后 git 不保留空目录
  if [[ -L $path ]]; then
    [[ $(readlink "$path") == "$target" ]] || fail "$path 已是符号链接但指向别处：$(readlink "$path")"
    printf '    %s → %s（已就位）\n' "$path" "$target"
    return
  fi
  [[ -e $path ]] && fail "$path 已存在且不是符号链接，请先手动处理"
  ln -s "$target" "$path"
  printf '    %s → %s\n' "$path" "$target"
}

step "挂载符号链接"
link frontend/src/content/blog ../../../content/blog
link frontend/public/images    ../../content/images

step "校验"
md=$(find -L frontend/src/content/blog -name '*.md' | wc -l | tr -d ' ')
img=$(find -L frontend/public/images -type f | wc -l | tr -d ' ')
[[ $md -gt 0 ]] || fail "frontend/src/content/blog 下没有找到文章"
[[ $img -gt 0 ]] || fail "frontend/public/images 下没有找到图片"
printf '    文章 %s 篇，图片 %s 个\n' "$md" "$img"

printf '\n\033[1;32m内容就位，可以 npm run dev 了。\033[0m\n'
