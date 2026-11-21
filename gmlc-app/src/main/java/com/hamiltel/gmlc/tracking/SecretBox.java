package com.hamiltel.gmlc.tracking;

import com.hamiltel.gmlc.config.TrackingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seals a channel secret with AES-256-GCM. The key comes from the deployment
 * ({@code gmlc.tracking.notifier-key}), never from the database, so a database
 * dump alone does not yield a signing secret. The sealed value carries its own
 * nonce and a tag, so a tampered row fails to open rather than decrypting to
 * something else. A blank key disables channel creation instead of quietly
 * storing plaintext.
 */
@Component
public class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    public SecretBox(TrackingProperties properties) {
        this(properties.notifierKey());
    }

    /** Test seam: an explicit base64 key, or blank for an unconfigured box. */
    SecretBox(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "gmlc.tracking.notifier-key must be base64", e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("gmlc.tracking.notifier-key must decode to"
                    + " exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public boolean configured() {
        return key != null;
    }

    /** @throws IllegalStateException when no key is configured */
    public String seal(String plaintext) {
        if (key == null) {
            throw new IllegalStateException("no notifier key is configured");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
        try {
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secret could not be sealed", e);
        }
    }

    /**
     * @throws IllegalStateException when the key is absent, the value is not
     * sealed with this scheme, or the tag does not verify
     */
    public String open(String sealed) {
        if (key == null) {
            throw new IllegalStateException("no notifier key is configured");
        }
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalStateException("sealed value is not readable");
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sealed value is not readable", e);
        }
        if (packed.length <= NONCE_BYTES) {
            throw new IllegalStateException("sealed value is not readable");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(packed, 0, nonce, 0, NONCE_BYTES);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce);
        try {
            return new String(cipher.doFinal(packed, NONCE_BYTES,
                    packed.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sealed value is not readable", e);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }
}
