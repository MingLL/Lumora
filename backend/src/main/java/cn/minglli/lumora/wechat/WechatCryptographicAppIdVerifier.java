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
 * <p>A WeChat frame is {@code [16 random bytes][4-byte big-endian length][message][AppID]},
 * AES-CBC encrypted with the IV taken from the key. Weixin Java 4.7.0's public
 * decrypt method reads the length field and returns only the message, discarding
 * the AppID trailer without comparing it, and exposes no frame-level hook.
 *
 * <p>This adapter therefore replays the same library primitive with the declared
 * length raised to {@code message + AppID}. Under CBC, XOR-ing the first four
 * ciphertext bytes flips exactly those bits of plaintext block 1 — the length
 * field — while block 0, the discarded random prefix, becomes garbage. The second
 * decrypt then returns message plus trailer, which is compared in constant time
 * against the expected concatenation. AES, padding and key handling stay entirely
 * inside Weixin Java.
 *
 * <p>Any unexpected frame behaviour fails closed: a malformed frame, a shifted
 * trailer or a library change all surface as an exception or a mismatch, and the
 * caller rejects the callback with 403.
 *
 * <p><strong>Upgrade note:</strong> this depends on Weixin Java's frame layout and
 * on {@code decrypt} honouring the embedded length field. Re-run the encrypted
 * callback tests in {@code WechatCallbackControllerTest} before changing the
 * {@code weixin-java-mp} version — a silent behaviour change here rejects every
 * encrypted callback rather than failing loudly.
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
