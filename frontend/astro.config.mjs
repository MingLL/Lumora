import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';
// 不写进 package.json：它是 astro 自己的依赖，由 astro 锁版本。显式声明反而可能
// 装出第二个版本，让这里 import 到的 unified() 和 astro 实际用的对不上。
import { unified } from '@astrojs/markdown-remark';
import rehypeImageDimensions from './scripts/rehype-image-dimensions.mjs';

export default defineConfig({
  // canonical、sitemap、RSS、og:image 的绝对地址均由此生成。
  site: 'https://lumora.love',
  integrations: [mdx(), sitemap()],
  vite: {
    plugins: [tailwindcss()]
  },
  markdown: {
    shikiConfig: {
      theme: 'github-light'
    },
    // 给正文图片补上真实宽高与模糊占位图，消除加载时的版位跳动。
    // MDX 默认继承这份 markdown 配置，所以 .mdx 也一并生效。
    //
    // 走 processor 而不是已废弃的 markdown.rehypePlugins（Astro 7 会告警，将来会删）。
    // 注意这会把处理器从默认的 satteri() 换成 remark/rehype 的 unified 管线 —— 换的是
    // 引擎不是键名，所以这次迁移是对着 dist/ 逐字节 diff 验证过产出没变才留下的。
    processor: unified({ rehypePlugins: [rehypeImageDimensions] })
  }
});
