package com.hamiltel.gmlc.diameter;

/**
 * The serving node an HSS reports for a subscriber. For EPS this is the MME,
 * which is the node a Provide-Location request is then addressed to.
 *
 * @param diameterName the node's Diameter identity, used as Destination-Host
 * @param realm        the realm after the first dot of the identity, used as
 *                     Destination-Realm
 */
public record ServingNode(String diameterName, String realm) {

    public ServingNode {
        if (diameterName == null || diameterName.isBlank()) {
            throw new IllegalArgumentException("a serving node needs a Diameter identity");
        }
    }

    public static ServingNode of(String diameterName) {
        int dot = diameterName.indexOf('.');
        if (dot < 0 || dot == diameterName.length() - 1) {
            throw new IllegalArgumentException(
                    "serving node " + diameterName + " has no realm; a bare host name cannot be routed");
        }
        return new ServingNode(diameterName, diameterName.substring(dot + 1));
    }
}
