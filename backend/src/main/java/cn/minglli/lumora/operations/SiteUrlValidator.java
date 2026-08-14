package cn.minglli.lumora.operations;

import java.net.URI;
import java.net.URISyntaxException;

import cn.minglli.lumora.config.LumoraProperties;
import org.springframework.stereotype.Component;

/**
 * 判断一个客户端传来的 URL 是不是本站页面。
 *
 * <p>两个开放接口都需要它：JS-SDK 签名接口不能给站外 URL 签名，客户端埋点也
 * 不该收下站外 URL —— 那些记录只会污染后续的访问分析。
 *
 * <p>用 {@link URI} 解析而不是前缀匹配：{@code https://lumora.love@evil.com/}
 * 这种把主机名塞进 userinfo 的写法，前缀匹配会放过，解析后 host 是 evil.com。
 */
@Component
public class SiteUrlValidator {

    // 和 client_event.url / jsapi_signature_error.url 的列宽一致。
    public static final int MAX_URL_LENGTH = 2048;

    private final URI origin;

    public SiteUrlValidator(LumoraProperties properties) {
        this.origin = URI.create(properties.getSiteOrigin());
    }

    public boolean isSiteUrl(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_URL_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        return origin.getScheme().equalsIgnoreCase(uri.getScheme())
                && origin.getHost().equalsIgnoreCase(uri.getHost())
                && defaultedPort(origin) == defaultedPort(uri);
    }

    // 浏览器的 location.href 会省略默认端口，配置里也不会写，但显式写出来的
    // https://lumora.love:443/ 指的是同一个地方，不该被当成站外。
    private static int defaultedPort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
