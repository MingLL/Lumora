package cn.minglli.lumora.wechat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import me.chanjar.weixin.mp.util.crypto.WxMpCryptUtil;

/**
 * Verifies the AppID trailer in a WeChat AES frame without implementing AES.
 *
 * <p>Weixin Java 4.7.0's public decrypt method parses the trailer but returns
 * only the message and does not compare the trailer. It exposes no frame-level
 * hook. This adapter therefore asks that same library decrypt primitive to
 * return message plus trailer by changing only the encrypted frame's
 * four-byte length field. AES, padding, and normal decryption remain entirely
 * inside Weixin Java. A second one-byte probe proves that the trailer ends
 * exactly after the expected AppID. Any unexpected frame behavior fails closed.
 */
final class WechatCryptographicAppIdVerifier {

    private static final int FRAME_LENGTH_BYTES = Integer.BYTES;

    boolean matches(
            WxMpCryptUtil cryptUtil,
            String ciphertext,
            String plaintext,
            String expectedAppId) {
        try {
            byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] appId = expectedAppId.getBytes(StandardCharsets.UTF_8);
            int messageAndAppIdLength = Math.addExact(message.length, appId.length);

            byte[] expected = concatenate(message, appId);
            byte[] actual = decryptWithExposedLength(
                            cryptUtil, ciphertext, message.length, messageAndAppIdLength)
                    .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String decryptWithExposedLength(
            WxMpCryptUtil cryptUtil,
            String ciphertext,
            int originalMessageLength,
            int exposedLength) {
        byte[] encrypted = Base64.getDecoder().decode(ciphertext);
        if (encrypted.length < 2 * 16 || encrypted.length % 16 != 0) {
            throw new IllegalArgumentException("Invalid WeChat AES frame");
        }

        byte[] modified = encrypted.clone();
        byte[] originalLength = networkOrder(originalMessageLength);
        byte[] replacementLength = networkOrder(exposedLength);
        for (int index = 0; index < FRAME_LENGTH_BYTES; index++) {
            modified[index] ^= originalLength[index] ^ replacementLength[index];
        }
        return cryptUtil.decrypt(Base64.getEncoder().encodeToString(modified));
    }

    private static byte[] networkOrder(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
