package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the first administrator from deployment settings when the console has
 * no users. It never updates an existing account, so a stale bootstrap
 * password in the environment cannot reset an administrator later. If any of
 * the three values is missing nothing is created and the console stays closed
 * until someone inserts an account deliberately.
 */
@Component
public class OpsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpsBootstrap.class);

    private final OpsUserRepository users;
    private final Argon2PasswordHasher hasher;
    private final OpsProperties properties;
    private final GmlcProperties gmlc;
    private final AuditTrail audit;

    public OpsBootstrap(OpsUserRepository users, Argon2PasswordHasher hasher,
                        OpsProperties properties, GmlcProperties gmlc, AuditTrail audit) {
        this.users = users;
        this.hasher = hasher;
        this.properties = properties;
        this.gmlc = gmlc;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = trim(properties.bootstrapUsername());
        if (username == null) {
            return;
        }
        String password = properties.bootstrapPassword();
        String totpSecret = trim(properties.bootstrapTotpSecret());
        if (password == null || password.isBlank() || totpSecret == null) {
            log.warn("gmlc.ops.bootstrap-username is set but the password or TOTP secret is not;"
                    + " no bootstrap account was created");
            return;
        }
        try {
            Totp.decode(totpSecret);
        } catch (IllegalArgumentException e) {
            log.error("gmlc.ops.bootstrap-totp-secret is not valid base32; no bootstrap account"
                    + " was created");
            return;
        }

        long tenantId = gmlc.defaultTenantId();
        String normalised = username.toLowerCase(Locale.ROOT);
        if (users.findByUsername(tenantId, normalised).isPresent()) {
            return;
        }
        UUID userId = UUID.randomUUID();
        users.insert(userId, tenantId, normalised, "Bootstrap administrator", OpsRole.ADMIN,
                hasher.hash(password.toCharArray()), totpSecret, true);
        audit.recordOperator(tenantId, userId, "OPS_USER_CREATED", AuditTrail.Outcome.ALLOWED,
                Map.of("source", "BOOTSTRAP", "role", OpsRole.ADMIN.name()));
        log.info("created the bootstrap administrator '{}'", normalised);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
