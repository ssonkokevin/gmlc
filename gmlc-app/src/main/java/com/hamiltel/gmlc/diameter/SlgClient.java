package com.hamiltel.gmlc.diameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Provide-Location over SLg (TS 29.172 section 7.3.1). The request asks for the
 * current or last known location; a Phase 1 MME answers with the serving cell,
 * which the cell database then turns into a bounded position. A PLA that
 * carries only a GAD shape has no cell in it, and this client says so instead
 * of inventing one.
 */
public class SlgClient {

    private final DiameterTransport transport;
    private final DiameterProperties properties;

    public SlgClient(DiameterTransport transport, DiameterProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    /**
     * @param lcsClientName the name shown to the MME as the requesting client
     * @param emergency     whether this is an emergency request; it becomes the
     *                      LCS-Client-Type the MME applies its privacy rules to
     */
    public Optional<ServingCell> provideLocation(ServingNode node, SubscriberId subscriber,
                                                 int accuracyMeters, String lcsClientName,
                                                 boolean emergency) {
        List<Avp> avps = new ArrayList<>();
        avps.add(Avp.baseUtf8(DiameterDictionary.SESSION_ID, SessionId.next(properties.originHost())));
        avps.add(Avp.baseU32(DiameterDictionary.AUTH_SESSION_STATE,
                DiameterDictionary.AUTH_SESSION_STATE_NO_STATE_MAINTAINED));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, properties.originHost()));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_REALM, properties.originRealm()));
        avps.add(Avp.baseUtf8(DiameterDictionary.DESTINATION_HOST, node.diameterName()));
        avps.add(Avp.baseUtf8(DiameterDictionary.DESTINATION_REALM, node.realm()));
        avps.add(Avp.vendorU32(DiameterDictionary.SLG_LOCATION_TYPE,
                DiameterDictionary.SLG_LOCATION_TYPE_CURRENT_OR_LAST_KNOWN));
        if (subscriber.hasImsi()) {
            avps.add(Avp.baseUtf8(DiameterDictionary.USER_NAME, subscriber.imsi()));
        }
        if (subscriber.msisdn() != null) {
            avps.add(Avp.vendor(DiameterDictionary.MSISDN, Tbcd.encode(subscriber.msisdn())));
        }
        avps.add(Avp.vendorGrouped(DiameterDictionary.LCS_EPS_CLIENT_NAME,
                Avp.vendorUtf8(DiameterDictionary.LCS_NAME_STRING, lcsClientName),
                Avp.vendorU32(DiameterDictionary.LCS_FORMAT_INDICATOR, 0)));
        avps.add(Avp.vendorU32(DiameterDictionary.LCS_CLIENT_TYPE,
                emergency ? DiameterDictionary.LCS_CLIENT_TYPE_EMERGENCY
                          : DiameterDictionary.LCS_CLIENT_TYPE_VALUE_ADDED));
        if (accuracyMeters > 0) {
            avps.add(Avp.vendorGrouped(DiameterDictionary.LCS_QOS,
                    Avp.vendorU32(DiameterDictionary.HORIZONTAL_ACCURACY,
                            UncertaintyCode.codeForMetres(accuracyMeters)),
                    Avp.vendorU32(DiameterDictionary.RESPONSE_TIME,
                            DiameterDictionary.RESPONSE_TIME_LOW_DELAY)));
        }

        DiameterMessage answer = transport.exchange(
                DiameterMessage.request(DiameterDictionary.PROVIDE_LOCATION,
                        DiameterDictionary.SLG_APPLICATION_ID, avps),
                Duration.ofMillis(properties.timeoutMs()));
        DiameterResult.requireSuccess(answer, DiameterDictionary.PROVIDE_LOCATION, "Provide-Location");

        Boolean fulfilled = null;
        Integer indicator = answer.integer(DiameterDictionary.ACCURACY_FULFILMENT_INDICATOR);
        if (indicator != null) {
            fulfilled = indicator == DiameterDictionary.ACCURACY_FULFILLED;
        }
        Integer age = answer.integer(DiameterDictionary.AGE_OF_LOCATION_ESTIMATE);

        Optional<Avp> ecgi = answer.first(DiameterDictionary.ECGI);
        if (ecgi.isPresent()) {
            return Optional.of(lteCell(ecgi.get().fixedValue(7), fulfilled, age));
        }
        Optional<Avp> cgi = answer.first(DiameterDictionary.CELL_GLOBAL_IDENTITY);
        if (cgi.isPresent()) {
            return Optional.of(gsmCell(cgi.get().fixedValue(7), fulfilled, age));
        }
        return Optional.empty();
    }

    private static ServingCell lteCell(byte[] ecgi, Boolean fulfilled, Integer age) {
        if (ecgi.length != 7) {
            throw new SignallingException("DIAMETER_PROTOCOL_ERROR",
                    "ECGI is " + ecgi.length + " octets, expected 7");
        }
        PlmnCodec.Plmn plmn = PlmnCodec.decode(new byte[] {ecgi[0], ecgi[1], ecgi[2]});
        if ((ecgi[3] & 0xF0) != 0) {
            throw new SignallingException("DIAMETER_PROTOCOL_ERROR",
                    "ECGI spare bits are not zero");
        }
        // The 28-bit ECI starts at bit 4 of the fourth octet (TS 29.274 8.21.5).
        long eci = ((long) (ecgi[3] & 0x0F) << 24) | ((ecgi[4] & 0xFF) << 16)
                | ((ecgi[5] & 0xFF) << 8) | (ecgi[6] & 0xFF);
        return new ServingCell("LTE", plmn.mcc(), plmn.mnc(), null,
                Long.toString(eci), fulfilled, age);
    }

    private static ServingCell gsmCell(byte[] cgi, Boolean fulfilled, Integer age) {
        if (cgi.length != 7) {
            throw new SignallingException("DIAMETER_PROTOCOL_ERROR",
                    "Cell-Global-Identity is " + cgi.length + " octets, expected 7");
        }
        PlmnCodec.Plmn plmn = PlmnCodec.decode(new byte[] {cgi[0], cgi[1], cgi[2]});
        int lac = ((cgi[3] & 0xFF) << 8) | (cgi[4] & 0xFF);
        int ci = ((cgi[5] & 0xFF) << 8) | (cgi[6] & 0xFF);
        return new ServingCell("GSM", plmn.mcc(), plmn.mnc(), Integer.toString(lac),
                Integer.toString(ci), fulfilled, age);
    }
}
