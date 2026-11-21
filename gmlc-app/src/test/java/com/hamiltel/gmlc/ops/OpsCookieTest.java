package com.hamiltel.gmlc.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsCookieTest {

    @Test
    void theCookieIsHttpOnlyStrictAndScopedToTheConsole() {
        String header = OpsSessionFilter.cookieHeader("token-value", 600, true);
        assertTrue(header.startsWith("GMLC_OPS=token-value;"));
        assertTrue(header.contains("; Path=/v1/ops;"));
        assertTrue(header.contains("; HttpOnly;"));
        assertTrue(header.contains("; SameSite=Strict"));
        assertTrue(header.contains("; Max-Age=600"));
        assertTrue(header.contains("; Secure"));
    }

    @Test
    void secureCanBeDroppedOnlyExplicitly() {
        String lab = OpsSessionFilter.cookieHeader("token-value", 0, false);
        assertFalse(lab.contains("Secure"));
        assertTrue(lab.contains("Max-Age=0"));
    }
}
