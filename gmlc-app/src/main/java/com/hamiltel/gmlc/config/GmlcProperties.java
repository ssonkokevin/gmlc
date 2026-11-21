package com.hamiltel.gmlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment settings that are not per-tenant data.
 *
 * @param defaultTenantId          tenant served when the caller is not scoped
 *                                 to a tenant yet (Hamiltel is 1)
 * @param bundledCapabilityProfile profile loaded when the database has no
 *                                 row for the tenant
 * @param version                  reported by the health endpoint
 * @param adminKey                 operator key that guards client registration;
 *                                 blank disables the admin surface
 * @param subjectPepper            key for the pseudonymous subscriber reference;
 *                                 blank falls back to an unkeyed SHA-256
 * @param emergencyOverride        allow an EMERGENCY request without stored
 *                                 consent; the audit row records the override
 */
@ConfigurationProperties(prefix = "gmlc")
public record GmlcProperties(
        long defaultTenantId,
        String bundledCapabilityProfile,
        String version,
        String adminKey,
        String subjectPepper,
        boolean emergencyOverride) {
}
