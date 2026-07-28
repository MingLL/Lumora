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
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MAX_LOCATION_PRECISION =
            new BigDecimal("999999.999999");

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
        writeField(coordinates, canonicalLatitude(message.latitude()));
        writeField(coordinates, canonicalLongitude(message.longitude()));
        writeField(coordinates, canonicalPrecision(message.locationPrecision()));
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
        Object normalizedContent = composite.declaredItemCount() == null
                ? composite.items()
                : Map.of(
                        "Count", composite.declaredItemCount(),
                        "PicList", Map.of("item", composite.items()));
        String hash = sha256Hex(canonicalBytes(normalizedContent));
        return Optional.of(new CompositeFingerprint(
                composite.type(), composite.itemCount(), hash));
    }

    static String canonicalLatitude(BigDecimal value) {
        return canonicalCoordinate(
                "latitude", value, MIN_LATITUDE, MAX_LATITUDE, 7);
    }

    static String canonicalLongitude(BigDecimal value) {
        return canonicalCoordinate(
                "longitude", value, MIN_LONGITUDE, MAX_LONGITUDE, 7);
    }

    static String canonicalPrecision(BigDecimal value) {
        return canonicalCoordinate(
                "precision", value, BigDecimal.ZERO, MAX_LOCATION_PRECISION, 6);
    }

    private static String canonicalCoordinate(
            String name,
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            int maximumScale) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (Math.max(normalized.scale(), 0) > maximumScale) {
            throw new IllegalArgumentException(name + " scale exceeds " + maximumScale);
        }
        return normalized.toPlainString();
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
        writeField(output, scalarType(value));
        writeField(output, canonicalScalar(value));
    }

    private static String scalarType(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Byte) {
            return "int8";
        }
        if (value instanceof Short) {
            return "int16";
        }
        if (value instanceof Integer) {
            return "int32";
        }
        if (value instanceof Long) {
            return "int64";
        }
        if (value instanceof java.math.BigInteger) {
            return "bigint";
        }
        if (value instanceof Float) {
            return "float32";
        }
        if (value instanceof Double) {
            return "float64";
        }
        if (value instanceof BigDecimal) {
            return "decimal";
        }
        throw new IllegalArgumentException(
                "Unsupported canonical scalar type: " + value.getClass().getName());
    }

    private static String canonicalScalar(Object value) {
        if (value instanceof BigDecimal decimal) {
            if (decimal.signum() == 0) {
                return "0";
            }
            return decimal.stripTrailingZeros().toString();
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
