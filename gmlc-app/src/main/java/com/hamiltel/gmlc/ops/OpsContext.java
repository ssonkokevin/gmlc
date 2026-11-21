package com.hamiltel.gmlc.ops;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated operator behind a request. It is put on the request by
 * {@link OpsSessionFilter} only after the session row has been checked, so a
 * controller can trust it the way the Le controllers trust {@code @CurrentClient}.
 */
public record OpsContext(OpsUser user, UUID sessionId, String csrfToken) {

    public static final String ATTRIBUTE = OpsContext.class.getName();

    public OpsRole role() {
        return user.role();
    }

    public long tenantId() {
        return user.tenantId();
    }

    public Set<OpsPermission> permissions() {
        return EnumSet.copyOf(user.role().permissions());
    }

    public boolean allows(OpsPermission permission) {
        return user.role().allows(permission);
    }
}
