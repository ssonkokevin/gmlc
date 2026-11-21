package com.hamiltel.gmlc.ops;

/**
 * What an operator may do. Permissions are explicit and checked per route, so
 * a role is only a named set - widening a role is a visible edit here, not a
 * hidden effect of some other change.
 */
public enum OpsPermission {
    VIEW_DASHBOARD,
    VIEW_REQUESTS,
    ACK_ALERTS,
    LOCATE,
    REVEAL_IDENTIFIERS,
    VIEW_RAW_SIGNALLING,
    EXPORT_CDR,
    MANAGE_GEOFENCES,
    MANAGE_CLIENTS,
    MANAGE_CONFIG,
    MANAGE_USERS,
    MANAGE_LICENCE
}
