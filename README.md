# Lumora

「远方有温度」的前后端单仓库：一个 Astro 静态站，加一个负责微信公众号事件收集与
每日邮件日报的 Spring Boot 服务。

```text
frontend/     Astro 静态站（内容、组件、字体子集脚本）
backend/      Spring Boot 服务（微信回调、日报、MySQL）
deploy/       前端的 k3s 部署清单与发布脚本
scripts/      服务器上跑的运维脚本（nginx 访问日报）
docs/         设计与方案文档
```

## 两个子项目

| | frontend | backend |
|---|---|---|
| 技术栈 | Astro 7 + TypeScript + Tailwind 4 | Java 17 + Spring Boot 3.3 + MyBatis + Flyway |
| 产物 | 纯静态 `dist/` | 可执行 jar / Docker 镜像 |
| 部署 | 本地构建 → rsync 到 dev1/dev2 → k3s 里的 nginx | Dockerfile（尚未接入 k3s） |
| 说明 | [frontend/README.md](frontend/README.md) | [backend/README.md](backend/README.md) |

两者目前**没有代码耦合**：站点是纯静态的，不调用后端接口。后端服务于公众号侧的
事件收集与日报，是独立进程。放在一个仓库里是为了统一版本和运维视角。

## 快速开始

前端：

```bash
cd frontend
npm install
npm run dev          # http://localhost:4321
```

后端：

```bash
cd backend
mvn -DskipTests package
```

后端跑测试需要 Docker（Testcontainers 会拉起 MySQL）。

## 发布

```bash
./deploy/deploy.sh   # 前端：构建 + 同步到两台服务器 + 应用 k8s 清单
```

在仓库根目录执行，脚本会自己进 `frontend/` 构建。架构、接域名 / HTTPS、
每日访问日报的安装与排查，都在 [deploy/README.md](deploy/README.md)。

## 两份「日报」不是一回事

仓库里有两套日报，容易混淆：

- `scripts/daily-report.py` —— **站点访问日报**。在 dev1 上用 cron 跑，
  从 `kubectl logs` 收 nginx 访问日志，统计 PV/UV、热门页面、来源设备，
  每天 07:00 发邮件。属于运维，跟后端服务无关。
- `backend/` 的 `DailyReportScheduler` —— **公众号事件日报**。统计关注 / 取关、
  扫码、菜单点击等微信回调事件，同样是每天 07:00（Asia/Shanghai）发邮件。

## 仓库历史

前端和后端原本是两个独立仓库，通过 `git subtree` 合并，两边的提交历史都完整保留。
`backend/` 的历史可以正常 `git log` / `git blame`。
