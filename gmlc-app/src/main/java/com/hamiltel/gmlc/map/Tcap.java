package com.hamiltel.gmlc.map;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TCAP as MAP uses it (Q.773, 1997 edition): a Begin carrying an AARQ dialogue
 * with the MAP application-context name, Invoke components inside a component
 * portion, and an End carrying the AARE and the ReturnResult. Local operation
 * and error codes are plain BER INTEGERs, which is what MAP peers put on the
 * wire; only the ASN.1 shape is TAGged (the dialogue and component portions).
 *
 * <p>Return Result Not Last and Reject are recognised but not produced: a MAP
 * LCS answer fits one component, and a peer that rejects our invoke is a
 * failure with a cause, not something to echo.
 */
public final class Tcap {

    public static final int BEGIN = 0x62;
    public static final int END = 0x64;
    public static final int CONTINUE = 0x65;
    public static final int ABORT = 0x67;

    public static final int INVOKE = 0x61;
    public static final int RETURN_RESULT_LAST = 0x62;
    public static final int RETURN_ERROR = 0x63;
    public static final int REJECT = 0x64;

    public static final int ORIG_TRANSACTION = 0x48;
    public static final int DEST_TRANSACTION = 0x49;
    public static final int DIALOGUE_PORTION = 0x6b;
    public static final int COMPONENT_PORTION = 0x6c;
    public static final int AARQ = 0x60;
    public static final int AARE = 0x61;

    /** The structured-dialogue abstract syntax: 0.0.17.773.1.1.1. */
    public static final byte[] DIALOGUE_AS = Ber.oid(0, 0, 17, 773, 1, 1, 1);

    public static final int ACCEPTED = 0;

    private Tcap() {
    }

    public sealed interface Component permits Invoke, ReturnResult, ReturnError {

        /** The component portion element, e.g. {@code 61 ...} for an Invoke. */
        byte[] encoded();
    }

    /**
     * {@code Invoke ::= SEQUENCE { invokeID, linkedID [0] OPTIONAL, opCode,
     * parameter OPTIONAL }}. A local opCode is a plain INTEGER on the wire; the
     * argument is passed already encoded by the operation codec.
     */
    public record Invoke(int invokeId, Integer linkedId, int opCode, byte[] argument)
            implements Component {

        @Override
        public byte[] encoded() {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Ber.integer(invokeId));
            if (linkedId != null) {
                body.writeBytes(Ber.taggedInteger(Ber.context(0), linkedId));
            }
            body.writeBytes(Ber.integer(opCode));
            if (argument != null) {
                body.writeBytes(argument);
            }
            return Ber.tlv(INVOKE, body.toByteArray());
        }
    }

    /** {@code ReturnResult ::= SEQUENCE { invokeID, resultretres SEQUENCE { opCode, parameter } OPTIONAL }}. */
    public record ReturnResult(int invokeId, int opCode, byte[] result) implements Component {

        @Override
        public byte[] encoded() {
            byte[] operation = result == null
                    ? Ber.sequence(Ber.integer(opCode))
                    : Ber.sequence(Ber.integer(opCode), result);
            return Ber.tlv(RETURN_RESULT_LAST, concat(Ber.integer(invokeId), operation));
        }
    }

    /** {@code ReturnError ::= SEQUENCE { invokeID, errorCode, parameter OPTIONAL }}. */
    public record ReturnError(int invokeId, int errorCode, byte[] parameter) implements Component {

        @Override
        public byte[] encoded() {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Ber.integer(invokeId));
            body.writeBytes(Ber.integer(errorCode));
            if (parameter != null) {
                body.writeBytes(parameter);
            }
            return Ber.tlv(RETURN_ERROR, body.toByteArray());
        }
    }

    public static byte[] begin(int transactionId, byte[] applicationContext, Component component) {
        return message(BEGIN, transactionId, null, applicationContext, component);
    }

    public static byte[] end(int transactionId, byte[] applicationContext, Component component) {
        return message(END, transactionId, null, applicationContext, component);
    }

    public static byte[] invoke(int invokeId, int opCode, byte[] argument) {
        return new Invoke(invokeId, null, opCode, argument).encoded();
    }

    public static byte[] returnResult(int invokeId, int opCode, byte[] result) {
        return new ReturnResult(invokeId, opCode, result).encoded();
    }

    public static byte[] returnError(int invokeId, int errorCode, byte[] parameter) {
        return new ReturnError(invokeId, errorCode, parameter).encoded();
    }

    public static byte[] message(int type, int transactionId, Integer destinationTransactionId,
                                 byte[] applicationContext, Component component) {
        return message(type, transactionId, destinationTransactionId, applicationContext, component, false);
    }

    /**
     * @param answer true to send an AARE (accepted) instead of an AARQ; a
     *               client only ever sends AARQ, a peer answers with AARE
     */
    public static byte[] message(int type, int transactionId, Integer destinationTransactionId,
                                 byte[] applicationContext, Component component, boolean answer) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(Ber.tlv(ORIG_TRANSACTION, transactionId(transactionId)));
        if (destinationTransactionId != null) {
            body.writeBytes(Ber.tlv(DEST_TRANSACTION, transactionId(destinationTransactionId)));
        }
        if (applicationContext != null) {
            body.writeBytes(dialogue(applicationContext, answer));
        }
        if (component != null) {
            body.writeBytes(Ber.tlv(COMPONENT_PORTION, component.encoded()));
        }
        return Ber.tlv(type, body.toByteArray());
    }

    public static byte[] dialogue(byte[] applicationContext, boolean answer) {
        byte[] acName = Ber.tlv(Ber.contextConstructed(1), applicationContext);
        byte[] pdu = answer
                ? Ber.tlv(AARE, concat(acName,
                        Ber.tlv(Ber.contextConstructed(2), Ber.integer(ACCEPTED)),
                        Ber.tlv(Ber.contextConstructed(3),
                                Ber.tlv(Ber.contextConstructed(1), Ber.integer(0)))))
                : Ber.tlv(AARQ, acName);
        return Ber.tlv(DIALOGUE_PORTION,
                Ber.tlv(Ber.EXTERNAL,
                        concat(DIALOGUE_AS, Ber.tlv(Ber.contextConstructed(0), pdu))));
    }

    public static Message parse(byte[] frame) {
        Ber.Tlv root = Ber.parse(frame);
        int type = root.tag();
        if (type != BEGIN && type != END && type != CONTINUE && type != ABORT) {
            throw new BerException("not a TCAP transaction portion: tag " + Integer.toHexString(type));
        }
        byte[] transactionId = null;
        byte[] destinationTransactionId = null;
        List<Integer> applicationContext = null;
        Integer dialogueResult = null;
        List<Component> components = new ArrayList<>();
        int abortCause = -1;

        for (Ber.Tlv field : root.children()) {
            switch (field.tag()) {
                case ORIG_TRANSACTION -> transactionId = field.value();
                case DEST_TRANSACTION -> destinationTransactionId = field.value();
                case DIALOGUE_PORTION -> {
                    Dialogue decoded = parseDialogue(field);
                    applicationContext = decoded.applicationContext();
                    dialogueResult = decoded.result();
                }
                case COMPONENT_PORTION -> {
                    for (Ber.Tlv element : field.children()) {
                        components.add(parseComponent(element));
                    }
                }
                case 0x4a -> abortCause = field.intValue();
                default -> {
                    // An unknown optional field is skipped, not guessed at.
                }
            }
        }
        if (type == ABORT) {
            throw new MapException("TC_ABORT", "the peer aborted the dialogue"
                    + (abortCause >= 0 ? " (cause " + abortCause + ")" : ""));
        }
        return new Message(type, transactionId, destinationTransactionId,
                applicationContext, dialogueResult, List.copyOf(components));
    }

    private static Component parseComponent(Ber.Tlv element) {
        List<Ber.Tlv> fields = element.children();
        if (fields.isEmpty()) {
            throw new BerException("empty component");
        }
        if (element.tag() == INVOKE) {
            int invokeId = requireInteger(fields.get(0));
            int index = 1;
            Integer linkedId = null;
            if (fields.size() > index && fields.get(index).tag() == Ber.context(0)) {
                linkedId = fields.get(index++).intValue();
            }
            int operation = operation(fields.get(index++));
            byte[] argument = index < fields.size() ? fields.get(index).encoded() : null;
            return new Invoke(invokeId, linkedId, operation, argument);
        }
        if (element.tag() == RETURN_RESULT_LAST) {
            int invokeId = requireInteger(fields.get(0));
            if (fields.size() < 2) {
                throw new BerException("return result without a result value");
            }
            Ber.Tlv result = fields.get(1);
            List<Ber.Tlv> inner = result.children();
            if (inner.isEmpty()) {
                throw new BerException("return result without an operation code");
            }
            int operation = operation(inner.get(0));
            byte[] value = inner.size() > 1 ? inner.get(1).encoded() : null;
            return new ReturnResult(invokeId, operation, value);
        }
        if (element.tag() == RETURN_ERROR) {
            int invokeId = requireInteger(fields.get(0));
            if (fields.size() < 2) {
                throw new BerException("return error without an error code");
            }
            int error = operation(fields.get(1));
            byte[] parameter = fields.size() > 2 ? fields.get(2).encoded() : null;
            return new ReturnError(invokeId, error, parameter);
        }
        throw new MapException("TCAP_UNSUPPORTED", "component tag "
                + Integer.toHexString(element.tag()) + " is not handled");
    }

    private static int requireInteger(Ber.Tlv element) {
        if (element.tag() != Ber.INTEGER) {
            throw new BerException("expected an integer, found tag "
                    + Integer.toHexString(element.tag()));
        }
        return element.intValue();
    }

    private static int operation(Ber.Tlv element) {
        if (element.tag() == Ber.INTEGER) {
            return element.intValue();
        }
        if (element.tag() == Ber.OBJECT_IDENTIFIER) {
            throw new MapException("MAP_GLOBAL_OPCODE",
                    "global operation codes are not implemented");
        }
        throw new BerException("not an operation code: tag " + Integer.toHexString(element.tag()));
    }

    private record Dialogue(List<Integer> applicationContext, Integer result) {
    }

    private static Dialogue parseDialogue(Ber.Tlv portion) {
        Ber.Tlv external = null;
        for (Ber.Tlv field : portion.children()) {
            if (field.tag() == Ber.EXTERNAL) {
                external = field;
            }
        }
        if (external == null) {
            return new Dialogue(null, null);
        }
        List<Integer> applicationContext = null;
        Integer result = null;
        for (Ber.Tlv field : external.children()) {
            if (field.tag() != Ber.contextConstructed(0)) {
                continue;
            }
            Ber.Tlv pdu = Ber.parse(field.value());
            if (pdu.tag() != AARE && pdu.tag() != AARQ) {
                continue;
            }
            for (Ber.Tlv item : pdu.children()) {
                if (item.tag() == Ber.contextConstructed(1)) {
                    applicationContext = arcs(oid(item));
                } else if (item.tag() == Ber.contextConstructed(2)) {
                    result = integer(item);
                }
            }
        }
        return new Dialogue(applicationContext, result);
    }

    /** An explicitly tagged field whose value is a single universal element. */
    private static Ber.Tlv inner(Ber.Tlv element) {
        List<Ber.Tlv> children = element.children();
        if (children.size() != 1) {
            throw new BerException("expected one element inside tag "
                    + Integer.toHexString(element.tag()) + ", found " + children.size());
        }
        return children.get(0);
    }

    private static int[] oid(Ber.Tlv element) {
        Ber.Tlv value = element.tag() == Ber.contextConstructed(1) ? inner(element) : element;
        if (value.tag() != Ber.OBJECT_IDENTIFIER) {
            throw new BerException("application context name is not an object identifier");
        }
        return value.oidArcs();
    }

    private static int integer(Ber.Tlv element) {
        Ber.Tlv value = inner(element);
        if (value.tag() != Ber.INTEGER) {
            throw new BerException("dialogue field is not an integer");
        }
        return value.intValue();
    }

    private static List<Integer> arcs(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    public static byte[] transactionId(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("transaction id out of range: " + value);
        }
        if (value <= 0xff) {
            return new byte[] {(byte) value};
        }
        if (value <= 0xffff) {
            return new byte[] {(byte) (value >>> 8), (byte) value};
        }
        if (value <= 0xffffff) {
            return new byte[] {(byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        }
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    public static int transactionIdValue(byte[] value) {
        if (value == null || value.length == 0 || value.length > 4) {
            throw new BerException("a transaction id is one to four octets");
        }
        int result = 0;
        for (byte b : value) {
            result = (result << 8) | (b & 0xff);
        }
        return result;
    }

    public record Message(
            int type,
            byte[] transactionId,
            byte[] destinationTransactionId,
            List<Integer> applicationContext,
            Integer dialogueResult,
            List<Component> components) {

        public Component singleComponent() {
            if (components.size() != 1) {
                throw new MapException("TCAP_COMPONENT_COUNT",
                        "expected one component, found " + components.size());
            }
            return components.get(0);
        }

        public boolean dialogueAccepted() {
            return dialogueResult == null || dialogueResult == ACCEPTED;
        }

        public int transactionIdValue() {
            return Tcap.transactionIdValue(transactionId);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
