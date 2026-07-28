package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EventDeduplicationKeyTest {

    @Test
    void prefersExactDecimalMessageId() {
        var message = baseMessage().msgId(9_223_372_036_854_775_000L).build();

        assertThat(EventDeduplicationKey.forMessage(message))
                .isEqualTo("msgid:9223372036854775000");
    }

    @Test
    void formatsLiteralMessageIdFastPath() {
        var message = baseMessage().msgId(12_345L).build();

        assertThat(EventDeduplicationKey.forMessage(message)).isEqualTo("msgid:12345");
    }

    @Test
    void fallbackPreimageHasExactlyNineTopLevelFieldsWithOneCoordinateTuple() {
        var message = WechatInboundMessage.builder()
                .appId("a")
                .openId("b")
                .msgType("event")
                .event("LOCATION")
                .createTimeEpochSeconds(1L)
                .eventKey(null)
                .ticket("")
                .latitude(new BigDecimal("1.200"))
                .longitude(null)
                .locationPrecision(new BigDecimal("-0.00"))
                .build();

        String expectedNineFieldPreimageHex =
                "0000000161"
                        + "0000000162"
                        + "000000056576656e74"
                        + "000000084c4f434154494f4e"
                        + "0000000131"
                        + "ffffffff"
                        + "00000000"
                        + "00000010"
                        + "00000003312e32ffffffff0000000130"
                        + "ffffffff";

        assertThat(HexFormat.of().formatHex(
                        EventDeduplicationKey.fallbackFingerprintInput(message)))
                .isEqualTo(expectedNineFieldPreimageHex);
        assertThat(EventDeduplicationKey.forMessage(message))
                .isEqualTo(
                        "sha256:222675bc258e4056303ce180a354082b7a29102b4d2ebff4f421942627fb2ba5");
    }

    @Test
    void hashesExactFixedOrderLengthEncodedFields() {
        var message = baseMessage()
                .eventKey("qrscene_campaign")
                .ticket("ticket")
                .latitude(new BigDecimal("31.2304000"))
                .longitude(new BigDecimal("121.473700"))
                .locationPrecision(new BigDecimal("0.000"))
                .build();

        String independentlyCalculated = "sha256:" + sha256(
                encoded("wx-app"),
                encoded("openid-1"),
                encoded("event"),
                encoded("SCAN"),
                encoded("1700000000"),
                encoded("qrscene_campaign"),
                encoded("ticket"),
                encodedBytes(concatenate(
                        encoded("31.2304"),
                        encoded("121.4737"),
                        encoded("0"))),
                encoded(null));

        assertThat(EventDeduplicationKey.forMessage(message))
                .isEqualTo(independentlyCalculated)
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void distinguishesNullFromEmpty() {
        var withNull = baseMessage().eventKey(null).build();
        var withEmpty = baseMessage().eventKey("").build();

        assertThat(EventDeduplicationKey.forMessage(withNull))
                .isNotEqualTo(EventDeduplicationKey.forMessage(withEmpty));
    }

    @Test
    void canonicalizesDecimalScaleAndAllZeroRepresentations() {
        var first = baseMessage()
                .latitude(new BigDecimal("31.2304000"))
                .longitude(new BigDecimal("-0.000"))
                .locationPrecision(new BigDecimal("12.500"))
                .build();
        var second = baseMessage()
                .latitude(new BigDecimal("31.2304"))
                .longitude(BigDecimal.ZERO)
                .locationPrecision(new BigDecimal("12.5"))
                .build();

        assertThat(EventDeduplicationKey.forMessage(first))
                .isEqualTo(EventDeduplicationKey.forMessage(second));
    }

    @Test
    void acceptsCoordinateBoundariesAndCanonicalizesThem() {
        assertThat(EventDeduplicationKey.canonicalLatitude(new BigDecimal("-90.0000000")))
                .isEqualTo("-90");
        assertThat(EventDeduplicationKey.canonicalLatitude(new BigDecimal("90")))
                .isEqualTo("90");
        assertThat(EventDeduplicationKey.canonicalLongitude(new BigDecimal("-180.0000000")))
                .isEqualTo("-180");
        assertThat(EventDeduplicationKey.canonicalLongitude(new BigDecimal("180")))
                .isEqualTo("180");
        assertThat(EventDeduplicationKey.canonicalPrecision(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(EventDeduplicationKey.canonicalPrecision(
                        new BigDecimal("999999.999999")))
                .isEqualTo("999999.999999");
    }

    @Test
    void rejectsOutOfRangeCoordinatesAndPrecision() {
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLatitude(new BigDecimal("90.0000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLatitude(new BigDecimal("-90.0000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLongitude(new BigDecimal("180.0000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLongitude(new BigDecimal("-180.0000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalPrecision(new BigDecimal("-0.000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalPrecision(new BigDecimal("1000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }

    @Test
    void rejectsCoordinateScaleBeyondStorageLimits() {
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLatitude(new BigDecimal("1.12345678")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalLongitude(new BigDecimal("1.12345678")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
        assertThatThrownBy(() ->
                        EventDeduplicationKey.canonicalPrecision(new BigDecimal("1.1234567")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }

    @Test
    void rejectsExtremeCoordinateExponentBeforePlainStringAllocation() {
        BigDecimal extreme = new BigDecimal("1E+2147483647");
        var message = baseMessage().latitude(extreme).build();

        assertThatThrownBy(() -> EventDeduplicationKey.forMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
    }

    @Test
    void fixedFieldOrderCannotBeChangedWithoutChangingFingerprint() {
        var first = baseMessage().appId("left").openId("right").build();
        var swapped = baseMessage().appId("right").openId("left").build();

        assertThat(EventDeduplicationKey.forMessage(first))
                .isNotEqualTo(EventDeduplicationKey.forMessage(swapped));
    }

    @Test
    void differentCompositePayloadsHaveDifferentPrivacyFingerprintsAndDedupKeys() {
        var first = baseMessage()
                .event("scancode_push")
                .composite(new WechatInboundMessage.CompositePayload(
                        "ScanCodeInfo", List.of(Map.of("ScanResult", "secret-a"))))
                .build();
        var second = baseMessage()
                .event("scancode_push")
                .composite(new WechatInboundMessage.CompositePayload(
                        "ScanCodeInfo", List.of(Map.of("ScanResult", "secret-b"))))
                .build();

        var firstFingerprint = EventDeduplicationKey.compositeFingerprint(first.composite());
        var secondFingerprint = EventDeduplicationKey.compositeFingerprint(second.composite());

        assertThat(firstFingerprint).isPresent();
        assertThat(firstFingerprint.orElseThrow().contentSha256()).matches("[0-9a-f]{64}");
        assertThat(firstFingerprint).isNotEqualTo(secondFingerprint);
        assertThat(EventDeduplicationKey.forMessage(first))
                .isNotEqualTo(EventDeduplicationKey.forMessage(second));
        assertThat(firstFingerprint.toString()).doesNotContain("secret-a", "secret-b");
    }

    @Test
    void recursivelySortedCompoundFieldNamesProduceSameFingerprint() {
        Map<String, Object> firstOrder = Map.of(
                "Zulu", Map.of("Beta", "2", "Alpha", "1"),
                "Alpha", "top");
        Map<String, Object> secondOrder = new java.util.LinkedHashMap<>();
        secondOrder.put("Alpha", "top");
        Map<String, Object> nestedSecondOrder = new java.util.LinkedHashMap<>();
        nestedSecondOrder.put("Alpha", "1");
        nestedSecondOrder.put("Beta", "2");
        secondOrder.put("Zulu", nestedSecondOrder);

        var first = new WechatInboundMessage.CompositePayload("SendPicsInfo", List.of(firstOrder));
        var second = new WechatInboundMessage.CompositePayload("SendPicsInfo", List.of(secondOrder));

        assertThat(EventDeduplicationKey.compositeFingerprint(first))
                .isEqualTo(EventDeduplicationKey.compositeFingerprint(second));
    }

    @Test
    void compositeScalarTypeTagsPreventNumericCrossTypeCollisions() {
        assertThat(List.of(
                        compositeSha256(compositeWithValue("1")),
                        compositeSha256(compositeWithValue(1)),
                        compositeSha256(compositeWithValue(BigInteger.ONE)),
                        compositeSha256(compositeWithValue(BigDecimal.ONE))))
                .doesNotHaveDuplicates();
    }

    @Test
    void compositeScalarTypeTagsPreventBooleanStringCollision() {
        assertThat(compositeSha256(compositeWithValue("true")))
                .isNotEqualTo(compositeSha256(compositeWithValue(true)));
    }

    @Test
    void compositeTypeAndItemCountParticipateInOuterFingerprint() {
        Map<String, Object> content = Map.of("Value", "same");
        var first = baseMessage()
                .composite(new WechatInboundMessage.CompositePayload("ScanCodeInfo", List.of(content)))
                .build();
        var differentType = baseMessage()
                .composite(new WechatInboundMessage.CompositePayload("SendPicsInfo", List.of(content)))
                .build();
        var differentCount = baseMessage()
                .composite(new WechatInboundMessage.CompositePayload(
                        "ScanCodeInfo", List.of(content, content)))
                .build();

        assertThat(EventDeduplicationKey.forMessage(first))
                .isNotEqualTo(EventDeduplicationKey.forMessage(differentType))
                .isNotEqualTo(EventDeduplicationKey.forMessage(differentCount));
    }

    @Test
    void compositeEncodingMatchesIndependentLiteralFixture() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("Beta", "2");
        nested.put("Alpha", "1");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Zulu", List.of("x", "y"));
        item.put("Alpha", nested);
        var composite =
                new WechatInboundMessage.CompositePayload("ScanCodeInfo", List.of(item));
        var message = baseMessage().event("scancode_push").composite(composite).build();

        byte[] independentlyCanonicalContent = independentCanonical(composite.items());
        String contentSha256 = sha256(independentlyCanonicalContent);
        byte[] descriptor = concatenate(
                encoded("ScanCodeInfo"), encoded("1"), encoded(contentSha256));
        String completeDeduplicationKey = "sha256:" + sha256(
                encoded("wx-app"),
                encoded("openid-1"),
                encoded("event"),
                encoded("scancode_push"),
                encoded("1700000000"),
                encoded(null),
                encoded(null),
                encodedBytes(concatenate(
                        encoded(null),
                        encoded(null),
                        encoded(null))),
                encodedBytes(descriptor));

        assertThat(contentSha256)
                .isEqualTo("9e4324c468f8f04106f3a5c03555856c4ef542bfd17b6e2982d9042ac187bfc0");
        assertThat(EventDeduplicationKey.compositeFingerprint(composite)
                        .orElseThrow()
                        .contentSha256())
                .isEqualTo("9e4324c468f8f04106f3a5c03555856c4ef542bfd17b6e2982d9042ac187bfc0");
        assertThat(completeDeduplicationKey)
                .isEqualTo("sha256:3a0ff19509b5a9c55fc15557a3ce9eb511b1bf542811296ce12f76f3058571f0");
        assertThat(EventDeduplicationKey.forMessage(message))
                .isEqualTo("sha256:3a0ff19509b5a9c55fc15557a3ce9eb511b1bf542811296ce12f76f3058571f0");
    }

    @Test
    void rejectsMutableAndNonCanonicalPayloadLeaves() {
        assertThatThrownBy(() -> baseMessage()
                        .payload(Map.of("MutableText", new StringBuilder("value")))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported payload value type");
        assertThatThrownBy(() -> baseMessage()
                        .payload(Map.of("MutableBytes", new byte[] {1, 2, 3}))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported payload value type");
    }

    @Test
    void acceptsOnlyImmutableCanonicalScalarTypes() {
        var message = baseMessage()
                .payload(Map.of(
                        "String", "value",
                        "Boolean", true,
                        "Integer", 7,
                        "Long", 8L,
                        "BigInteger", BigInteger.TEN,
                        "Decimal", new BigDecimal("1.20")))
                .build();

        assertThat(SafeMessageSummary.normalizedMessageSha256(message)).matches("[0-9a-f]{64}");
    }

    @Test
    void normalizedMessageHashIsStableButCoversPrivatePayload() {
        var first = baseMessage()
                .payload(Map.of("Content", "private-a", "Nested", Map.of("B", "2", "A", "1")))
                .build();
        var reordered = baseMessage()
                .payload(Map.of("Nested", Map.of("A", "1", "B", "2"), "Content", "private-a"))
                .build();
        var changed = baseMessage().payload(Map.of("Content", "private-b")).build();

        assertThat(SafeMessageSummary.normalizedMessageSha256(first))
                .isEqualTo(SafeMessageSummary.normalizedMessageSha256(reordered))
                .matches("[0-9a-f]{64}");
        assertThat(SafeMessageSummary.normalizedMessageSha256(first))
                .isNotEqualTo(SafeMessageSummary.normalizedMessageSha256(changed));
        assertThat(SafeMessageSummary.from(first)).doesNotContain("private-a");
    }

    private static WechatInboundMessage.Builder baseMessage() {
        return WechatInboundMessage.builder()
                .appId("wx-app")
                .openId("openid-1")
                .msgType("event")
                .event("SCAN")
                .createTimeEpochSeconds(1_700_000_000L);
    }

    private static WechatInboundMessage.CompositePayload compositeWithValue(Object value) {
        return new WechatInboundMessage.CompositePayload(
                "TypedValue", List.of(Map.of("Value", value)));
    }

    private static String compositeSha256(WechatInboundMessage.CompositePayload composite) {
        return EventDeduplicationKey.compositeFingerprint(composite)
                .orElseThrow()
                .contentSha256();
    }

    private static byte[] encoded(String value) {
        if (value == null) {
            return new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
        output.writeBytes(utf8);
        return output.toByteArray();
    }

    private static byte[] encodedBytes(byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static byte[] independentCanonical(Object value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (value == null) {
            output.write('N');
            output.writeBytes(encodedNull());
        } else if (value instanceof Map<?, ?> map) {
            output.write('M');
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(map.size()).array());
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> (String) entry.getKey()))
                    .forEach(entry -> {
                        output.writeBytes(encoded((String) entry.getKey()));
                        output.writeBytes(encodedBytes(independentCanonical(entry.getValue())));
                    });
        } else if (value instanceof List<?> list) {
            output.write('L');
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(list.size()).array());
            for (Object item : list) {
                output.writeBytes(encodedBytes(independentCanonical(item)));
            }
        } else {
            output.write('V');
            output.writeBytes(encoded("string"));
            output.writeBytes(encoded(String.valueOf(value)));
        }
        return output.toByteArray();
    }

    private static byte[] encodedNull() {
        return new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    }

    private static byte[] concatenate(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[]... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] value : values) {
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
