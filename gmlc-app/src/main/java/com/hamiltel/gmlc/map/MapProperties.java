package com.hamiltel.gmlc.map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The identity and addressing the MAP face needs. The MLC number is the
 * gateway's own E.164 number as the HLR and the serving node see it, and it is
 * carried in the SRILCS and PSL arguments; point codes belong to the operator,
 * so a zero HLR point code means "route on subsystem 6 at the M3UA peer".
 *
 * @param mlcNumber    this gateway's E.164 LCS number; blank disables the MAP
 *                     face and every MAP request fails closed
 * @param hlrPointCode the HLR's point code, or zero to route on SSN 6 at the
 *                     M3UA peer's point code
 */
@ConfigurationProperties(prefix = "gmlc.map")
public record MapProperties(String mlcNumber, int hlrPointCode) {

    public MapProperties {
        mlcNumber = mlcNumber == null ? "" : mlcNumber.trim();
        if (!mlcNumber.isEmpty()
                && (mlcNumber.length() < 5 || mlcNumber.length() > 15
                || !mlcNumber.chars().allMatch(c -> c >= '0' && c <= '9'))) {
            throw new IllegalArgumentException(
                    "gmlc.map.mlc-number is an E.164 number of 5 to 15 decimal digits");
        }
        if (hlrPointCode < 0 || hlrPointCode > 0x3FFF) {
            throw new IllegalArgumentException("gmlc.map.hlr-point-code must fit 14 bits");
        }
    }

    public boolean configured() {
        return !mlcNumber.isEmpty();
    }
}
