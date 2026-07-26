# 部署说明

## 架构

站点是纯静态产物，服务器上不跑 Node —— 构建在本地完成，只把 `dist/` 推上去。

```
本地  npm run build  →  dist/
        │
        ├── rsync ──→ dev1:/opt/lumora/site
        └── rsync ──→ dev2:/opt/lumora/site
                          │
              k3s: DaemonSet lumora-web（每节点一个 nginx）
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
| 访问地址 | http://47.120.54.233 、 http://47.120.64.186 |

**为什么用 ECR Public 的 nginx**：这两台阿里云机器直连 Docker Hub 不通，
而 k3s 配置的 `registry.cn-hangzhou.aliyuncs.com` 镜像源只代理 k8s 组件、
不代理 `docker.io/library`。ECR Public 是 Docker 官方镜像的 AWS 官方镜像站，实测可达。

## 日常发布

```bash
./deploy/deploy.sh                # 构建 + 同步 + 应用清单
./deploy/deploy.sh --skip-build   # 跳过构建，直接发已有的 dist/
```

新增文章后，如果用到了以前没出现过的字，先重新生成字体子集再发布：

```bash
npm run build && npm run fonts && ./deploy/deploy.sh
```

（`npm run fonts` 需要读 `dist/` 才能判断哪些字用手写体，所以先 build 一次。
脚本会报告站点用到但字体缺字形的字符 —— 缺字只会回退到系统字体，不影响可读性。）

脚本会把 `deploy/k8s/lumora.yaml` 的内容 hash 写进 pod 注解，所以：

- **只改了文章/样式** → 同步文件即可，pod 不重启，发布零中断
- **改了 nginx 配置** → 清单 hash 变化，自动滚动重启

`rsync --delete` 保证服务器上的文件与 `dist/` 严格一致，删掉的页面不会留下孤儿文件。

## 待办：放行 dev2 的 80 端口

目前 **dev2(47.120.64.186) 的公网 80 被阿里云安全组拦截**，dev1 已放行。
两个节点的 nginx 都健康（集群内访问 dev2 返回 200），只是外网进不来。

在阿里云控制台放行即可：

> ECS 控制台 → 实例 `i-f8z7tkykzanln8ym6jxm`（区域 cn-heyuan）→ 安全组 →
> 配置规则 → 入方向 → 手动添加：协议 TCP、端口 `80/80`、源 `0.0.0.0/0`

放行后重新跑一次 `./deploy/deploy.sh --skip-build`，两个 IP 都会显示 200。

服务器上装了 aliyun CLI 但没有配置凭证，所以这一步没法用命令行代劳。
如果之后配好了 AK，也可以用：

```bash
aliyun ecs AuthorizeSecurityGroup --RegionId cn-heyuan \
  --SecurityGroupId <安全组ID> --IpProtocol tcp --PortRange 80/80 \
  --SourceCidrIp 0.0.0.0/0 --Description "lumora http"
```

## 接域名

1. 域名解析加 A 记录，指向 `47.120.54.233`（放行 dev2 后可以两个 IP 都加做轮询）。

2. 改 `astro.config.mjs` 的 `site`，这决定 canonical / sitemap / RSS 里的绝对地址：

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
        redirectTo:
          port: websecure
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

只有 HTML 页面会进统计：nginx 对 `/images/`、`/_astro/`、`/fonts/` 都关了 access_log，
所以 PV 天然就是页面浏览量，不含静态资源。

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

## 变更记录

- 集群里原有的 `k8s-demo` 命名空间已删除（本次部署前确认不再需要），
  清单备份在 dev1 的 `/root/k8s-demo-backup-20260726.yaml`，需要时可 `kubectl apply` 恢复。
