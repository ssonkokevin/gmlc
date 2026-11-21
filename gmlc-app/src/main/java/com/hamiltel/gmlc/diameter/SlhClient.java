package com.hamiltel.gmlc.diameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LCS-Routing-Info over SLh (TS 29.173 section 6.2.3). The HSS answers with the
 * serving node, not a position: for EPS that node is the MME the following
 * Provide-Location request is addressed to.
 */
public class SlhClient {

    private final DiameterTransport transport;
    private final DiameterProperties properties;

    public SlhClient(DiameterTransport transport, DiameterProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    /**
     * @return the serving MME, or empty when the HSS answered successfully but
     *         named no node this gateway can address
     */
    public Optional<ServingNode> routeInfo(SubscriberId subscriber) {
        List<Avp> avps = new ArrayList<>();
        avps.add(Avp.baseUtf8(DiameterDictionary.SESSION_ID, SessionId.next(properties.originHost())));
        avps.add(Avp.baseU32(DiameterDictionary.AUTH_SESSION_STATE,
                DiameterDictionary.AUTH_SESSION_STATE_NO_STATE_MAINTAINED));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, properties.originHost()));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_REALM, properties.originRealm()));
        avps.add(Avp.baseUtf8(DiameterDictionary.DESTINATION_REALM, properties.originRealm()));
        if (subscriber.hasImsi()) {
            avps.add(Avp.baseUtf8(DiameterDictionary.USER_NAME, subscriber.imsi()));
        }
        if (subscriber.msisdn() != null) {
            avps.add(Avp.vendor(DiameterDictionary.MSISDN, Tbcd.encode(subscriber.msisdn())));
        }

        DiameterMessage answer = transport.exchange(
                DiameterMessage.request(DiameterDictionary.LCS_ROUTING_INFO,
                        DiameterDictionary.SLH_APPLICATION_ID, avps),
                Duration.ofMillis(properties.timeoutMs()));
        DiameterResult.requireSuccess(answer, DiameterDictionary.LCS_ROUTING_INFO, "LCS-Routing-Info");

        return answer.first(DiameterDictionary.SERVING_NODE)
                .map(Avp::children)
                .flatMap(SlhClient::addressableNode);
    }

    private static Optional<ServingNode> addressableNode(List<Avp> servingNode) {
        for (Avp avp : servingNode) {
            if (avp.code() == DiameterDictionary.MME_NAME) {
                return Optional.of(ServingNode.of(avp.asText()));
            }
        }
        // An MSC or SGSN number is an E.164 number for MAP addressing, not a
        // Diameter host; the 2G/3G path uses MAP and never needs this answer.
        return Optional.empty();
    }
}
