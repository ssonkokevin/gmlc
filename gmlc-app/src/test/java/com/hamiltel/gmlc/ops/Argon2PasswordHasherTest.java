package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.config.OpsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2PasswordHasherTest {

    private final Argon2PasswordHasher hasher = new Argon2PasswordHasher(
            new OpsProperties(false, 15, 8, 5, 15, "test", 30, 4096, 1, 1,
                    null, null, null, "LAB", 0));

    @Test
    void hashesWithArgon2idAndVerifies() {
        String hash = hasher.hash("correct horse battery staple".toCharArray());
        assertTrue(hash.startsWith("$argon2id$v=19$m=4096,t=1,p=1$"));
        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), hash));
        assertFalse(hasher.verify("wrong".toCharArray(), hash));
    }

    @Test
    void saltsEveryHash() {
        String first = hasher.hash("same-password".toCharArray());
        String second = hasher.hash("same-password".toCharArray());
        assertNotEquals(first, second);
        assertTrue(hasher.verify("same-password".toCharArray(), first));
        assertTrue(hasher.verify("same-password".toCharArray(), second));
    }

    @Test
    void rejectsMalformedStoredStringsInsteadOfThrowing() {
        assertFalse(hasher.verify("password".toCharArray(), null));
        assertFalse(hasher.verify("password".toCharArray(), ""));
        assertFalse(hasher.verify("password".toCharArray(), "$2a$10$abcdefghijklmnopqrstuv"));
        assertFalse(hasher.verify("password".toCharArray(),
                "$argon2id$v=19$m=notanumber,t=1,p=1$c2FsdA$aGFzaA"));
        assertFalse(hasher.verify("password".toCharArray(), Argon2PasswordHasher.timingAnchor()));
    }

    @Test
    void refusesEmptyOrOversizedPasswords() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> hasher.hash(new char[0]));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> hasher.hash(new char[5000]));
    }
}
