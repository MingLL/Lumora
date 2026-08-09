# 部署说明

两个子项目都部署在同一套 k3s 上，共用 `lumora` 命名空间和 Traefik 入口，
但发布互不影响：

| | 前端静态站 | 后端服务 |
|---|---|---|
| 发布命令 | `./deploy/deploy.sh` | `./deploy/deploy-backend.sh` |
| 清单 | `deploy/k8s/lumora.yaml` | `deploy/k8s/lumora-backend*.yaml` |
| 共用清单 | `deploy/k8s/lumora-ingress.yaml`（入口层，两边都会 apply） ||
| 公网路径 | `/`（兜底） | `/wechat/callback/`（更长，优先命中） |
| 详见 | 下文 | [后端发布](#后端发布) |

下文先讲**前端静态站**（`frontend/`）。

## 架构

站点是纯静态产物，服务器上不跑 Node —— 构建在本地完成，只把 `frontend/dist/` 推上去。

```
本地  (cd frontend && npm run build)  →  frontend/dist/
        │
        └── rsync ──→ dev1:/opt/lumora/site
                          │
              k3s: DaemonSet lumora-web（固定在 dev1）
                   hostPath /opt/lumora/site → /usr/share/nginx/html（只读）
                          │
                   Service → Ingress（Traefik）→ :80
```

复用了两台机器上已有的 k3s 集群（dev1 是 control-plane，dev2 是 agent），
Traefik 是 k3s 自带的，不需要额外装 ingress controller。

| | |
|---|---|
| 命名空间 | `lumora` |
| 静态文件目录 | `/opt/lumora/site`（两台各一份） |
| 镜像 | `public.ecr.aws/docker/library/nginx:1.29-alpine` |
| 访问地址 | https://lumora.love（80 端口 301 到 https；Ingress 按 host 匹配，直连 IP 会 404） |

**为什么用 ECR Public 的 nginx**：这两台阿里云机器直连 Docker Hub 不通，
而 k3s 配置的 `registry.cn-hangzhou.aliyuncs.com` 镜像源只代理 k8s 组件、
不代理 `docker.io/library`。ECR Public 是 Docker 官方镜像的 AWS 官方镜像站，实测可达。

## 日常发布

```bash
./deploy/deploy.sh                # 构建 + 同步 + 应用清单
./deploy/deploy.sh --skip-build   # 跳过构建，直接发已有的 frontend/dist/
```

脚本在仓库根目录执行，构建那步会自己进 `frontend/`，不用手动切目录。

新增文章后，如果用到了以前没出现过的字，先重新生成字体子集再发布：

```bash
(cd frontend && npm run build && npm run fonts) && ./deploy/deploy.sh
```

（`npm run fonts` 需要读 `dist/` 才能判断哪些字用手写体，所以先 build 一次。
脚本会报告站点用到但字体缺字形的字符 —— 缺字只会回退到系统字体，不影响可读性。）

脚本会把 `deploy/k8s/lumora.yaml` 的内容 hash 写进 pod 注解，所以：

- **只改了文章/样式** → 同步文件即可，pod 不重启，发布零中断
- **改了 nginx 配置** → 清单 hash 变化，自动滚动重启

`rsync --delete` 保证服务器上的文件与 `frontend/dist/` 严格一致，删掉的页面不会留下孤儿文件。

## 接域名

> 已完成：`lumora.love` 已解析到 dev1，Ingress 按 host 匹配，Traefik 用 Let's Encrypt
> 签发证书（ACME http-01 走 80 端口），80 端口在入口层 301 到 https。
> 以下步骤保留作换域名时的参考。

1. 域名解析加 A 记录，指向 dev1 的公网 IP（前端只跑在 dev1 上）。

2. 改 `frontend/astro.config.mjs` 的 `site`，这决定 canonical / sitemap / RSS 里的绝对地址：

   ```js
   site: 'https://你的域名',
   ```

3. 在 `deploy/k8s/lumora.yaml` 的 Ingress 规则里加上 host：

   ```yaml
   rules:
     - host: 你的域名
       http:
         paths:
           - path: /
             pathType: Prefix
             backend:
               service:
                 name: lumora-web
                 port:
                   number: 80
   ```

4. `./deploy/deploy.sh` 重新发布。

> ⚠️ **大陆服务器需要 ICP 备案**。这两台机器在阿里云 cn-heyuan（河源），
> 未备案的域名指向大陆 IP 后，80/443 会被运营商拦截 —— 这跟本项目配置无关，
> 备案通过前先继续用 IP 访问。

## 接 HTTPS

域名备案通过后，用 Traefik 内置的 ACME 签 Let's Encrypt 证书最省资源
（机器只有 1.8G 内存，不建议再装 cert-manager）。

在 dev1 上创建 `/var/lib/rancher/k3s/server/manifests/traefik-config.yaml`：

```yaml
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    ports:
      web:
        # chart v34 起的写法。旧的 `redirectTo: {port: websecure}` 已被移除，
        # 继续用它 helm upgrade 会直接 fail（2026-08-09 踩过）。
        # permanent: true 才发 301，不写默认是 302。
        redirections:
          entryPoint:
            to: websecure
            scheme: https
            permanent: true
    certificatesResolvers:
      le:
        acme:
          email: 你的邮箱
          storage: /data/acme.json
          httpChallenge:
            entryPoint: web
    persistence:
      enabled: true
```

k3s 会自动 reload。然后给 Ingress 加注解和 TLS：

```yaml
metadata:
  annotations:
    traefik.ingress.kubernetes.io/router.tls: "true"
    traefik.ingress.kubernetes.io/router.tls.certresolver: le
spec:
  tls:
    - hosts:
        - 你的域名
```

## 每日访问日报

每天早上 07:00（服务器时区就是 CST，不用换算）把前一天的访问情况发到邮箱。
内容包括 PV/UV 及环比、热门页面（带文章标题）、来源与设备构成、404 与服务器错误、时段分布。

安装（可反复执行，不会覆盖已填好的凭证）：

```bash
./deploy/setup-report.sh
```

然后填发信凭证 —— 这一步只能手动，密码不进仓库：

```bash
ssh dev1
vi /etc/lumora/report.env      # 填 SMTP_USER 和 SMTP_PASS
```

`SMTP_PASS` 要的是**授权码不是登录密码**：登录 mail.qq.com → 设置 → 账户 →
开启「IMAP/SMTP 服务」→ 生成授权码。

填好后自检：

```bash
ssh dev1 '/opt/lumora/bin/daily-report.py test-mail'                    # 发测试信
ssh dev1 '/opt/lumora/bin/daily-report.py report --dry-run'             # 只生成不发送
ssh dev1 '/opt/lumora/bin/daily-report.py report --date 2026-07-27 --dry-run'  # 补看某天
```

### 它是怎么工作的

```
每小时 :05   daily-report.py collect  ──→ /var/log/lumora/access-YYYY-MM-DD.log
每天 07:00   daily-report.py report   ──→ 统计前一天 ──→ QQ SMTP(465) ──→ 邮箱
```

几个绕不开的约束，决定了它为什么长这样：

- **日志用 `kubectl logs` 收，不读文件。** nginx 跑在两个节点上各写各的，而 dev1
  没法 ssh 到 dev2，`kubectl logs -l app=lumora-web` 能一次把两个节点的日志都拉过来。
- **为什么每小时归档一次。** `kubectl logs` 读的是容器日志，pod 一重启就只剩新容器的
  内容。每小时落盘，pod 重启最多影响一小时，而不是整份日报。归档保留 90 天。
- **必须走 465/587。** 阿里云 ECS 封禁 25 端口出站，所以 `mail`/`sendmail` 都用不了，
  脚本直接用 Python smtplib 走 SSL。
- **健康检查要过滤掉。** k8s 的存活探针每 10 秒请求一次首页，不滤掉的话真实访客会被
  完全淹没。统计里也把爬虫单独拎出来，不计入 PV/UV。
- **日志时间是 UTC，统计按 CST。** 容器里没有时区数据，所以 nginx 记的是 UTC，
  脚本负责转换 —— 别看到日志里是 16:00 就以为出错了。

nginx 对 `/images/`、`/_astro/`、`/fonts/` 都关了 access_log，所以统计不含
静态资源，主要反映页面访问；RSS、sitemap、robots.txt 等仍可能被记录和计入。

### 访问最多的访客与归属地

日报会列出访问最多的 3 个访客：只统计非爬虫且 HTTP 状态码为
200 或 304 的成功请求，按命中数从高到低排序。当前 access log 主要记录页面
访问，但 RSS、sitemap、robots.txt 等也可能计入。每行显示完整 IP、命中数和不同
请求路径数。

归属地默认通过 HTTPS 查询 `ipinfo.is`，显示国家/地区、省州、城市和 ISP。
每份日报最多向该服务披露 3 个尚未缓存的公网 IP；内网、保留或其他
非公网地址不会发送给外部服务。单次查询超时为 3 秒，超时、网络错误或
响应异常时只标记“归属地暂不可用”，不会阻断其余日报生成或发送。
三个未缓存地址依次超时时，最多可为日报增加约 9 秒。

成功的查询结果缓存在 `/var/lib/lumora/geo-cache.json`，有效期为 30 天，
缓存文件权限为 `0600`。30 天是查询结果的新鲜度期限，不代表到期即从磁盘
删除；过期条目会在后续查询成功并重写缓存时被清理。

**隐私与安全提示：** 除了每份日报最多向 `ipinfo.is` 披露 3 个未缓存
的公网 IP，日报还会把完整 IP 及推断的归属地发给 `MAIL_TO` 配置的收件人；
原始访问归档保留 90 天，其中也包含 IP。请仅配置必要的收件人，并限制邮箱、
`/var/log/lumora/access-*.log` 归档和归属地缓存的访问权限。运营者应根据适用地区和
业务情况评估隐私告知、同意及其他合规义务。

> **未来设计，尚未实现：** 如果需要完全离线的 IP 归属地查询，计划以
> `GEO_PROVIDER` 作为在线/离线 provider 的选择边界，增加 MaxMind GeoLite2
> MMDB provider，并由独立的定时更新流程下载和原子替换 MMDB 数据库。当前版本
> 没有 `GEO_PROVIDER`、GeoLite2 MMDB 读取或数据库更新能力。

### 排查日报

```bash
ssh dev1 'tail -30 /var/log/lumora/cron.log'        # cron 执行记录
ssh dev1 'wc -l /var/log/lumora/access-*.log'       # 归档是否在增长
ssh dev1 'crontab -l'                                # 定时任务是否还在
ssh dev1 'cat /var/log/lumora/.collect-state'       # 上次收集到哪个时间点
```

没收到邮件时，先跑一次 `test-mail` 看是 SMTP 的问题还是统计的问题。

## 排查

```bash
# pod 状态
ssh dev1 'k3s kubectl -n lumora get pod -o wide'

# nginx 日志
ssh dev1 'k3s kubectl -n lumora logs -l app=lumora-web --tail=50'

# 确认某个节点自己能 serve（绕过公网和 Traefik）
ssh dev2 'curl -sI http://127.0.0.1/ | head -1'

# 看服务器上的文件
ssh dev1 'ls /opt/lumora/site | head'

# 完整回滚：删掉整个命名空间，文件仍在 /opt/lumora/site
ssh dev1 'k3s kubectl delete ns lumora'
```

### 站点突然整个打不开

先分清是「应用挂了」还是「节点抖了」—— 后者会连 Traefik 一起重启，表现为两台
同时访问不到，但机器其实一直开着：

```bash
# 节点有没有 NotReady 过（2026-08-04 就是这条查出来的）
ssh dev1 'journalctl -u k3s --since "today" | grep "Node became not ready"'

# 控制面数据库是不是被 I/O 拖慢了（正常应该一条都没有）
ssh dev1 'journalctl -u k3s --since "today" | grep -c "Slow SQL"'

# 机器到底重启没有：uptime 还在就说明是 k3s 层面的问题，不是宕机
ssh dev1 uptime; ssh dev2 uptime

# 被重启的 Pod 是 OOMKilled 还是 Completed（后者说明是节点抖动带走的）
ssh dev1 'k3s kubectl get pod -A -o json | jq -r ".items[]|select(.status.containerStatuses[]?.lastState.terminated)|\"\(.metadata.name) \(.status.containerStatuses[0].lastState.terminated.reason)\""'
```

## 变更记录

- 集群里原有的 `k8s-demo` 命名空间已删除（本次部署前确认不再需要），
  清单备份在 dev1 的 `/root/k8s-demo-backup-20260726.yaml`，需要时可 `kubectl apply` 恢复。

- **2026-08-04：发布后端把控制面压垮，站点断了几分钟。** 17:22 往 dev1 导镜像 →
  解包跟 kine(SQLite) 抢盘 → INSERT 慢到 3.5s → apiserver `i/o timeout` →
  kubelet 续不上 node lease → 17:32:09 dev1 `NotReady` → Traefik 被重启 → 断站；
  dev2 隧道断开跟着 `NotReady`，17:37 两台恢复。机器没重启，也不是 OOM
  （被重启的 Pod 全是 `Completed exit=0`）。修法：镜像不再导给控制面
  （`IMAGE_HOSTS=(dev2)`）、import 加 `ionice -c3 nice -n19`、迁移 Job 和
  schema-smoke Pod 钉到 dev2、dev2 的 limits 从 136% 收到贴合节点容量。
  契约测试新增 6 条断言锁住这些性质。

## 后端发布

```bash
./deploy/deploy-backend.sh              # 用当前 git commit 当镜像 tag
./deploy/deploy-backend.sh v20260729    # 指定 tag
./deploy/deploy-backend.sh --skip-build # 复用已构建的同名镜像
```

同一个镜像跑四种角色，靠环境变量区分，互不抢工作：

| 角色 | 副本 | 后台任务 | 内部发送 | 公网 |
|---|---|---|---|---|
| `migrate` (Job) | 一次性 | — | — | 无 |
| `web` | 1 | 全关 | 关 | `/wechat/callback/` |
| `worker` | 1 | 全开 | 关 | 无 |
| `ops` | 1 | 全关 | 开 | 无，仅 ClusterIP |

**四种角色全部通过 `nodeSelector` 固定在 `dev2`** —— 三个 Deployment、迁移 Job，
以及 `deploy-backend.sh` 里那个临时的 schema-smoke Pod。集群内 MySQL 也在 dev2。
任一节点不可用时，对应工作负载不会自动漂移。

把后端整体压在 agent 节点上，是为了让控制面 `dev1` 只干控制面的事：镜像因此
只分发到 dev2（见[镜像怎么上服务器](#镜像怎么上服务器)），dev1 的磁盘不用在发布时
陪着解包 147 MB 的 tar。代价是**改节点必须三处一起改** —— `lumora-backend.yaml`
的 `nodeSelector`、`lumora-backend-migrate.yaml` 的 `nodeSelector`、
`deploy-backend.sh` 的 `IMAGE_HOSTS`/`BACKEND_NODE`。漏改任何一处，Pod 会调度到
没有镜像的机器上卡在 `ImagePullBackOff`（`imagePullPolicy: IfNotPresent` 加上
集群没有可用 registry）。契约测试锁住了这几处的一致性。

内存预算按 dev2 的 1870 MiB 物理内存算：三个 Java Deployment 各 requests
256 MiB / limits 384 MiB，MySQL requests 512 MiB / limits 768 MiB，limits 合计
1920 MiB。**limits 之和必须贴着节点容量**，否则四个容器同时冲顶会触发内核
OOM killer —— 它不看 limits 挑谁杀，很可能连 MySQL 一起带走；收紧之后最坏
情况是单个 Pod 被 OOMKill 再由 Deployment 拉起。改这些数字前先看
`kubectl -n lumora top pod`（当前实测 RSS：web/worker/ops 约 178～215 MiB，
MySQL 约 502 MiB），JVM 堆按 `-XX:MaxRAMPercentage=75.0` 跟着 limits 走。

**为什么 worker 是 `strategy: Recreate`**：先停旧的再起新的，保证任一时刻最多一个
调度实例。数据库租约仍是最终保障，但发布过程不该依赖它兜底。worker 的就绪探针查
`/tmp/lumora-worker-ready`，这个文件由 `WorkerReadinessVerifier` 在确认「模式对、
数据库通、三个定时任务都注册了」之后才写 —— 进程起来了不等于在干活。

### 前置准备

服务器上要有凭据文件，脚本只读不写、也不打印内容：

```bash
ssh dev1 'sudo mkdir -p /opt/lumora/backend'
scp backend/.env.example dev1:/tmp/env && ssh dev1 \
  'sudo mv /tmp/env /opt/lumora/backend/.env && sudo chmod 600 /opt/lumora/backend/.env'
# 然后在服务器上填写实际值，两台机器都要
```

校验：`ssh dev1 'cd /opt/lumora/backend && bash -s' < backend/deploy/check-env.sh`

数据库：如果已有 MySQL，把 `.env` 的 `MYSQL_HOST` 指过去即可。没有的话用
`deploy/k8s/lumora-mysql.yaml`（把 `__MYSQL_NODE__` 换成节点名再 apply）。
它用 hostPath，**线上当前跑在 dev2，数据在 `dev2:/opt/lumora/mysql`**（dev1 上
没有这个目录）；hostPath 不跟着 pod 漂移，换节点就等于换了一个空库。备份和加密
要自己安排，见 [backend/README.md](../backend/README.md) 的 Operating the Database。

注意两个发布脚本都**不会** apply 这份清单，改了它要手动执行：

```bash
sed 's/__MYSQL_NODE__/dev2/' deploy/k8s/lumora-mysql.yaml \
  | ssh dev1 'cat > /tmp/mysql.yaml && sudo /usr/local/bin/k3s kubectl apply -f /tmp/mysql.yaml'
```

### 镜像怎么上服务器

这两台阿里云机器直连 Docker Hub 不通，也没有私有 registry，所以走
`docker save` → `scp` → `k3s ctr images import`。构建时固定 `--platform linux/amd64`
（本地可能是 arm64 Mac）。

**只导 `dev2`，不导控制面。** 由 `deploy-backend.sh` 的 `IMAGE_HOSTS` 控制。
2026-08-04 17:22 那次发布往 dev1 也导了一份：解包和 k3s 的 kine（SQLite，跟镜像
在同一块盘）抢 I/O，kine 单条 INSERT 从毫秒涨到 3.5 秒，apiserver 开始
`i/o timeout`，kubelet 续不上 node lease，17:32:09 dev1 自判 `NotReady`，kubelet
顺手重启了它管的 Pod —— 其中包括 Traefik（唯一入口），站点断了几分钟；dev2 也
因为到 supervisor 的隧道断开跟着 `NotReady`。dev1 上根本不跑后端 Pod，那份 I/O
是纯浪费。三周的 journal 里 `Node became not ready` 只出现过两次，两次都在
`ctr images import` 期间。

即便如此，import 仍然带 `ionice -c3 nice -n19`（idle I/O + 最低 CPU 优先级）：
将来若有人把某个角色挪回控制面，这层兜底还在。

### 发布顺序

脚本严格按这个顺序走，任何一步失败都不会动到正在服务的 pod：

1. 预检控制面和后端节点可达、`.env` 存在且权限 600
2. 构建 + `verify-packaging.sh`（非 root 用户、镜像里没有密钥形状的值）
3. 分发镜像到后端节点（只有 dev2）
4. 从服务器 `.env` 刷新 `Secret/lumora-env`
5. 跑迁移 Job，**等它完成**
6. `schema-smoke` 确认候选镜像能用迁移后的库
7. apply 清单，等三个 Deployment rollout
8. 验证 web 存活，并确认公网访问不到 `/internal/` 和 `/actuator/health/readiness`

回滚：

```bash
ssh dev1 'sudo k3s kubectl -n lumora rollout undo deployment/lumora-backend-web'
```

迁移是 expand-only 的（只加可空列/表/兼容索引），所以旧版本能继续跑在迁移后的库上。

### 手动补发日报

`ops` 容器没有公网路由，只能在服务器上调：

```bash
ssh dev1 "sudo k3s kubectl -n lumora exec deployment/lumora-backend-ops -- \
  curl -fsS -X POST http://127.0.0.1:8080/internal/reports/2026-07-28/send \
  -H 'X-Lumora-Admin-Key: <REPORT_ADMIN_KEY>' -H 'X-Request-Id: $(uuidgen)'"
```

`X-Request-Id` 是幂等键，重复用同一个 ID 会返回上次的结果而不是再发一封。
可选 JSON body：`{"regenerate": true}` 重新生成快照，`{"force": true}` 允许已成功时再发。

### 改了发布脚本之后

```bash
bash deploy/tests/deploy_contract_test.sh
```

用假的 docker/ssh/scp/curl 跑一遍，断言迁移早于 apply、校验早于分发、预检失败时
不产生任何变更、以及输出里不出现 `.env` 的内容。
