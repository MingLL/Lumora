package cn.minglli.lumora.wechat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EventDeduplicationKey {

    private static final byte[] NULL_FIELD = {
        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };

    private EventDeduplicationKey() {}

    public static String forMessage(WechatInboundMessage message) {
        if (message.msgId() != null) {
            return "msgid:" + message.msgId();
        }

        return "sha256:" + sha256Hex(fallbackFingerprintInput(message));
    }

    static byte[] fallbackFingerprintInput(WechatInboundMessage message) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeField(output, message.appId());
        writeField(output, message.openId());
        writeField(output, message.msgType());
        writeField(output, message.event());
        writeField(
                output,
                message.createTimeEpochSeconds() == null
                        ? null
                        : Long.toString(message.createTimeEpochSeconds()));
        writeField(output, message.eventKey());
        writeField(output, message.ticket());

        ByteArrayOutputStream coordinates = new ByteArrayOutputStream();
        writeField(coordinates, canonicalDecimal(message.latitude()));
        writeField(coordinates, canonicalDecimal(message.longitude()));
        writeField(coordinates, canonicalDecimal(message.locationPrecision()));
        writeField(output, coordinates.toByteArray());

        Optional<CompositeFingerprint> compositeFingerprint =
                compositeFingerprint(message.composite());
        if (compositeFingerprint.isEmpty()) {
            output.writeBytes(NULL_FIELD);
        } else {
            CompositeFingerprint fingerprint = compositeFingerprint.orElseThrow();
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
            writeField(descriptor, fingerprint.type());
            writeField(descriptor, Integer.toString(fingerprint.itemCount()));
            writeField(descriptor, fingerprint.contentSha256());
            writeField(output, descriptor.toByteArray());
        }
        return output.toByteArray();
    }

    public static Optional<CompositeFingerprint> compositeFingerprint(
            WechatInboundMessage.CompositePayload composite) {
        if (composite == null) {
            return Optional.empty();
        }
        String hash = sha256Hex(canonicalBytes(composite.items()));
        return Optional.of(new CompositeFingerprint(
                composite.type(), composite.items().size(), hash));
    }

    static String canonicalDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    static byte[] canonicalBytes(Object value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeCanonical(output, value);
        return output.toByteArray();
    }

    static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static void writeCanonical(ByteArrayOutputStream output, Object value) {
        if (value == null) {
            output.write('N');
            output.writeBytes(NULL_FIELD);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            output.write('M');
            output.writeBytes(intBytes(map.size()));
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> (String) entry.getKey()))
                    .forEach(entry -> {
                        writeField(output, (String) entry.getKey());
                        writeField(output, canonicalBytes(entry.getValue()));
                    });
            return;
        }
        if (value instanceof List<?> list) {
            output.write('L');
            output.writeBytes(intBytes(list.size()));
            for (Object item : list) {
                writeField(output, canonicalBytes(item));
            }
            return;
        }
        output.write('V');
        writeField(output, canonicalScalar(value));
    }

    private static String canonicalScalar(Object value) {
        if (value instanceof BigDecimal decimal) {
            return canonicalDecimal(decimal);
        }
        return String.valueOf(value);
    }

    private static void writeField(ByteArrayOutputStream output, String value) {
        if (value == null) {
            output.writeBytes(NULL_FIELD);
            return;
        }
        writeField(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(ByteArrayOutputStream output, byte[] value) {
        output.writeBytes(intBytes(value.length));
        output.writeBytes(value);
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public record CompositeFingerprint(String type, int itemCount, String contentSha256) {}
}
