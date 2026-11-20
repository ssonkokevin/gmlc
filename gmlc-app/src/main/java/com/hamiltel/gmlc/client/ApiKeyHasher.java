package com.hamiltel.gmlc.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys are {@code gk_} followed by 256 random bits, base64url encoded. The
 * registry keeps the SHA-256 hex of the key, so a database copy does not hand
 * anyone a working credential.
 */
public final class ApiKeyHasher {

    public static final String PREFIX = "gk_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {
    }

    public static String newKey() {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    public static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    public static boolean looksLikeAKey(String candidate) {
        return candidate != null
                && candidate.startsWith(PREFIX)
                && candidate.length() == PREFIX.length() + 43;
    }
}
