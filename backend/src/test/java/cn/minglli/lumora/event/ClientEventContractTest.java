package cn.minglli.lumora.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ClientEventContractTest {

    @Test
    void acceptsTheTwoEventsTheArticlePageActuallySends() {
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of("browser", "WECHAT"))).isNull();
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of("browser", "OTHER"))).isNull();
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "wifi"))).isNull();
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "unknown"))).isNull();
    }

    @Test
    void rejectsTypesNobodyDeclared() {
        // 这类伪造事件最危险的地方不是写入本身，是它看起来像一条真的安全记录。
        assertThat(ClientEventContract.violation("ADMIN_LOGIN_FAILED", Map.of()))
                .isEqualTo("unknown type");
        assertThat(ClientEventContract.violation("page_open", Map.of("browser", "WECHAT")))
                .isEqualTo("unknown type");
    }

    @Test
    void rejectsUnexpectedProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("browser", "WECHAT");
        properties.put("injected", "anything");

        assertThat(ClientEventContract.violation("PAGE_OPEN", properties))
                .isEqualTo("unexpected property");
    }

    @Test
    void rejectsMissingProperties() {
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of()))
                .isEqualTo("missing property browser");
    }

    @Test
    void rejectsValuesOutsideTheDeclaredEnum() {
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of("browser", "SAFARI")))
                .isEqualTo("invalid value for browser");
    }

    @Test
    void rejectsNonStringAndNestedValues() {
        // 嵌套深度不用单独数：值必须是受限的短字符串，对象和数组自然过不了。
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of("browser", Map.of("a", "b"))))
                .isEqualTo("invalid value for browser");
        assertThat(ClientEventContract.violation("PAGE_OPEN", Map.of("browser", List.of("WECHAT"))))
                .isEqualTo("invalid value for browser");
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", 4)))
                .isEqualTo("invalid value for networkType");
    }

    @Test
    void rejectsOverlongOrNonTokenNetworkTypes() {
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "x".repeat(17))))
                .isEqualTo("invalid value for networkType");
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "wi fi")))
                .isEqualTo("invalid value for networkType");
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "")))
                .isEqualTo("invalid value for networkType");
    }

    @Test
    void acceptsNetworkTypeValuesWechatHasNotInventedYet() {
        // 刻意不写成枚举：微信加一个返回值，不该让我们把那段时间的数据全 400 掉。
        assertThat(ClientEventContract.violation("NETWORK_TYPE", Map.of("networkType", "6g"))).isNull();
    }
}
