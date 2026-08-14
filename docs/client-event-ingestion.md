# 匿名客户端事件采集

## 目的与范围

`POST /client-events` 用于采集浏览器侧的非业务事件。当前文章页会上报：

| `type` | `properties` | 说明 |
| --- | --- | --- |
| `PAGE_OPEN` | `browser`: `WECHAT` 或 `OTHER` | 页面在微信内置浏览器或其他浏览器中打开。 |
| `NETWORK_TYPE` | `networkType`: 微信 JS-SDK 返回值 | 仅微信环境在 `wx.getNetworkType()` 成功后上报。 |

一次页面打开生成的事件共享同一个 `visitId`，以便关联 `PAGE_OPEN` 和后续的
`NETWORK_TYPE`。该标识只在当前页面内存中存在，不作为登录身份或跨页面追踪标识。

事件存入 PostgreSQL 的 `client_event` 表：`visit_id`、`type`、`url`、
`properties JSONB`、`received_at`。`properties` 预留给以后新增事件使用，新增
事件不需要修改表结构。

## 当前公开边界

该接口刻意不要求登录或管理员密钥，因为普通访客也需要上报事件。它通过 HTTPS
入口公开，仅允许 POST；`GET /client-events` 应返回 `405`。

当前的输入限制分四层：

- **入口层**：Traefik 的 `lumora-small-body` 中间件把请求体截到 4 KB，超出直接
  413，请求到不了后端。它挂在 `/client-events` 和 `/wechat/callback/jsapi-signature*`
  上，不覆盖 `/wechat/callback/{appId}` —— 微信回调的报文大小不由我们控制。
- **应用层限流**：按来源 IP 每分钟 60 次，用的是**独立于微信签名接口的计数桶**。
  一次页面打开会发 `PAGE_OPEN` + `NETWORK_TYPE` 两条事件，两者共桶的话埋点流量
  会把签名接口挤到 429 —— 功能不能被观测拖垮。
- **字段校验**：`visitId` 和 `type` 各 64 字符、`url` 2048 字符且必须是
  `lumora.love` 下的页面（`SiteUrlValidator`，按 scheme + host + port 比对，不做
  前缀匹配），`properties` 序列化后不超过 2048 字符。
- **事件契约校验**：`ClientEventContract` 维护允许的 `type` 及每类事件的属性
  schema。未知 `type`、多余属性、缺失属性、非法枚举值都返回 `400`。属性值必须是
  受限的短字符串，所以嵌套对象/数组会被自然拒掉，不需要单独限制嵌套深度。

限流用于降低误触发和低成本刷写，**不证明请求来自真实 Lumora 页面**。契约校验能
挡住伪造的事件类型和属性，但挡不住「用合法字段刷合法事件」—— 任何能访问公网接口
的客户端都能构造合规 JSON，因此这些数据只能用于趋势分析，不能作为安全告警、计费
或其他高可信业务依据。

`/client-events` 与微信 JS-SDK 错误上报分开：正常客户端事件写入 `client_event`，
错误仍写入 `jsapi_signature_error`。

## SQL 注入与数据污染

写入通过 MyBatis 参数绑定完成，`properties` 由 Jackson 序列化后转换为 PostgreSQL
`JSONB`，不把客户端字段拼接到 SQL 中，因此该入口没有 SQL 注入路径。

但它存在匿名埋点固有的数据污染风险：契约校验之后，攻击者伪造不出新的事件类型和
属性，但仍可以在合法契约内刷量，或用多个 IP 规避单 IP 限流。`type` 的开放是**表
结构**层面的（加事件不用改表），不是**输入**层面的 —— 每个新事件都必须先进
`ClientEventContract` 才收得下来。

`type` 和 `url` 这类客户端可控字段写日志时一律过 `LogSanitizer`：项目没有 logback
配置，默认 pattern 不转义换行，直接打印等于让对方在日志里伪造整行。

## 后续加固要求

在将客户端事件用于正式分析前，还差这些：

1. ~~**事件契约校验**~~：已完成，见 `ClientEventContract`。新增事件时在契约里加
   一行，并同时提交对应的测试。
2. **输入与网关限制**：请求体 4 KB、URL 域名、属性 schema 都已经落地。仍待补的是
   `visitId` 仅接收 UUID —— 现在前端在没有 `crypto.randomUUID` 的老 webview 上会
   退化成 `${Date.now()}-${Math.random()}`，要收紧得先把前端的兜底改成生成合规
   UUID，否则会把老客户端的数据全部拒掉。另可在 Traefik 侧再加一层每 IP 平均
   5 次/分钟、突发 10 次的限流，在应用层之前挡掉刷写。
3. **高可信场景另行设计**：若事件将用于告警、风控或计费，必须引入短时、一次性
   的服务端签发令牌，并由 CDN/WAF 提供防刷能力。匿名埋点接口本身不能承担该信任级别。

每次新增事件，应重新评估数据最小化、保留期限、隐私告知和是否需要将字段加入索引。
