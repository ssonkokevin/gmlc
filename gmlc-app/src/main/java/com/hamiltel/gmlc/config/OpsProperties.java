package com.hamiltel.gmlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operator-console settings. They are deployment settings, not tenant data:
 * the Argon2 cost, the two session clocks, the lockout threshold and the
 * optional bootstrap account that seeds the first administrator.
 *
 * @param cookieSecure        mark the session cookie Secure; leave it true in
 *                            production and set it false only for a plain-HTTP
 *                            lab, otherwise the browser will not return it
 * @param sessionIdleMinutes  how long a session survives without a request
 * @param sessionAbsoluteHours hard cap on a session's life, however active
 * @param lockoutThreshold    failed logins before the account locks
 * @param lockoutMinutes      how long the lock lasts
 * @param totpIssuer          issuer shown in the authenticator app
 * @param totpPeriodSeconds   TOTP time step (RFC 6238 default 30 s)
 * @param argon2MemoryKib     Argon2 memory cost in KiB (OWASP 19 MiB)
 * @param argon2Iterations    Argon2 time cost (OWASP 2)
 * @param argon2Parallelism   Argon2 lanes (OWASP 1)
 * @param bootstrapUsername   create this administrator on startup if absent
 * @param bootstrapPassword   its initial password; never committed
 * @param bootstrapTotpSecret its base32 TOTP secret; never committed
 */
@ConfigurationProperties(prefix = "gmlc.ops")
public record OpsProperties(
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("15") long sessionIdleMinutes,
        @DefaultValue("8") long sessionAbsoluteHours,
        @DefaultValue("5") int lockoutThreshold,
        @DefaultValue("15") long lockoutMinutes,
        @DefaultValue("Hamiltel GMLC") String totpIssuer,
        @DefaultValue("30") int totpPeriodSeconds,
        @DefaultValue("19456") int argon2MemoryKib,
        @DefaultValue("2") int argon2Iterations,
        @DefaultValue("1") int argon2Parallelism,
        String bootstrapUsername,
        String bootstrapPassword,
        String bootstrapTotpSecret) {
}
