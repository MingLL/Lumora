package cn.minglli.lumora.operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void stripsNewlinesSoAttackersCannotForgeLogLines() {
        String forged = "boom\nWARN  c.m.l.Security -- admin key rotated by operator";

        String sanitized = LogSanitizer.forLog(forged);

        assertThat(sanitized).doesNotContain("\n").doesNotContain("\r");
        assertThat(sanitized).isEqualTo("boom?WARN  c.m.l.Security -- admin key rotated by operator");
    }

    @Test
    void stripsCarriageReturnsAndAnsiEscapes() {
        assertThat(LogSanitizer.forLog("a\r\nb")).isEqualTo("a??b");
        assertThat(LogSanitizer.forLog("red\u001B[31mtext")).isEqualTo("red?[31mtext");
        assertThat(LogSanitizer.forLog("tab\there")).isEqualTo("tab?here");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(LogSanitizer.forLog("https://lumora.love/posts/hello?from=timeline"))
                .isEqualTo("https://lumora.love/posts/hello?from=timeline");
        assertThat(LogSanitizer.forLog("配置失败：invalid signature"))
                .isEqualTo("配置失败：invalid signature");
    }

    @Test
    void truncatesOverlongValues() {
        String sanitized = LogSanitizer.forLog("x".repeat(1000));

        assertThat(sanitized).hasSize(515).endsWith("...");
    }

    @Test
    void rendersNullAsLiteralNull() {
        assertThat(LogSanitizer.forLog(null)).isEqualTo("null");
    }
}
