import sharp from 'sharp';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// 图片放在 public/images（符号链接到内容仓库），不经 Astro 的资源管线，
// 所以拿不到 astro:assets 会自动写入的宽高。这里在构建期直接读文件：
//
//   width/height —— 让浏览器在图片下载完之前就算得出宽高比、提前留好版位。
//                    没有它，正文的裸 <img> 只能预留 0，图一到就把后面的内容顶下去。
//   lqip         —— 20px 宽的缩略图，内联成 data URI 垫在 img 背景上。
//                    放大到全宽后本身就是模糊的，不需要 CSS filter，也不需要 JS 摘除：
//                    真图是不透明照片，加载完直接盖住。
//
// 站点 CSP 是 `img-src 'self' data:` 加 `style-src 'unsafe-inline'`，两者都放行。

// 两条候选路径都要留着：rehype 插件是 Node 直接加载的，import.meta.url 就是源码位置；
// 而 .astro 组件里的这份会被 Vite 打包，import.meta.url 指向产物、算出来的目录并不存在。
// 取第一个真实存在的（astro build 的 cwd 就是项目根，也就是 frontend/）。
const PUBLIC_DIR =
  [
    fileURLToPath(new URL('../../public', import.meta.url)),
    path.join(process.cwd(), 'public')
  ].find((dir) => existsSync(dir)) ?? path.join(process.cwd(), 'public');

// 同一张图会被多个页面引用（首页网格、归档、文章页），按 src 缓存，一次构建只读一次。
const cache = new Map();

/**
 * @param {string} src 站内绝对路径，如 `/images/posts/street-2022-07-18/01.webp`
 * @returns {Promise<{width: number, height: number, lqip: string} | null>}
 *          读不到文件时返回 null —— 没跑 setup-content.sh 时构建仍应能跑完，
 *          缺图只是退回原来的无占位行为，不该整个构建失败。
 */
export async function getImageMeta(src) {
  if (typeof src !== 'string' || !src.startsWith('/')) return null;
  if (cache.has(src)) return cache.get(src);

  const promise = read(src);
  cache.set(src, promise);
  return promise;
}

async function read(src) {
  // 去掉查询串，并挡住 `/images/../../etc` 这类跳出 public/ 的路径
  const clean = src.split(/[?#]/)[0];
  const file = path.join(PUBLIC_DIR, decodeURIComponent(clean));
  if (!file.startsWith(PUBLIC_DIR + path.sep) || !existsSync(file)) return null;

  try {
    const image = sharp(file);
    const { width, height } = await image.metadata();
    if (!width || !height) return null;

    const thumb = await sharp(file)
      .resize(20, null, { fit: 'inside' })
      .webp({ quality: 40 })
      .toBuffer();

    return {
      width,
      height,
      lqip: `data:image/webp;base64,${thumb.toString('base64')}`
    };
  } catch {
    return null;
  }
}

/** 拼给 img 用的背景样式；meta 为 null 时返回 undefined，调用处直接透传即可。 */
export function lqipStyle(meta) {
  if (!meta) return undefined;
  return `background-image:url(${meta.lqip});background-size:cover;background-repeat:no-repeat`;
}

/** 单张图的占位样式；没填 src 或读不到文件时返回 undefined。 */
export async function coverPlaceholder(src) {
  if (!src) return undefined;
  return lqipStyle(await getImageMeta(src));
}

/**
 * 批量取一组文章封面的占位样式，返回 `id → style` 的 Map。
 * 没填 cover、或文件读不到的条目不会进 Map —— 调用处拿到 undefined，
 * 直接透传给 `style` 属性即可，Astro 会把它整个省略掉。
 *
 * @param {Array<{id: string, data: {cover?: string}}>} entries
 * @returns {Promise<Map<string, string>>}
 */
export async function coverPlaceholders(entries) {
  const pairs = await Promise.all(
    entries
      .filter((entry) => entry.data?.cover)
      .map(async (entry) => [entry.id, lqipStyle(await getImageMeta(entry.data.cover))])
  );
  return new Map(pairs.filter(([, style]) => style));
}
