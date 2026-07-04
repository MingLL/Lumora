# 远方有温度

一个面向个人公众号内容整理的 Astro 静态网站。主题是「远方有温度」：一个普通人的成长、城市与生活记录。

## 技术栈

- Astro + TypeScript
- Markdown / MDX 内容管理
- Astro Content Collections
- Tailwind CSS
- 静态部署，适合 Vercel / Cloudflare Pages

## 项目结构

```text
src/
  components/        # 站点组件
  content/
    blog/           # Markdown 文章
    config.ts       # 内容集合 schema
  data/             # 站点配置、时间线、城市数据
  layouts/          # 站点布局和文章布局
  pages/            # 页面路由
  styles/           # 全局样式
public/
  images/
    covers/         # 文章封面图
    qrcode/         # 公众号二维码
```

## 安装依赖

```bash
npm install
```

## 本地开发

```bash
npm run dev
```

默认访问 `http://localhost:4321`。

## 构建检查

```bash
npm run build
```

## 新增文章

在 `src/content/blog` 下新增 Markdown 文件，例如：

```text
src/content/blog/my-new-post.md
```

Frontmatter 示例：

```yaml
---
title: "我做好了所有准备，站在你面前"
description: "一次关于成长、准备和重新出发的记录。"
date: "2022-01-15"
category: "成长手记"
tags: ["成长", "工作", "北京"]
city: "北京"
cover: "/images/covers/my-new-post.jpg"
featured: true
series: ""
---
```

字段说明：

- `title`：文章标题
- `description`：一句话摘要
- `date`：发布时间，格式为 `YYYY-MM-DD`
- `category`：栏目分类，可选 `关于我`、`成长手记`、`北京生活`、`扫街周记`、`城记远方`
- `tags`：文章标签
- `city`：城市，可为空
- `cover`：封面图路径，可为空
- `featured`：是否显示在首页精选文章
- `series`：系列名，例如 `扫街周记`

## 配置封面图

把图片放到：

```text
public/images/covers/
```

然后在文章 frontmatter 中填写：

```yaml
cover: "/images/covers/your-image.jpg"
```

如果 `cover` 为空，网站会自动显示胶片感占位封面。

## 配置公众号二维码

替换文件：

```text
public/images/qrcode/wechat-qrcode.svg
```

也可以在 `src/data/site.ts` 修改 `qrcode` 路径。

## 部署到 Vercel

1. 将项目推送到 GitHub。
2. 在 Vercel 导入仓库。
3. Framework Preset 选择 `Astro`。
4. Build Command 使用 `npm run build`。
5. Output Directory 使用 `dist`。

## 部署到 Cloudflare Pages

1. 将项目推送到 GitHub。
2. 在 Cloudflare Pages 创建项目并连接仓库。
3. Framework preset 选择 `Astro`。
4. Build command 填写 `npm run build`。
5. Build output directory 填写 `dist`。

## 已实现页面

- `/` 首页
- `/archive` 文章归档，支持搜索、分类、标签筛选和年份分组
- `/timeline` 成长时间线
- `/street` 扫街周记
- `/cities` 城市地图
- `/about` 关于页
- `/posts/[slug]` 文章详情页
