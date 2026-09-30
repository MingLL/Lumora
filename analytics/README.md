# Lumora 访问统计站

独立 Astro 静态前端，默认部署到 **https://analytics.lumora.love/**。拥有自己的依赖、构建产物、nginx 服务与域名入口；统计 API 通过该域名同源路由到现有后端。

```bash
cd analytics
npm ci
npm run dev       # http://localhost:4322，/api/analytics 代理到本地后端 8080
npm run build     # analytics/dist/
```

不依赖 `frontend/` 的源码、依赖、构建或静态资源。构建时可选读取 `../content/blog/` 中的文章标题；也可指定 `CONTENT_DIR=/path/to/blog npm run build`。内容目录不存在仍可构建，文章列表显示路径。

登录使用后端 `REPORT_ADMIN_KEY`。统计站不采集自身访问、不设置追踪 Cookie、不保存管理密钥。访问统计和地图逻辑见 [功能说明](../docs/visitor-analytics.md)。

## 独立发布

先准备后端 V6 迁移及服务，再把统计域名的 DNS 指向现有 dev1 入口。
统计页面、静态资源和全部查询接口均受入口 IP 白名单限制，白名单内仍需管理密钥。
部署必须配置 `ANALYTICS_ALLOWED_IPS`（逗号分隔的 IPv4、IPv6 或 CIDR），也可将同样
的一行内容保存在 `deploy/analytics-allowed-ips.local`；该文件不进入 git。
环境变量优先，未配置、格式错误或 `/0` 全网放行时脚本会在任何远端变更前停止。

```bash
# 示例地址，须替换为实际允许的公网出口
export ANALYTICS_ALLOWED_IPS='203.0.113.10/32,2001:db8::1/128'
```

白名单根据 Traefik 直接连接的来源地址判断，不使用客户端可伪造的 X-Forwarded-For。
当前入口保留源 IP；若以后在前面增加 CDN 或反向代理，需要重新配置可信代理策略。
网络出口、代理或 IPv6 地址变化时，更新白名单后重新应用部署配置；执行发布的网络也应在白名单内。

```bash
# 查看将要使用的清单，不产生远端变更
./deploy/deploy-analytics.sh --render-only

# 在仓库根目录执行（仅发布统计前端）
./deploy/deploy-analytics.sh

# 使用另一个独立域名
ANALYTICS_HOST=analytics.example.com ./deploy/deploy-analytics.sh
```

发布脚本只同步 `analytics/dist/` 到 `/opt/lumora/analytics/`，应用独立清单
`deploy/k8s/lumora-analytics.yaml`。TLS 使用集群现有 `le-prod` 自动签发证书。
主站的发布仍使用 `deploy/deploy.sh`，后端使用 `deploy/deploy-backend.sh`。

从原主站路径迁移时，也需发布一次主站，使旧 `/admin/analytics/` 文件及主站统计 API 路由被移除；统计站不再从主域名提供。
