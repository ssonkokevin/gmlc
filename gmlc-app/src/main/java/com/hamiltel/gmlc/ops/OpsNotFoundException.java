package com.hamiltel.gmlc.ops;

public class OpsNotFoundException extends RuntimeException {

    private final String resource;

    public OpsNotFoundException(String resource) {
        super(resource + " not found");
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }
}
