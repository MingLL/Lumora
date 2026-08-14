package cn.minglli.lumora.event;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 客户端事件的服务端契约：允许哪些 type，每种 type 允许哪些属性、属性能取什么值。
 *
 * <p>{@code type} 开放是为了以后加事件不用改表结构，但「表结构不用改」不等于
 * 「输入可信」。接口无需鉴权，不校验的话任何人都能写进一条
 * {@code type="ADMIN_LOGIN_FAILED"} —— 将来看报表的人会当成真事。
 *
 * <p>顺带也解决了嵌套深度：每条属性的值都必须是一个受限的短字符串，嵌套的
 * 对象或数组过不了规则，不需要单独再数一遍深度。
 *
 * <p>新增事件时在 CONTRACTS 里加一行，并补上对应的测试。
 */
final class ClientEventContract {

    @FunctionalInterface
    private interface PropertyRule {
        boolean accepts(Object value);
    }

    private static final Map<String, Map<String, PropertyRule>> CONTRACTS = Map.of(
            "PAGE_OPEN", Map.of("browser", oneOf("WECHAT", "OTHER")),
            // networkType 是微信 JS-SDK 的返回值（wifi / 2g / 3g / 4g / none / unknown），
            // 不由我们定义。这里刻意不写成枚举：微信哪天多返回一个值，枚举会让我们
            // 把那段时间的数据全部 400 掉，而这正是最需要看到数据的时候。限定成短
            // token 已经足够挡住伪造，又不会因为对方加值就丢数据。
            "NETWORK_TYPE", Map.of("networkType", token(16)));

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private ClientEventContract() {
    }

    /**
     * @return 违反契约的原因；符合契约时返回 null。
     *         返回值只包含本类写死的字符串和契约里已知的属性名，不会回显客户端输入。
     */
    static String violation(String type, Map<String, Object> properties) {
        Map<String, PropertyRule> contract = CONTRACTS.get(type);
        if (contract == null) {
            return "unknown type";
        }
        for (String key : properties.keySet()) {
            if (!contract.containsKey(key)) {
                // 不回显 key，它由客户端控制。
                return "unexpected property";
            }
        }
        for (Map.Entry<String, PropertyRule> rule : contract.entrySet()) {
            Object value = properties.get(rule.getKey());
            if (value == null) {
                return "missing property " + rule.getKey();
            }
            if (!rule.getValue().accepts(value)) {
                return "invalid value for " + rule.getKey();
            }
        }
        return null;
    }

    private static PropertyRule oneOf(String... allowed) {
        Set<String> values = Set.of(allowed);
        return value -> value instanceof String text && values.contains(text);
    }

    private static PropertyRule token(int maxLength) {
        return value -> value instanceof String text
                && !text.isEmpty()
                && text.length() <= maxLength
                && TOKEN.matcher(text).matches();
    }
}
