import { visit } from 'unist-util-visit';
import { getImageMeta, lqipStyle } from '../src/lib/image-meta.mjs';

// 正文里的图没有 width/height，也吃不到模板上那些 Tailwind 的 aspect-ratio 类。
// 浏览器因此在图下载完之前只能预留 0 高度，图一到就把后面的正文整段顶下去 ——
// 页面肉眼可见地跳。这里在构建期把真实宽高和模糊占位图写进标签。
//
// 不用改 CSS：global.css:57 的 `img { max-width:100%; height:auto }` 已经是配套的另一半，
// 有了它，width/height 属性只作为宽高比提示，图片照样响应式缩放、不会被拉变形。
//
// 要处理两种形态：
//   1. Markdown 的 ![]() 语法 —— 在 hast 里是正常的 element 节点。
//   2. 从公众号导入的正文用的 <figure><img>（55 篇文章的主要形态）—— 裸 HTML 在 hast 里
//      是未解析的 raw 字符串，element 遍历完全看不见，只能在字符串上改写。
// 改这个插件之后要先 `rm -rf node_modules/.astro`：渲染好的正文 HTML 缓存在
// node_modules/.astro/data-store.json 里，只按内容文件变化失效，不认插件变化 ——
// 不清缓存的话改了也看不出任何效果，很容易误判成插件没生效。
const IMG_TAG = /<img\b[^>]*>/gi;
const SRC_ATTR = /\ssrc=["']([^"']+)["']/i;

export default function rehypeImageDimensions() {
  return async (tree) => {
    const jobs = [];

    visit(tree, (node) => {
      if (node.type === 'element' && node.tagName === 'img') {
        jobs.push(decorateElement(node));
      } else if (node.type === 'raw' && typeof node.value === 'string' && node.value.includes('<img')) {
        jobs.push(decorateRaw(node));
      }
    });

    await Promise.all(jobs);
  };
}

async function decorateElement(node) {
  const src = node.properties?.src;
  // 只处理站内图片；外链和 data: 读不到本地文件
  if (typeof src !== 'string' || !src.startsWith('/')) return;
  // 模板已经显式给过尺寸的不覆盖
  if (node.properties.width && node.properties.height) return;

  const meta = await getImageMeta(src);
  if (!meta) return;

  node.properties.width = meta.width;
  node.properties.height = meta.height;
  node.properties.decoding = 'async';
  if (!node.properties.style) node.properties.style = lqipStyle(meta);
}

async function decorateRaw(node) {
  const tags = node.value.match(IMG_TAG) ?? [];
  const rewritten = new Map();

  await Promise.all(
    [...new Set(tags)].map(async (tag) => {
      const next = await decorateTag(tag);
      if (next !== tag) rewritten.set(tag, next);
    })
  );

  if (rewritten.size === 0) return;
  node.value = node.value.replace(IMG_TAG, (tag) => rewritten.get(tag) ?? tag);
}

async function decorateTag(tag) {
  if (/\swidth=/i.test(tag) && /\sheight=/i.test(tag)) return tag;

  const src = tag.match(SRC_ATTR)?.[1];
  if (!src || !src.startsWith('/')) return tag;

  const meta = await getImageMeta(src);
  if (!meta) return tag;

  // base64 只含 [A-Za-z0-9+/=]，放进双引号属性里不需要转义
  const style = /\sstyle=/i.test(tag) ? '' : ` style="${lqipStyle(meta)}"`;
  const attrs = ` width="${meta.width}" height="${meta.height}" decoding="async"${style}`;

  // 结尾可能是 `>` 也可能是 `/>`，保持原样
  return tag.replace(/\s*(\/?)>$/, (_, slash) => `${attrs}${slash ? ' />' : '>'}`);
}
