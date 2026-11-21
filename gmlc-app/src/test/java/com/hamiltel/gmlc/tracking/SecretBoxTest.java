package com.hamiltel.gmlc.tracking;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretBoxTest {

    private static final String KEY_A = base64Key((byte) 1);
    private static final String KEY_B = base64Key((byte) 2);

    @Test
    void aSealedSecretRoundTripsAndIsNeverStoredAsPlaintext() {
        SecretBox box = new SecretBox(KEY_A);
        String secret = "0123456789abcdef0123456789abcdef";

        String sealed = box.seal(secret);

        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains(secret));
        assertEquals(secret, box.open(sealed));
    }

    @Test
    void twoSealsOfTheSameSecretDiffer() {
        SecretBox box = new SecretBox(KEY_A);
        assertNotEquals(box.seal("same-secret"), box.seal("same-secret"));
    }

    @Test
    void aTamperedSealDoesNotOpen() {
        SecretBox box = new SecretBox(KEY_A);
        String sealed = box.seal("signing-secret");

        char[] chars = sealed.toCharArray();
        int index = chars.length - 2;
        chars[index] = chars[index] == 'A' ? 'B' : 'A';

        assertThrows(IllegalStateException.class, () -> box.open(new String(chars)));
    }

    @Test
    void anotherKeyCannotOpenTheSeal() {
        String sealed = new SecretBox(KEY_A).seal("signing-secret");
        assertThrows(IllegalStateException.class, () -> new SecretBox(KEY_B).open(sealed));
    }

    @Test
    void aBlankKeyDisablesTheBoxInsteadOfStoringPlaintext() {
        SecretBox box = new SecretBox("");
        assertFalse(box.configured());
        assertThrows(IllegalStateException.class, () -> box.seal("signing-secret"));
        assertThrows(IllegalStateException.class, () -> box.open("v1:anything"));
    }

    @Test
    void aKeyThatIsNot32BytesIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalArgumentException.class, () -> new SecretBox("not base64!"));
    }

    private static String base64Key(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }
}
