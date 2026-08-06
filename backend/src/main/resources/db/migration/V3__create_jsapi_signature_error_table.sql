-- 分享 JS-SDK 签名失败上报。
--
-- 前端 wx.error / fetch 失败时，POST /wechat/callback/jsapi-signature/error
-- 把页面 URL 和微信返回的 errMsg 落库，便于回看分享卡片为什么没生效
-- （典型：公众号后台 JS 安全域名没配 lumora.love）。
--
-- 复用 wechat_event 的 InnoDB + utf8mb4 + 微秒时间戳风格；url 用前缀索引 255
-- （实际有效的是 path 部分，按页面筛选用得到），received_at 全索引用于按时间排查和保留删除。
CREATE TABLE jsapi_signature_error (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    url VARCHAR(2048) NOT NULL,
    err_msg VARCHAR(1024) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_jsapi_error_received_at (received_at),
    INDEX ix_jsapi_error_url (url(255))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;
