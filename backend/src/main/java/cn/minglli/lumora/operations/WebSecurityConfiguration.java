package cn.minglli.lumora.operations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurityConfiguration implements WebMvcConfigurer {

    private final AdminKeyInterceptor adminKeyInterceptor;
    private final RateLimitInterceptor jsapiRateLimitInterceptor;
    private final RateLimitInterceptor clientEventRateLimitInterceptor;
    private final RateLimitInterceptor globalRateLimitInterceptor;

    public WebSecurityConfiguration(
            AdminKeyInterceptor adminKeyInterceptor) {
        this.adminKeyInterceptor = adminKeyInterceptor;
        this.jsapiRateLimitInterceptor = new RateLimitInterceptor(30, 60_000);
        // 埋点单独一个桶。一次文章页打开会发 PAGE_OPEN + NETWORK_TYPE 两条事件，
        // 和签名共用配额的话，纯观测流量会先把额度吃光，被 429 掉的却是签名接口
        // ——功能被观测挤掉，方向反了。60/分钟对应每 IP 每分钟 30 次页面打开，
        // 和签名接口原本的余量一致。
        this.clientEventRateLimitInterceptor = new RateLimitInterceptor(60, 60_000);
        this.globalRateLimitInterceptor = new RateLimitInterceptor(300, 60_000);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminKeyInterceptor).addPathPatterns("/internal/**");

        registry.addInterceptor(jsapiRateLimitInterceptor)
                .addPathPatterns("/wechat/callback/jsapi-signature",
                        "/wechat/callback/jsapi-signature/error");

        registry.addInterceptor(clientEventRateLimitInterceptor)
                .addPathPatterns("/client-events");

        registry.addInterceptor(globalRateLimitInterceptor)
                .addPathPatterns("/wechat/callback/**")
                .excludePathPatterns("/wechat/callback/jsapi-signature",
                        "/wechat/callback/jsapi-signature/error");
    }
}
