package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SafeXmlParserTest {

    private final SafeXmlParser parser = new SafeXmlParser();

    @Test
    void parsesScalarAndNestedFieldsWithoutExposingParserInternals() {
        SafeXmlParser.ParsedXml parsed = parser.parse(bytes("""
                <xml>
                  <ToUserName><![CDATA[gh_original]]></ToUserName>
                  <Event>LOCATION</Event>
                  <SendLocationInfo>
                    <Location_X>31.23</Location_X>
                    <Location_Y>121.47</Location_Y>
                  </SendLocationInfo>
                </xml>
                """));

        assertThat(parsed.rootName()).isEqualTo("xml");
        assertThat(parsed.text("ToUserName")).isEqualTo("gh_original");
        assertThat(parsed.text("Event")).isEqualTo("LOCATION");
        assertThat(parsed.object("SendLocationInfo"))
                .containsEntry("Location_X", "31.23")
                .containsEntry("Location_Y", "121.47");
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parser.parse(bytes("<xml><Event>subscribe</xml>")))
                .isInstanceOf(WechatMalformedXmlException.class);
    }

    @Test
    void rejectsDoctypeAndExternalEntityBeforeEntityExpansion() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <xml><Event>&xxe;</Event></xml>
                """;

        assertThatThrownBy(() -> parser.parse(bytes(xxe)))
                .isInstanceOf(WechatMalformedXmlException.class);
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
