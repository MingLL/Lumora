package cn.minglli.lumora.operations;

import static org.assertj.core.api.Assertions.assertThat;

import cn.minglli.lumora.config.LumoraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SiteUrlValidatorTest {

    private final SiteUrlValidator validator = validatorFor("https://lumora.love");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://lumora.love",
            "https://lumora.love/",
            "https://lumora.love/posts/hello",
            "https://lumora.love/posts/hello?from=timeline",
            "https://LUMORA.LOVE/posts/hello",
            "https://lumora.love:443/posts/hello"})
    void acceptsPagesUnderTheSiteOrigin(String url) {
        assertThat(validator.isSiteUrl(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.com/",
            "http://lumora.love/",
            "https://lumora.love.evil.com/",
            "https://evil.com/https://lumora.love/",
            "https://lumora.love:8443/",
            "//lumora.love/",
            "/posts/hello",
            "not-a-url",
            "javascript:alert(1)",
            ""})
    void rejectsEverythingElse(String url) {
        assertThat(validator.isSiteUrl(url)).isFalse();
    }

    @Test
    void rejectsUserInfoTricks() {
        // 前缀匹配会把这条当成本站，实际 host 是 evil.com。
        assertThat(validator.isSiteUrl("https://lumora.love@evil.com/")).isFalse();
        // host 正确但带 userinfo，正常页面不会长这样，一并拒掉。
        assertThat(validator.isSiteUrl("https://user@lumora.love/")).isFalse();
    }

    @Test
    void rejectsNullAndOverlongUrls() {
        assertThat(validator.isSiteUrl(null)).isFalse();
        assertThat(validator.isSiteUrl(
                "https://lumora.love/" + "x".repeat(SiteUrlValidator.MAX_URL_LENGTH))).isFalse();
    }

    @Test
    void honoursANonDefaultConfiguredOrigin() {
        SiteUrlValidator local = validatorFor("http://localhost:4321");

        assertThat(local.isSiteUrl("http://localhost:4321/posts/hello")).isTrue();
        assertThat(local.isSiteUrl("http://localhost:8080/posts/hello")).isFalse();
        assertThat(local.isSiteUrl("https://lumora.love/")).isFalse();
    }

    private static SiteUrlValidator validatorFor(String origin) {
        LumoraProperties properties = new LumoraProperties();
        properties.setSiteOrigin(origin);
        return new SiteUrlValidator(properties);
    }
}
