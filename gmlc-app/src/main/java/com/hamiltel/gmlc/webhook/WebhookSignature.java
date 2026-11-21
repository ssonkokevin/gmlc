package com.hamiltel.gmlc.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Signs a delivery body with HMAC-SHA256. The signed material is the Unix
 * timestamp, a full stop and the exact bytes sent, so a receiver can reject a
 * replayed body by checking the timestamp against its own clock. The secret is
 * generated per subscription and shown once; it never appears in a body or a
 * log line.
 */
public final class WebhookSignature {

    public static final String TIMESTAMP_HEADER = "X-GMLC-Timestamp";
    public static final String SIGNATURE_HEADER = "X-GMLC-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, String body) {
        return "v1=" + hex(secret, timestamp + "." + body);
    }

    public static boolean verify(String secret, long timestamp, String body, String header) {
        if (header == null || secret == null) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = header.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    static String hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE; this cannot happen.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
