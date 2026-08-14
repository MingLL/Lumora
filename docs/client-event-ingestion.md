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

当前应用层按来源 IP 限制为每分钟 30 次请求。限流用于降低误触发和低成本刷写，
**不证明请求来自真实 Lumora 页面**。任何能访问公网接口的客户端都能构造合法 JSON
写入事件，因此这些数据只能用于趋势分析，不能作为安全告警、计费或其他高可信业务
依据。

`/client-events` 与微信 JS-SDK 错误上报分开：正常客户端事件写入 `client_event`，
错误仍写入 `jsapi_signature_error`。

## SQL 注入与数据污染

写入通过 MyBatis 参数绑定完成，`properties` 由 Jackson 序列化后转换为 PostgreSQL
`JSONB`，不把客户端字段拼接到 SQL 中，因此该入口没有 SQL 注入路径。

但它存在匿名埋点固有的数据污染风险：攻击者可以伪造事件类型、URL 和属性值，或用
多个 IP 规避单 IP 限流。开放的 `type` 字段是为未来扩展准备的，不能把它误认为输入
已经受信任。

## 后续加固要求

在将客户端事件用于正式分析前，应完成以下三层防护：

1. **事件契约校验**：服务端维护允许的事件类型及每类事件的属性 schema。未知类型、
   多余字段、非法枚举值和超长字符串返回 `400`。新增事件时同时提交其契约与测试。
2. **输入与网关限制**：`visitId` 仅接收 UUID，`url` 只接受 `https://lumora.love/`
   下的页面；限制 `properties` 的字段数量、嵌套深度、字符串长度和总请求体大小
   （建议不超过 4 KB）。在 Traefik 对 `/client-events` 单独配置每 IP 平均 5 次/分钟、
   突发 10 次的限流。
3. **高可信场景另行设计**：若事件将用于告警、风控或计费，必须引入短时、一次性
   的服务端签发令牌，并由 CDN/WAF 提供防刷能力。匿名埋点接口本身不能承担该信任级别。

每次新增事件，应重新评估数据最小化、保留期限、隐私告知和是否需要将字段加入索引。
