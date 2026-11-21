package com.hamiltel.gmlc.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RFC 6238 test vectors, truncated from eight to six digits (the app
 * convention). A wrong implementation of the counter, the HMAC or the
 * truncation cannot pass these.
 */
class TotpTest {

    // Base32 of the RFC's ASCII secret "12345678901234567890".
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheRfc6238VectorsAtSixDigits() {
        assertEquals("287082", Totp.code(SECRET, Instant.ofEpochSecond(59)));
        assertEquals("081804", Totp.code(SECRET, Instant.ofEpochSecond(1111111109)));
        assertEquals("050471", Totp.code(SECRET, Instant.ofEpochSecond(1111111111)));
        assertEquals("005924", Totp.code(SECRET, Instant.ofEpochSecond(1234567890)));
        assertEquals("279037", Totp.code(SECRET, Instant.ofEpochSecond(2000000000)));
        assertEquals("353130", Totp.code(SECRET, Instant.ofEpochSecond(20000000000L)));
    }

    @Test
    void acceptsOneStepOfClockSkewAndRejectsMore() {
        Instant at = Instant.ofEpochSecond(1_700_000_000);
        String code = Totp.code(SECRET, at);
        assertTrue(Totp.verify(SECRET, code, at));
        assertTrue(Totp.verify(SECRET, code, at.plusSeconds(29)));
        assertFalse(Totp.verify(SECRET, code, at.plusSeconds(120)));
    }

    @Test
    void rejectsMalformedCodesAndSecrets() {
        Instant at = Instant.ofEpochSecond(59);
        assertFalse(Totp.verify(SECRET, null, at));
        assertFalse(Totp.verify(SECRET, "12345", at));
        assertFalse(Totp.verify(SECRET, "12345a", at));
        assertFalse(Totp.verify(SECRET, "287082 ", at.plusSeconds(300)));
        assertFalse(Totp.verify("not base32!", "287082", at));
    }

    @Test
    void roundTripsBase32() {
        String secret = Totp.generateSecret();
        assertEquals(32, secret.length());
        assertEquals(secret, Totp.base32(Totp.decode(secret)));
        assertTrue(Totp.verify(secret, Totp.code(secret, Instant.now()), Instant.now()));
    }
}
