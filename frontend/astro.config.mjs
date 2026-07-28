import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  // canonical、sitemap、RSS、og:image 的绝对地址均由此生成。
  // 域名到位后改成 https://你的域名，然后重新构建部署即可。
  site: 'http://47.120.54.233',
  integrations: [mdx(), sitemap()],
  vite: {
    plugins: [tailwindcss()]
  },
  markdown: {
    shikiConfig: {
      theme: 'github-light'
    }
  }
});
