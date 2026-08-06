package cn.minglli.lumora.operations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurityConfiguration implements WebMvcConfigurer {

    private final AdminKeyInterceptor adminKeyInterceptor;
    private final RateLimitInterceptor jsapiRateLimitInterceptor;
    private final RateLimitInterceptor globalRateLimitInterceptor;

    public WebSecurityConfiguration(
            AdminKeyInterceptor adminKeyInterceptor) {
        this.adminKeyInterceptor = adminKeyInterceptor;
        this.jsapiRateLimitInterceptor = new RateLimitInterceptor(30, 60_000);
        this.globalRateLimitInterceptor = new RateLimitInterceptor(300, 60_000);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminKeyInterceptor).addPathPatterns("/internal/**");

        registry.addInterceptor(jsapiRateLimitInterceptor)
                .addPathPatterns("/wechat/callback/jsapi-signature",
                        "/wechat/callback/jsapi-signature/error");

        registry.addInterceptor(globalRateLimitInterceptor)
                .addPathPatterns("/wechat/callback/**")
                .excludePathPatterns("/wechat/callback/jsapi-signature",
                        "/wechat/callback/jsapi-signature/error");
    }
}