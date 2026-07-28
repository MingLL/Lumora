package cn.minglli.lumora.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class RecipientCodec {

    private RecipientCodec() {
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String visible = local.length() <= 1 ? local : local.substring(0, 1);
        return visible + "***" + domain;
    }

    static String sha256(List<String> sortedNormalizedRecipients) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join(",", sortedNormalizedRecipients);
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
