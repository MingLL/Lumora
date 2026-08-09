package cn.minglli.lumora.config;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LumoraPropertiesTest {

    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void createValidator() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @Test
    void bindsValidConfigurationAndParsesRecipients() {
        LumoraProperties properties = bind(validProperties());

        assertThat(properties.getWechatAppId()).isEqualTo("wx-app-id");
        assertThat(properties.getPostgresPort()).isEqualTo(3307);
        assertThat(properties.getReportRecipients())
                .containsExactly("owner@example.com", "ops@example.com");
    }

    @Test
    void suppliesDocumentedDefaults() {
        Map<String, Object> values = validProperties();
        values.keySet().removeIf(key -> key.startsWith("lumora.scheduling-enabled")
                || key.startsWith("lumora.report-recovery-enabled")
                || key.startsWith("lumora.retention-enabled")
                || key.startsWith("lumora.internal-send-enabled")
                || key.startsWith("lumora.mail-from-name")
                || key.startsWith("lumora.zone"));

        LumoraProperties properties = bind(values);

        assertThat(properties.getMailFromName()).isEqualTo("Lumora");
        assertThat(properties.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(properties.isSchedulingEnabled()).isTrue();
        assertThat(properties.isReportRecoveryEnabled()).isTrue();
        assertThat(properties.isRetentionEnabled()).isTrue();
        assertThat(properties.isInternalSendEnabled()).isTrue();
    }

    @ParameterizedTest(name = "{1} is reported when {0} is absent")
    @MethodSource("requiredProperties")
    void reportsMissingRequiredVariablesByEnvironmentName(String propertyName, String environmentName) {
        Map<String, Object> values = validProperties();
        values.remove(propertyName);

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining(environmentName);
    }

    @Test
    void rejectsMalformedRecipientWithNamedValidationError() {
        Map<String, Object> values = validProperties();
        values.put("lumora.report-recipients", "owner@example.com,not-an-email");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("REPORT_RECIPIENTS");
    }

    private static LumoraProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("lumora", Bindable.of(LumoraProperties.class),
                        new ValidationBindHandler(validator))
                .get();
    }

    private static Map<String, Object> validProperties() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lumora.wechat-app-id", "wx-app-id");
        values.put("lumora.wechat-original-id", "gh_original");
        values.put("lumora.wechat-token", "wechat-token");
        values.put("lumora.wechat-aes-key", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        values.put("lumora.wechat-app-secret", "wechat-app-secret");
        values.put("lumora.postgres-host", "localhost");
        values.put("lumora.postgres-port", "3307");
        values.put("lumora.postgres-database", "lumora");
        values.put("lumora.postgres-username", "lumora_user");
        values.put("lumora.postgres-password", "secret");
        values.put("lumora.mail-username", "sender@qq.com");
        values.put("lumora.mail-auth-code", "mail-auth-code");
        values.put("lumora.report-recipients", "owner@example.com,ops@example.com");
        values.put("lumora.report-admin-key", "admin-secret");
        return values;
    }

    private static Stream<Arguments> requiredProperties() {
        return Stream.of(
                Arguments.of("lumora.wechat-app-id", "WECHAT_APP_ID"),
                Arguments.of("lumora.wechat-original-id", "WECHAT_ORIGINAL_ID"),
                Arguments.of("lumora.wechat-token", "WECHAT_TOKEN"),
                Arguments.of("lumora.wechat-aes-key", "WECHAT_AES_KEY"),
                Arguments.of("lumora.wechat-app-secret", "WECHAT_APP_SECRET"),
                Arguments.of("lumora.postgres-host", "POSTGRES_HOST"),
                Arguments.of("lumora.postgres-port", "POSTGRES_PORT"),
                Arguments.of("lumora.postgres-database", "POSTGRES_DATABASE"),
                Arguments.of("lumora.postgres-username", "POSTGRES_USERNAME"),
                Arguments.of("lumora.postgres-password", "POSTGRES_PASSWORD"),
                Arguments.of("lumora.mail-username", "MAIL_USERNAME"),
                Arguments.of("lumora.mail-auth-code", "MAIL_AUTH_CODE"),
                Arguments.of("lumora.report-recipients", "REPORT_RECIPIENTS"),
                Arguments.of("lumora.report-admin-key", "REPORT_ADMIN_KEY"));
    }
}
