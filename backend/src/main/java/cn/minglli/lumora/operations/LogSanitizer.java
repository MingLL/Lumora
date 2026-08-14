package cn.minglli.lumora.operations;

/**
 * 把外部可控的字符串处理成可以安全写进日志的形式。
 *
 * <p>项目没有 logback 配置，用的是 Spring Boot 默认 pattern，它不转义换行。
 * 于是任何把请求字段直接塞进 log 的地方，攻击者都能用 {@code \n} 在日志里伪造
 * 一整行 —— 日志比数据库传得更远，伪造的日志行会一路骗到排查现场。控制字符
 * 同样会污染终端和日志采集管道。
 *
 * <p>这里统一替换成 {@code ?} 而不是转义成 {@code \\n}：排查时一眼能看出
 * 「这里原本有个不该出现的字符」，不用再分辨是原文就长这样还是被转义过。
 */
public final class LogSanitizer {

    // 字段自身的 @Size 已经卡了长度，这里再截一道，防止将来有调用方漏加校验。
    private static final int MAX_LENGTH = 512;
    private static final String TRUNCATION_SUFFIX = "...";

    private LogSanitizer() {
    }

    public static String forLog(String value) {
        if (value == null) {
            return "null";
        }
        int limit = Math.min(value.length(), MAX_LENGTH);
        StringBuilder sanitized = new StringBuilder(limit + TRUNCATION_SUFFIX.length());
        for (int i = 0; i < limit; i++) {
            char character = value.charAt(i);
            // isISOControl 覆盖 \r \n \t、ESC（ANSI 转义序列的起头）和 DEL。
            sanitized.append(Character.isISOControl(character) ? '?' : character);
        }
        if (value.length() > MAX_LENGTH) {
            sanitized.append(TRUNCATION_SUFFIX);
        }
        return sanitized.toString();
    }
}
