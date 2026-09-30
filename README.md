# Lumora

「远方有温度」的前后端单仓库：一个 Astro 静态站，加一个负责微信公众号事件收集与
每周邮件周报的 Spring Boot 服务。

```text
analytics/    独立访问统计站（地图、来源、文章阅读排行）
frontend/     Astro 静态站（组件、字体子集脚本）
backend/      Spring Boot 服务（微信回调、日报、PostgreSQL）
deploy/       前后端的 k3s 部署清单、发布脚本与契约测试
scripts/      运维与分析脚本：访问周报、访问日志安全分析、微信本地联调助手、
              内容仓库挂载（逐个说明见 scripts/README.md）
docs/         设计与方案文档
content/      文章正文与配图，来自私有仓库，不在本仓库里（见下）
```

## 文章内容不在这个仓库

站点的文章正文与配图放在私有仓库 `MingLL/lumora-content`。本仓库只有代码。
克隆后先执行一次：

```bash
./scripts/setup-content.sh
```

它把内容仓库克隆到 `content/`，再用两条符号链接挂回 Astro 期望的位置
（`frontend/src/content/blog` 和 `frontend/public/images`）。这两个路径和 `content/`
都在 `.gitignore` 里。**不跑这一步，`npm run build` 会因为文章集合为空而产不出文章页。**

## 三个子项目

| | frontend | analytics | backend |
|---|---|---|---|
| 技术栈 | Astro + TypeScript + Tailwind | Astro + TypeScript | Java 17 + Spring Boot + MyBatis + PostgreSQL |
| 产物 | 主站静态页面 | 独立统计站静态页面 | 可执行 jar / Docker 镜像 |
| 域名 | lumora.love | analytics.lumora.love（可配置） | 通过对应站点域名路由 |
| 说明 | [主站](frontend/README.md) | [统计站](analytics/README.md) | [后端](backend/README.md) |

公开页面通过 `POST /client-events` 上报页面打开事件；文章页在微信环境下还会请求
JS-SDK 签名和上报网络类型。上报失败不影响页面渲染。
管理员可在独立统计站 `https://analytics.lumora.love/` 输入管理密钥，查看来源网站、IP 地理分布地图、
访问时间、同 IP 的文章记录和阅读排行。见 [访问统计与地图](docs/visitor-analytics.md)。
后端同时独立处理公众号事件与邮件周报。
放在一个仓库里是为了统一版本和运维视角。

## 快速开始

前端：

```bash
./scripts/setup-content.sh   # 只需一次，把文章内容挂进来
cd frontend
npm install
npm run dev          # http://localhost:4321
```

后端：

```bash
cd backend
mvn -DskipTests package
```

后端跑测试需要 Docker（Testcontainers 会拉起 PostgreSQL）。

## 发布

```bash
./deploy/deploy.sh           # 前端：构建 + 同步到两台服务器 + 应用 k8s 清单
./deploy/deploy-analytics.sh # 统计站：独立构建、同步与域名入口
./deploy/deploy-backend.sh   # 后端：构建镜像 + 导入 dev2 + 迁移 + 滚动更新
```

都在仓库根目录执行，脚本自己会进各自的子目录。三个服务共用同一套 k3s 和 `lumora`
命名空间，但发布互不影响 —— 入口路由在 `deploy/k8s/lumora-ingress.yaml` 里显式写了
priority：后端的 `/client-events` 和 `/wechat/callback/jsapi-signature`（300，单独一条
是为了挂请求体大小限制）> 其余 `/wechat/callback`（200）> 前端兜底的 `/`（100）。

架构、接域名 / HTTPS、后端发布顺序与回滚、每周访问周报的安装与排查，
都在 [deploy/README.md](deploy/README.md)。

## 两份「周报」不是一回事

仓库里有两套日报，容易混淆：

- `scripts/daily-report.py` —— **站点访问周报**。在 dev1 上用 cron 跑，
  从 `kubectl logs` 收 nginx 访问日志，统计 PV/UV、热门页面、来源设备，
  每周一 07:00 发邮件，统计上一完整周。属于运维，跟后端服务无关。
- `backend/` 的 `DailyReportScheduler` —— **公众号事件周报**。统计关注 / 取关、
  扫码、菜单点击等微信回调事件，同样是每周一 07:00（Asia/Shanghai）发邮件。

## 仓库历史

前端和后端原本是两个独立仓库，通过 `git subtree` 合并，两边的提交历史都完整保留。
`backend/` 的历史可以正常 `git log` / `git blame`。
