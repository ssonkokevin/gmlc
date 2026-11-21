package com.hamiltel.gmlc.ops;

/** The session is valid but the role does not carry the permission. */
public class OpsForbiddenException extends RuntimeException {

    private final OpsPermission permission;

    public OpsForbiddenException(OpsPermission permission) {
        super("permission " + permission + " is not granted to this role");
        this.permission = permission;
    }

    public OpsPermission permission() {
        return permission;
    }
}
