package com.hamiltel.gmlc.ops;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP with the RFC 4226 HMAC-SHA1 truncation, six digits and a
 * thirty-second step. The secret is base32, as authenticator apps expect.
 *
 * <p>Verification accepts the previous and next step so a skewed phone clock
 * of a few seconds does not lock an operator out; the code is still single
 * use in practice because each step is only valid for its window.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32(bytes);
    }

    public static String code(String base32Secret, Instant at) {
        return code(decode(base32Secret), at.getEpochSecond(), STEP_SECONDS, DIGITS);
    }

    public static boolean verify(String base32Secret, String candidate, Instant at) {
        if (candidate == null || base32Secret == null) {
            return false;
        }
        String observed = candidate.trim().replace(" ", "");
        if (observed.length() != DIGITS || !observed.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key;
        try {
            key = decode(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long step = at.getEpochSecond() / STEP_SECONDS;
        boolean matches = false;
        for (long drift = -1; drift <= 1; drift++) {
            String expected = code(key, Math.max(0, step + drift) * STEP_SECONDS, STEP_SECONDS, DIGITS);
            matches |= MessageDigest.isEqual(expected.getBytes(), observed.getBytes());
        }
        return matches;
    }

    static String code(byte[] key, long epochSecond, int stepSeconds, int digits) {
        long counter = epochSecond / stepSeconds;
        byte[] message = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] digest;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            digest = mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
        int offset = digest[digest.length - 1] & 0x0f;
        int binary = ((digest[offset] & 0x7f) << 24)
                | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8)
                | (digest[offset + 3] & 0xff);
        int modulo = (int) Math.pow(10, digits);
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulo);
    }

    public static byte[] decode(String base32) {
        if (base32 == null || base32.isBlank()) {
            throw new IllegalArgumentException("empty TOTP secret");
        }
        String normalised = base32.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[normalised.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < normalised.length(); i++) {
            int value = valueOf(normalised.charAt(i));
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        if (index == out.length) {
            return out;
        }
        byte[] trimmed = new byte[index];
        System.arraycopy(out, 0, trimmed, 0, index);
        return trimmed;
    }

    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    private static int valueOf(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return c - '2' + 26;
        }
        throw new IllegalArgumentException("not a base32 character");
    }
}
