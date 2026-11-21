package com.hamiltel.gmlc.location;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Which core-network face answers a Le request. A deployment normally uses
 * one: Diameter SLh/SLg towards an HSS and an MME, or MAP towards an HLR (and
 * MSC/SGSN) over SS7. Choosing MAP does not change the cell plan or the
 * result envelope, only the signalling that obtains the serving cell.
 */
@ConfigurationProperties(prefix = "gmlc.location")
public record LocationProperties(String transport) {

    public LocationProperties {
        transport = transport == null || transport.isBlank()
                ? "DIAMETER"
                : transport.trim().toUpperCase(Locale.ROOT);
        if (!transport.equals("DIAMETER") && !transport.equals("MAP")) {
            throw new IllegalArgumentException(
                    "gmlc.location.transport must be DIAMETER or MAP, not " + transport);
        }
    }

    public boolean map() {
        return transport.equals("MAP");
    }
}
