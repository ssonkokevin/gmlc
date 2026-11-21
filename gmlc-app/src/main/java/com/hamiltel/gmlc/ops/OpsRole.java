package com.hamiltel.gmlc.ops;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.hamiltel.gmlc.ops.OpsPermission.ACK_ALERTS;
import static com.hamiltel.gmlc.ops.OpsPermission.EXPORT_CDR;
import static com.hamiltel.gmlc.ops.OpsPermission.LOCATE;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_CLIENTS;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_CONFIG;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_GEOFENCES;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_LICENCE;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_NOTIFIERS;
import static com.hamiltel.gmlc.ops.OpsPermission.MANAGE_USERS;
import static com.hamiltel.gmlc.ops.OpsPermission.REVEAL_IDENTIFIERS;
import static com.hamiltel.gmlc.ops.OpsPermission.VIEW_DASHBOARD;
import static com.hamiltel.gmlc.ops.OpsPermission.VIEW_RAW_SIGNALLING;
import static com.hamiltel.gmlc.ops.OpsPermission.VIEW_REQUESTS;

/**
 * The four console roles. ANALYST can read and export but never place a
 * request; VIEWER can only read. Only ADMIN manages users, clients, config and
 * the licence. No role can bypass consent: every locate goes through the same
 * service as the Le API.
 */
public enum OpsRole {

    ADMIN(EnumSet.allOf(OpsPermission.class)),

    OPERATOR(EnumSet.of(VIEW_DASHBOARD, VIEW_REQUESTS, ACK_ALERTS, LOCATE,
            REVEAL_IDENTIFIERS, VIEW_RAW_SIGNALLING, MANAGE_GEOFENCES, MANAGE_NOTIFIERS)),

    ANALYST(EnumSet.of(VIEW_DASHBOARD, VIEW_REQUESTS, EXPORT_CDR)),

    VIEWER(EnumSet.of(VIEW_DASHBOARD, VIEW_REQUESTS));

    private final Set<OpsPermission> permissions;

    OpsRole(Set<OpsPermission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public boolean allows(OpsPermission permission) {
        return permissions.contains(permission);
    }

    public Set<OpsPermission> permissions() {
        return permissions;
    }
}
