package com.hamiltel.gmlc.admin;

import com.hamiltel.gmlc.config.GmlcProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one check that guards operator endpoints. A blank configured key
 * disables the surface entirely, so an unfinished deployment cannot be
 * administered; comparison is constant-time.
 */
@Component
public class AdminKeyGuard {

    private final GmlcProperties properties;

    public AdminKeyGuard(GmlcProperties properties) {
        this.properties = properties;
    }

    public void require(String presented) {
        String configured = properties.adminKey();
        if (configured == null || configured.isBlank()) {
            throw new AdminApiDisabledException();
        }
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8))) {
            throw new AdminKeyRejectedException();
        }
    }
}
