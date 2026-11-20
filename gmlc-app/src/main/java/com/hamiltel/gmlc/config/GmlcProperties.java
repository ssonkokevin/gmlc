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
 */
@ConfigurationProperties(prefix = "gmlc")
public record GmlcProperties(
        long defaultTenantId,
        String bundledCapabilityProfile,
        String version) {
}
