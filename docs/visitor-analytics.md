# 访问统计与地图

入口：独立统计站 `https://analytics.lumora.love/`（域名可通过 `ANALYTICS_HOST` 调整）。输入现有 `REPORT_ADMIN_KEY` 查看数据；密钥只在当前页面内存中使用，不进入 URL 或浏览器存储。页面外壳为静态 HTML，页面、静态资源与 API 首先通过入口 IP 白名单，统计数据还需要后端管理员鉴权。独立前端位于 `analytics/`，不生成 sitemap，并设置 noindex 和 robots 禁止抓取。

## 展示内容

- 日期区间（默认最近 7 天，含首尾日期，最多 93 天），北京时间每日趋势。
- 页面浏览、独立 IP、文章阅读、未采集 IP 的历史浏览数。
- 文章阅读排行、来源网站、访客排行（各前 100），同一 IP 的全部访问明细（50 条一页）。
- 世界地图展示访客排行中有坐标的 IP，同一位置聚合，点击圆点或 IP 查看访问记录。
- 地图是局部样本：只展示前 100 个 IP，未知位置不猜测，不以 (0, 0) 代替。

`PAGE_OPEN` 才计浏览量，`NETWORK_TYPE` 不重复计数；同一时间范围内相同 visitId 的重复上报合并。所有使用 SiteLayout 的公开页面现在都上报，文章页与微信事件共享 visitId。管理员页不采集。URL 的查询参数和锚点不用于排行，文章尾斜杠统一；来源只存域名。直接访问也包括 referrer 被浏览器隐藏的访问。独立 IP 不等于独立访客。

旧记录缺少 IP 和来源，分别显示未知；不回填虚构信息。此功能统计浏览器上报的页面打开，不包含所有 HTTP 请求、图片、爬虫日志，也不会自动导入旧 nginx / Traefik 日志。

## 地理位置

复用周报已有服务 [ipinfo.is](https://ipinfo.is/)，查询 IP 的国家、地区、城市和经纬度。浏览器不直接访问归属地服务；后端超时为 3 秒，失败显示未知。位置表示 IP 出口的大致位置，不是设备位置。

接口免费限制为每分钟 6 次，后端将新查询间隔限制为 11 秒，页面显示加载进度；首次查询 100 个新 IP 可能需要约 18 分钟。成功结果在 PostgreSQL 缓存 7 天，失败缓存 5 分钟。缓存过期数据在后续写入时清理。可以在后端配置 `LUMORA_GEO_API_KEY` 使用已有服务的付费 API key，当前实现会将查询间隔缩短为 1 秒；不需要配置也能使用。

底图为 Natural Earth 公共领域的 110m 陆地轮廓，源数据：
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson

以等距圆柱投影转换为本地 `analytics/public/maps/world-land.svg`，没有远程瓦片或地图 SDK。来源与许可：https://www.naturalearthdata.com/about/terms-of-use/

## 发布

1. 先按现有后端发布流程执行 Flyway V6 迁移，新增 client_event 两列、索引和 IP 位置缓存表，再更新后端。
2. 为 `analytics.lumora.love`（或选定的独立域名）设置指向 dev1 的 DNS。
3. 设置 `ANALYTICS_ALLOWED_IPS` 或本机 `deploy/analytics-allowed-ips.local`（公网 IP/CIDR，逗号分隔，必填）。在 `analytics/` 执行 `npm ci`，再于仓库根目录运行 `./deploy/deploy-analytics.sh`。只读 `/api/analytics/{summary,visits,location}` 路由仅挂在统计域名下，浏览器不跨域，`/internal/**` 保持不公开。
4. 发布一次主站以移除旧 `/admin/analytics/` 静态页和主域名上的统计接口路由。之后两个前端可独立发布。
5. 访问统计域名根路径，输入现有管理密钥。

统计站有独立 nginx、Service、NetworkPolicy、HTTPS 路由及 `/opt/lumora/analytics` 文件目录。主站只保留 `/client-events` 采集，统计查询和展示使用独立域名。详见 [统计站说明](../analytics/README.md)。

后端服务必须保持只允许可信 Traefik 入站的 NetworkPolicy，且 Traefik 不信任客户端提供的转发头；采集 IP 来自网关提供的 X-Forwarded-For，不接受上报 JSON 中的 IP。直连后端的自定义部署应先配置可信代理再使用该字段。复用现有 client_event 保留与清理任务。

## 验证

- `cd analytics && npm run build`
- `cd frontend && npm run build`
- `bash deploy/tests/analytics_contract_test.sh`
- `cd backend && mvn -Dtest=ClientEventControllerTest,ClientEventContractTest,AdminKeyInterceptorTest,AnalyticsControllerTest test`
- Docker 可用时：`cd backend && mvn -Dtest=AnalyticsIntegrationTest,ClientEventMapperTest test`

集成测试覆盖北京时间的日界线、重复上报、过滤非浏览事件、热门文章聚合、同 IP 多篇文章和分页。

## IP 白名单

使用 [Traefik IPAllowList](https://doc.traefik.io/traefik/v3.3/middlewares/http/ipallowlist/)，同时挂到页面与三个 API 的入口规则。范围外请求返回 403；范围内的统计 API 继续验证管理密钥。没有配置白名单时发布脚本停止，不会默认放行。支持 IPv4 `/32`、IPv6 `/128` 和明确的 CIDR；不根据单个 IP 猜测运营商网段。

部署时从本地文件读取白名单并渲染进 Kubernetes Middleware。文件被 git 忽略，克隆到其他机器后需重新提供。验证命令 `./deploy/deploy-analytics.sh --render-only` 会显示待应用的配置，不更改线上状态。
