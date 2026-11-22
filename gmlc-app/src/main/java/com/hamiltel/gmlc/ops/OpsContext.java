package com.hamiltel.gmlc.ops;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated operator behind a request. It is put on the request by
 * {@link OpsSessionFilter} only after the session row has been checked, so a
 * controller can trust it the way the Le controllers trust {@code @CurrentClient}.
 *
 * <p>{@code breakGlass} marks a session carrying an unexpired emergency grant.
 * The assigned role is unchanged and is what the roster and the session view
 * show; the grant only adds the ADMIN permission set to this one session, and
 * an audit row and a review obligation exist for it.
 */
public record OpsContext(OpsUser user, UUID sessionId, String csrfToken,
                         boolean breakGlass, Instant breakGlassExpiresAt) {

    public static final String ATTRIBUTE = OpsContext.class.getName();

    public OpsContext(OpsUser user, UUID sessionId, String csrfToken) {
        this(user, sessionId, csrfToken, false, null);
    }

    /** The role the account was given, even while break-glass is in force. */
    public OpsRole assignedRole() {
        return user.role();
    }

    /** The role in force for permission checks, which break-glass widens. */
    public OpsRole role() {
        return breakGlass ? OpsRole.ADMIN : user.role();
    }

    public long tenantId() {
        return user.tenantId();
    }

    public Set<OpsPermission> permissions() {
        return EnumSet.copyOf(role().permissions());
    }

    public boolean allows(OpsPermission permission) {
        return role().allows(permission);
    }
}
