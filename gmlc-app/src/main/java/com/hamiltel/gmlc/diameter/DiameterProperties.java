package com.hamiltel.gmlc.diameter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Diameter identity and peer for the signalling clients.
 *
 * @param originHost the gateway's own Diameter identity
 * @param originRealm the gateway's own realm
 * @param peerHost    the DRA or HSS the gateway sends to; blank means the
 *                    signalling clients are not configured and every location
 *                    request fails closed
 * @param peerPort    the peer's Diameter port
 * @param timeoutMs   how long to wait for an answer
 */
@ConfigurationProperties(prefix = "gmlc.diameter")
public record DiameterProperties(
        String originHost,
        String originRealm,
        String peerHost,
        int peerPort,
        int timeoutMs) {

    private static final String DEFAULT_ORIGIN_HOST = "gmlc.local";
    private static final String DEFAULT_ORIGIN_REALM = "local";
    private static final int DEFAULT_PORT = 3868;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    public DiameterProperties {
        originHost = blank(originHost) ? DEFAULT_ORIGIN_HOST : originHost.trim();
        originRealm = blank(originRealm) ? DEFAULT_ORIGIN_REALM : originRealm.trim();
        peerHost = peerHost == null ? "" : peerHost.trim();
        peerPort = peerPort <= 0 ? DEFAULT_PORT : peerPort;
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    public boolean configured() {
        return !peerHost.isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
