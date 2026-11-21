package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.ss7.M3uaTransport;
import com.hamiltel.gmlc.ss7.Sccp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A loopback M3UA link for the MAP tests: it parses each Begin, hands the
 * Invoke to the reply registered for that operation, and returns an End that
 * carries the same transaction identifier. A test can answer a component (the
 * link wraps it) or a raw TCAP payload when it is testing malformed answers.
 */
final class MapTestLink implements M3uaTransport {

    private final Map<Integer, BiFunction<Tcap.Message, Tcap.Invoke, Tcap.Component>> replies =
            new HashMap<>();
    private final Map<Integer, BiFunction<Tcap.Message, Tcap.Invoke, byte[]>> rawReplies =
            new HashMap<>();

    final List<Integer> destinationPointCodes = new ArrayList<>();
    final List<Sccp.UnitData> requests = new ArrayList<>();
    final List<Tcap.Invoke> invokes = new ArrayList<>();
    int exchanges;

    MapTestLink answer(int opCode,
                       BiFunction<Tcap.Message, Tcap.Invoke, Tcap.Component> reply) {
        replies.put(opCode, reply);
        return this;
    }

    MapTestLink raw(int opCode, BiFunction<Tcap.Message, Tcap.Invoke, byte[]> reply) {
        rawReplies.put(opCode, reply);
        return this;
    }

    @Override
    public Sccp.UnitData exchange(int destinationPointCode, Sccp.UnitData request,
                                  Duration timeout) {
        exchanges++;
        Tcap.Message dialogue = Tcap.parse(request.data());
        Tcap.Invoke invoke = (Tcap.Invoke) dialogue.singleComponent();
        destinationPointCodes.add(destinationPointCode);
        requests.add(request);
        invokes.add(invoke);
        byte[] payload;
        if (rawReplies.containsKey(invoke.opCode())) {
            payload = rawReplies.get(invoke.opCode()).apply(dialogue, invoke);
        } else {
            BiFunction<Tcap.Message, Tcap.Invoke, Tcap.Component> reply =
                    replies.get(invoke.opCode());
            if (reply == null) {
                throw new AssertionError("unexpected MAP operation " + invoke.opCode());
            }
            payload = end(dialogue, reply.apply(dialogue, invoke));
        }
        return new Sccp.UnitData(request.callingParty(), request.calledParty(), payload);
    }

    static byte[] end(Tcap.Message dialogue, Tcap.Component component) {
        int transactionId = dialogue.transactionIdValue();
        return Tcap.message(Tcap.END, transactionId, transactionId, null, component, false);
    }
}
