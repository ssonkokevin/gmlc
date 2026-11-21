package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.ss7.M3uaProperties;
import com.hamiltel.gmlc.ss7.M3uaTransport;
import com.hamiltel.gmlc.ss7.Sccp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The MAP location client: one TCAP dialogue per call. SRILCS is asked at the
 * HLR to find the serving node, PSL is asked at that node for the cell (an
 * MSC or SGSN addressed by its E.164 number), and ATI is asked at the HLR when
 * the subscriber is on an MME, because an MME is not a MAP PSL peer.
 *
 * <p>A MAP error becomes a {@link MapException} whose code is the TS 29.002
 * error name, and a malformed or mismatched answer is a code of its own; the
 * caller never receives a blank result that could be mistaken for a position.
 */
public final class MapClient {

    private static final int INVOKE_ID = 1;
    private static final int TRANSACTION_ID_CEILING = 1 << 24;

    private final M3uaTransport transport;
    private final MapProperties map;
    private final M3uaProperties m3ua;
    private final AtomicInteger transactionIds =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1, TRANSACTION_ID_CEILING));

    public MapClient(M3uaTransport transport, MapProperties map, M3uaProperties m3ua) {
        this.transport = transport;
        this.map = map;
        this.m3ua = m3ua;
    }

    public MapLcs.RoutingInfo routeInfo(String imsi, String msisdn) {
        String[] target = target(imsi, msisdn);
        byte[] answer = call(MapLcs.AC_LOCATION_SERVICE_GATEWAY, hlrAddress(), hlrPointCode(),
                MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                MapLcs.routingInfoForLcs(mlcNumber(), target[0], target[1]),
                "sendRoutingInfoForLCS");
        try {
            return MapLcs.parseRoutingInfoForLcs(answer);
        } catch (BerException e) {
            throw new MapException("MAP_DECODE",
                    "the routing answer is malformed: " + e.getMessage(), e);
        }
    }

    /** PSL at the serving node, reached by global title because the HLR named it. */
    public MapLcs.ProvideSubscriberLocationResult provideLocation(String networkNode, boolean gprs,
                                                                 String imsi, String msisdn,
                                                                 Integer accuracyMetres,
                                                                 String clientName) {
        if (networkNode == null || networkNode.isBlank()) {
            throw new MapException("MAP_NO_SERVING_NODE",
                    "the HLR named no MSC or SGSN to ask for a location");
        }
        Sccp.Address called = Sccp.Address.globalTitle(
                gprs ? Sccp.SSN_SGSN : Sccp.SSN_MSC, networkNode);
        String[] target = target(imsi, msisdn);
        byte[] answer = call(MapLcs.AC_LOCATION_SERVICE_GATEWAY, called, m3ua.remotePointCode(),
                MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                MapLcs.provideSubscriberLocation(mlcNumber(), target[0], target[1],
                        accuracyMetres, clientName),
                "provideSubscriberLocation");
        try {
            return MapLcs.parseProvideSubscriberLocation(answer);
        } catch (BerException e) {
            throw new MapException("MAP_DECODE",
                    "the provide-subscriber-location answer is malformed: " + e.getMessage(), e);
        }
    }

    /**
     * ATI is an HLR operation even when the answer comes from an MME, so this
     * is addressed at the HLR exactly like SRILCS.
     */
    public MapLcs.AnyTimeResult anyTime(String imsi, String msisdn) {
        String[] target = target(imsi, msisdn);
        byte[] answer = call(MapLcs.AC_ANY_TIME_ENQUIRY, hlrAddress(), hlrPointCode(),
                MapLcs.OP_ANY_TIME_INTERROGATION,
                MapLcs.anyTimeInterrogation(mlcNumber(), target[0], target[1], true, false),
                "anyTimeInterrogation");
        try {
            return MapLcs.parseAnyTimeInterrogation(answer);
        } catch (BerException e) {
            throw new MapException("MAP_DECODE",
                    "the any-time answer is malformed: " + e.getMessage(), e);
        }
    }

    private byte[] call(byte[] applicationContext, Sccp.Address called, int destinationPointCode,
                        int opCode, byte[] argument, String operation) {
        if (!map.configured()) {
            throw new MapException("SIGNALLING_NOT_CONFIGURED",
                    "gmlc.map.mlc-number is not set");
        }
        if (!m3ua.configured()) {
            throw new MapException("SIGNALLING_NOT_CONFIGURED",
                    "no M3UA signalling gateway is configured; set GMLC_M3UA_HOST and "
                            + "GMLC_M3UA_LOCAL_PC");
        }
        int transactionId = nextTransactionId();
        byte[] begin = Tcap.begin(transactionId, applicationContext,
                new Tcap.Invoke(INVOKE_ID, null, opCode, argument));
        Sccp.UnitData request = new Sccp.UnitData(called, m3ua.callingAddress(), begin);
        Sccp.UnitData answer = transport.exchange(destinationPointCode, request,
                Duration.ofMillis(m3ua.timeoutMs()));
        return result(answer.data(), transactionId, applicationContext, opCode, operation);
    }

    private static byte[] result(byte[] data, int transactionId, byte[] applicationContext,
                                 int expectedOp, String operation) {
        Tcap.Message answer;
        try {
            answer = Tcap.parse(data);
        } catch (BerException e) {
            throw new MapException("TCAP_DECODE",
                    "the answer is not a TCAP message: " + e.getMessage(), e);
        }
        if (answer.type() != Tcap.END && answer.type() != Tcap.CONTINUE) {
            throw new MapException("TCAP_UNEXPECTED",
                    operation + " answered with transaction portion 0x"
                            + Integer.toHexString(answer.type()));
        }
        if (answer.destinationTransactionId() != null
                && Tcap.transactionIdValue(answer.destinationTransactionId()) != transactionId) {
            throw new MapException("TCAP_TRANSACTION_MISMATCH",
                    operation + " answered a different dialogue");
        }
        if (!answer.dialogueAccepted()) {
            throw new MapException("TCAP_DIALOGUE_REFUSED",
                    operation + " dialogue was not accepted");
        }
        if (answer.applicationContext() != null
                && !answer.applicationContext().equals(arcs(applicationContext))) {
            throw new MapException("TCAP_APPLICATION_CONTEXT",
                    operation + " answered in another application context");
        }
        Tcap.Component component = answer.singleComponent();
        if (component instanceof Tcap.ReturnError error) {
            String code = MapLcs.errorName(error.errorCode());
            throw new MapException(code, operation + " failed: " + code);
        }
        if (!(component instanceof Tcap.ReturnResult returned)) {
            throw new MapException("TCAP_UNEXPECTED_COMPONENT",
                    operation + " answered with a component this client did not ask for");
        }
        if (returned.invokeId() != INVOKE_ID || returned.opCode() != expectedOp) {
            throw new MapException("TCAP_UNEXPECTED_COMPONENT",
                    "the answer does not return invoke " + INVOKE_ID + " of operation "
                            + expectedOp);
        }
        if (returned.result() == null) {
            throw new MapException("MAP_EMPTY_RESULT", operation + " returned no result");
        }
        return returned.result();
    }

    /** MAP takes exactly one subscriber identity; the IMSI is the stabler one. */
    private static String[] target(String imsi, String msisdn) {
        if (imsi != null) {
            return new String[] {imsi, null};
        }
        if (msisdn != null) {
            return new String[] {null, msisdn};
        }
        throw new MapException("MAP_TARGET", "a request needs an IMSI or an MSISDN");
    }

    private Sccp.Address hlrAddress() {
        return map.hlrPointCode() > 0
                ? Sccp.Address.subsystem(map.hlrPointCode(), Sccp.SSN_HLR)
                : Sccp.Address.subsystem(Sccp.SSN_HLR);
    }

    private int hlrPointCode() {
        return map.hlrPointCode() > 0 ? map.hlrPointCode() : m3ua.remotePointCode();
    }

    private String mlcNumber() {
        if (!map.configured()) {
            throw new MapException("SIGNALLING_NOT_CONFIGURED",
                    "gmlc.map.mlc-number is not set");
        }
        return map.mlcNumber();
    }

    private int nextTransactionId() {
        return transactionIds.updateAndGet(
                value -> value % (TRANSACTION_ID_CEILING - 1) + 1);
    }

    private static List<Integer> arcs(byte[] oid) {
        int[] values = Ber.parse(oid).oidArcs();
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }
}
