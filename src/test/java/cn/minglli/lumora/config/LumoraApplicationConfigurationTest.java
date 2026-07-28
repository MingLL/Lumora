package cn.minglli.lumora.config;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LumoraApplicationConfigurationTest {

    private static final String[] REQUIRED_ENVIRONMENT = {
            "WECHAT_APP_ID=wx-app-id",
            "WECHAT_ORIGINAL_ID=gh_original",
            "WECHAT_TOKEN=wechat-token",
            "WECHAT_AES_KEY=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            "MYSQL_HOST=localhost",
            "MYSQL_DATABASE=lumora",
            "MYSQL_USERNAME=lumora_user",
            "MYSQL_PASSWORD=secret",
            "MAIL_USERNAME=sender@qq.com",
            "MAIL_AUTH_CODE=mail-auth-code",
            "REPORT_RECIPIENTS=owner@example.com,ops@example.com",
            "REPORT_ADMIN_KEY=admin-secret"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest(name = "{0} missing from real application.yml fails startup")
    @MethodSource("requiredEnvironmentNames")
    void failsFastWhenRequiredEnvironmentVariableIsMissing(String missingEnvironmentName) {
        String[] availableEnvironment = Arrays.stream(REQUIRED_ENVIRONMENT)
                .filter(property -> !property.startsWith(missingEnvironmentName + "="))
                .toArray(String[]::new);

        contextRunner.withPropertyValues(availableEnvironment).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(missingEnvironmentName);
        });
    }

    private static Stream<String> requiredEnvironmentNames() {
        return Arrays.stream(REQUIRED_ENVIRONMENT)
                .map(property -> property.substring(0, property.indexOf('=')));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LumoraProperties.class)
    static class PropertiesConfiguration {
    }
}
