package com.hamiltel.gmlc.capability;

public class UnknownTenantException extends RuntimeException {

    private final long tenantId;

    public UnknownTenantException(long tenantId) {
        super("no capability profile for tenant " + tenantId);
        this.tenantId = tenantId;
    }

    public long tenantId() {
        return tenantId;
    }
}
