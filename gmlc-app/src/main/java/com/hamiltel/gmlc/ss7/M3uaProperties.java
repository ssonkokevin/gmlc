package com.hamiltel.gmlc.ss7;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The gateway's own SS7 identity and the M3UA association towards the STP.
 * Point codes belong to the operator, so none has a committed default: with
 * {@code local-point-code} zero the transport is disabled and every MAP
 * request fails closed.
 *
 * @param host               the STP's M3UA address; blank disables the link
 * @param port               the STP's M3UA port
 * @param localPointCode     this gateway's point code on the SS7 network
 * @param remotePointCode    the point code used when a caller supplies none
 * @param routingContext     M3UA routing context, -1 for "the peer has one key"
 * @param localGlobalTitle   this gateway's E.164 number for the calling party;
 *                           blank sends point code and subsystem number only
 * @param networkIndicator   0 international, 2 national (Hamiltel is national)
 * @param timeoutMs          how long to wait for a peer answer
 */
@ConfigurationProperties(prefix = "gmlc.m3ua")
public record M3uaProperties(
        String host,
        int port,
        int localPointCode,
        int remotePointCode,
        int routingContext,
        String localGlobalTitle,
        int networkIndicator,
        int timeoutMs) {

    private static final int DEFAULT_PORT = 2905;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    public M3uaProperties {
        host = host == null ? "" : host.trim();
        port = port <= 0 ? DEFAULT_PORT : port;
        if (localPointCode < 0 || localPointCode > 0x3FFF) {
            throw new IllegalArgumentException("gmlc.m3ua.local-point-code must fit 14 bits");
        }
        if (remotePointCode < 0 || remotePointCode > 0x3FFF) {
            throw new IllegalArgumentException("gmlc.m3ua.remote-point-code must fit 14 bits");
        }
        if (networkIndicator < 0 || networkIndicator > 3) {
            throw new IllegalArgumentException("gmlc.m3ua.network-indicator is 0-3");
        }
        localGlobalTitle = localGlobalTitle == null ? "" : localGlobalTitle.trim();
        if (!localGlobalTitle.isEmpty() && !localGlobalTitle.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("gmlc.m3ua.local-global-title holds decimal digits only");
        }
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    public boolean configured() {
        return !host.isEmpty() && localPointCode > 0;
    }

    public Integer routingContextOrNull() {
        return routingContext < 0 ? null : routingContext;
    }

    /**
     * The calling party on an outgoing MAP dialogue. TS 29.002 says an
     * intra-PLMN request names its own point code and subsystem number; the
     * E.164 number, when configured, is added for a gateway that routes
     * between networks.
     */
    public Sccp.Address callingAddress() {
        if (localPointCode <= 0) {
            throw new IllegalStateException("no local point code is configured");
        }
        if (localGlobalTitle.isEmpty()) {
            return Sccp.Address.subsystem(localPointCode, Sccp.SSN_GMLC);
        }
        return new Sccp.Address(localPointCode, Sccp.SSN_GMLC, localGlobalTitle);
    }
}
