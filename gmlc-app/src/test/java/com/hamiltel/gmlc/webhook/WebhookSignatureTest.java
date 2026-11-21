package com.hamiltel.gmlc.webhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureTest {

    @Test
    void hmacMatchesTheRfc4231Vector() {
        // RFC 4231 test case 1: HMAC-SHA256 over "Hi There" with 20 0x0b bytes.
        String secret = "\u000b".repeat(20);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                WebhookSignature.hex(secret, "Hi There"));
    }

    @Test
    void theSignatureCoversTheTimestampAndTheExactBody() {
        String secret = "0123456789abcdef0123456789abcdef";
        long timestamp = 1_700_000_000L;
        String body = "{\"event\":\"ENTER\"}";

        String signature = WebhookSignature.sign(secret, timestamp, body);
        assertTrue(signature.startsWith("v1="));
        assertEquals(3 + 64, signature.length());
        assertTrue(WebhookSignature.verify(secret, timestamp, body, signature));

        assertFalse(WebhookSignature.verify(secret, timestamp + 1, body, signature));
        assertFalse(WebhookSignature.verify(secret, timestamp, body + " ", signature));
        assertFalse(WebhookSignature.verify("other-secret", timestamp, body, signature));
        assertFalse(WebhookSignature.verify(secret, timestamp, body, null));
    }
}
