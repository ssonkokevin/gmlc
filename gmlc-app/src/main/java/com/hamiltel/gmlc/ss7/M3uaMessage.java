package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The M3UA subset an STP's signalling gateway needs (RFC 4666): ASPSM, ASPTM
 * and DATA. The common header is version 1, reserved, class, type and a
 * four-octet length that includes the header. Every parameter is a tag, a
 * length that includes the four-octet tag/length pair but not the padding,
 * the value, then zero padding to a four-octet boundary.
 *
 * <p>DATA carries SCCP through the Protocol Data parameter: 32-bit originating
 * and destination point codes, service indicator, network indicator, message
 * priority, signalling link selection, then the SCCP message itself. The MTP3
 * routing label does not exist on the wire here; those fields are the label.
 */
public final class M3uaMessage {

    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 8;

    public static final int CLASS_ERROR = 0;
    public static final int CLASS_TRANSFER = 1;
    public static final int CLASS_ASPSM = 3;
    public static final int CLASS_ASPTM = 4;

    public static final int TYPE_ERROR = 0;
    public static final int TYPE_DATA = 1;
    public static final int TYPE_ASPUP = 1;
    public static final int TYPE_ASPDN = 2;
    public static final int TYPE_BEAT = 3;
    public static final int TYPE_ASPUP_ACK = 4;
    public static final int TYPE_ASPDN_ACK = 5;
    public static final int TYPE_BEAT_ACK = 6;
    public static final int TYPE_ASPAC = 1;
    public static final int TYPE_ASPAC_ACK = 3;

    public static final int TAG_INFO_STRING = 0x0004;
    public static final int TAG_ROUTING_CONTEXT = 0x0006;
    public static final int TAG_TRAFFIC_MODE_TYPE = 0x000b;
    public static final int TAG_ERROR_CODE = 0x000c;
    public static final int TAG_ASP_IDENTIFIER = 0x0011;
    public static final int TAG_PROTOCOL_DATA = 0x0210;

    public static final int SI_SCCP = 3;
    public static final int TRAFFIC_MODE_OVERRIDE = 1;

    private static final int MAX_MESSAGE = 1 << 20;

    private final int messageClass;
    private final int type;
    private final List<Param> params;

    private M3uaMessage(int messageClass, int type, List<Param> params) {
        this.messageClass = messageClass;
        this.type = type;
        this.params = List.copyOf(params);
    }

    public int messageClass() {
        return messageClass;
    }

    public int type() {
        return type;
    }

    public List<Param> params() {
        return params;
    }

    public boolean is(int expectedClass, int expectedType) {
        return messageClass == expectedClass && type == expectedType;
    }

    public Optional<Param> first(int tag) {
        return params.stream().filter(param -> param.tag() == tag).findFirst();
    }

    public Optional<String> infoString() {
        return first(TAG_INFO_STRING).map(param -> new String(param.value(), StandardCharsets.US_ASCII));
    }

    public Integer errorCode() {
        return first(TAG_ERROR_CODE).map(Param::intValue).orElse(null);
    }

    public List<Integer> routingContexts() {
        return first(TAG_ROUTING_CONTEXT)
                .map(Param::ints)
                .orElse(List.of());
    }

    public Optional<ProtocolData> protocolData() {
        return first(TAG_PROTOCOL_DATA).map(param -> ProtocolData.parse(param.value()));
    }

    public byte[] encode() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Param param : params) {
            body.writeBytes(param.encode());
        }
        int total = HEADER_BYTES + body.size();
        if (total > MAX_MESSAGE) {
            throw new MapException("M3UA_TOO_LONG", "the M3UA message is " + total + " octets");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        out.write(VERSION);
        out.write(0);
        out.write(messageClass);
        out.write(type);
        out.write((total >>> 24) & 0xFF);
        out.write((total >>> 16) & 0xFF);
        out.write((total >>> 8) & 0xFF);
        out.write(total & 0xFF);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    public static M3uaMessage parse(byte[] frame) {
        if (frame.length < HEADER_BYTES) {
            throw new MapException("M3UA_FRAME",
                    "a frame of " + frame.length + " octets is shorter than the common header");
        }
        int version = frame[0] & 0xFF;
        if (version != VERSION) {
            throw new MapException("M3UA_VERSION", "M3UA version " + version + " is not 1");
        }
        long declared = ((long) (frame[4] & 0xFF) << 24) | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        if (declared != frame.length) {
            throw new MapException("M3UA_FRAME",
                    "the header declares " + declared + " octets but " + frame.length + " were read");
        }
        int offset = HEADER_BYTES;
        List<Param> params = new ArrayList<>();
        while (offset < frame.length) {
            if (offset + 4 > frame.length) {
                throw new MapException("M3UA_PARAM", "a parameter header is cut short at offset " + offset);
            }
            int tag = ((frame[offset] & 0xFF) << 8) | (frame[offset + 1] & 0xFF);
            int length = ((frame[offset + 2] & 0xFF) << 8) | (frame[offset + 3] & 0xFF);
            if (length < 4) {
                throw new MapException("M3UA_PARAM",
                        "parameter 0x" + Integer.toHexString(tag) + " declares length " + length);
            }
            int end = offset + length;
            if (end > frame.length) {
                throw new MapException("M3UA_PARAM",
                        "parameter 0x" + Integer.toHexString(tag) + " runs past the message");
            }
            params.add(new Param(tag, Arrays.copyOfRange(frame, offset + 4, end)));
            offset = end + ((4 - (length % 4)) % 4);
        }
        if (offset != frame.length) {
            throw new MapException("M3UA_PARAM", "padding runs past the end of the message");
        }
        return new M3uaMessage(frame[2] & 0xFF, frame[3] & 0xFF, params);
    }

    public static M3uaMessage of(int messageClass, int type, Param... params) {
        return new M3uaMessage(messageClass, type, List.of(params));
    }

    public static M3uaMessage aspUp(int aspIdentifier, String info) {
        List<Param> params = new ArrayList<>();
        params.add(Param.u32(TAG_ASP_IDENTIFIER, aspIdentifier));
        if (info != null && !info.isBlank()) {
            params.add(Param.text(TAG_INFO_STRING, info));
        }
        return new M3uaMessage(CLASS_ASPSM, TYPE_ASPUP, params);
    }

    public static M3uaMessage aspUpAck() {
        return of(CLASS_ASPSM, TYPE_ASPUP_ACK);
    }

    public static M3uaMessage aspDown(int aspIdentifier) {
        return of(CLASS_ASPSM, TYPE_ASPDN, Param.u32(TAG_ASP_IDENTIFIER, aspIdentifier));
    }

    public static M3uaMessage aspDownAck() {
        return of(CLASS_ASPSM, TYPE_ASPDN_ACK);
    }

    public static M3uaMessage beat() {
        return of(CLASS_ASPSM, TYPE_BEAT);
    }

    public static M3uaMessage beatAck() {
        return of(CLASS_ASPSM, TYPE_BEAT_ACK);
    }

    public static M3uaMessage aspActive(Integer routingContext) {
        List<Param> params = new ArrayList<>();
        params.add(Param.u32(TAG_TRAFFIC_MODE_TYPE, TRAFFIC_MODE_OVERRIDE));
        if (routingContext != null) {
            params.add(Param.u32(TAG_ROUTING_CONTEXT, routingContext));
        }
        return new M3uaMessage(CLASS_ASPTM, TYPE_ASPAC, params);
    }

    public static M3uaMessage aspActiveAck(Integer routingContext) {
        List<Param> params = new ArrayList<>();
        params.add(Param.u32(TAG_TRAFFIC_MODE_TYPE, TRAFFIC_MODE_OVERRIDE));
        if (routingContext != null) {
            params.add(Param.u32(TAG_ROUTING_CONTEXT, routingContext));
        }
        return new M3uaMessage(CLASS_ASPTM, TYPE_ASPAC_ACK, params);
    }

    public static M3uaMessage data(int originatingPointCode, int destinationPointCode,
                                   int serviceIndicator, int networkIndicator, int signallingLinkSelection,
                                   Integer routingContext, byte[] sccp) {
        ProtocolData protocolData = new ProtocolData(originatingPointCode, destinationPointCode,
                serviceIndicator, networkIndicator, 0, signallingLinkSelection, sccp);
        List<Param> params = new ArrayList<>();
        if (routingContext != null) {
            params.add(Param.u32(TAG_ROUTING_CONTEXT, routingContext));
        }
        params.add(new Param(TAG_PROTOCOL_DATA, protocolData.encode()));
        return new M3uaMessage(CLASS_TRANSFER, TYPE_DATA, params);
    }

    public record Param(int tag, byte[] value) {

        public Param {
            if (tag < 0 || tag > 0xFFFF) {
                throw new IllegalArgumentException("a parameter tag is 16 bits");
            }
            if (value == null) {
                throw new IllegalArgumentException("a parameter needs a value");
            }
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        public static Param u32(int tag, int value) {
            return new Param(tag, new byte[] {
                    (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
        }

        public static Param text(int tag, String text) {
            return new Param(tag, text.getBytes(StandardCharsets.US_ASCII));
        }

        public int intValue() {
            if (value.length != 4) {
                throw new MapException("M3UA_PARAM",
                        "parameter 0x" + Integer.toHexString(tag) + " is not a 32-bit value");
            }
            return ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                    | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
        }

        public List<Integer> ints() {
            if (value.length == 0 || value.length % 4 != 0) {
                throw new MapException("M3UA_PARAM",
                        "parameter 0x" + Integer.toHexString(tag) + " is not a run of 32-bit values");
            }
            List<Integer> values = new ArrayList<>(value.length / 4);
            for (int i = 0; i < value.length; i += 4) {
                values.add(((value[i] & 0xFF) << 24) | ((value[i + 1] & 0xFF) << 16)
                        | ((value[i + 2] & 0xFF) << 8) | (value[i + 3] & 0xFF));
            }
            return values;
        }

        byte[] encode() {
            int length = 4 + value.length;
            int padded = (length + 3) & ~3;
            byte[] out = new byte[padded];
            out[0] = (byte) (tag >>> 8);
            out[1] = (byte) tag;
            out[2] = (byte) (length >>> 8);
            out[3] = (byte) length;
            System.arraycopy(value, 0, out, 4, value.length);
            return out;
        }
    }

    /** The RFC 4666 section 3.3.1 protocol-data parameter. */
    public record ProtocolData(int originatingPointCode, int destinationPointCode,
                               int serviceIndicator, int networkIndicator, int messagePriority,
                               int signallingLinkSelection, byte[] userData) {

        private static final int FIXED = 12;

        public ProtocolData {
            if (originatingPointCode < 0 || destinationPointCode < 0) {
                throw new IllegalArgumentException("point codes are not negative");
            }
            if (serviceIndicator < 0 || serviceIndicator > 15) {
                throw new IllegalArgumentException("a service indicator is 4 bits");
            }
            if (networkIndicator < 0 || networkIndicator > 3) {
                throw new IllegalArgumentException("a network indicator is 2 bits");
            }
            if (signallingLinkSelection < 0 || signallingLinkSelection > 15) {
                throw new IllegalArgumentException("a signalling link selection is 4 bits");
            }
            if (userData == null || userData.length == 0) {
                throw new IllegalArgumentException("protocol data needs user data");
            }
            userData = userData.clone();
        }

        @Override
        public byte[] userData() {
            return userData.clone();
        }

        byte[] encode() {
            byte[] out = new byte[FIXED + userData.length];
            writeInt(out, 0, originatingPointCode);
            writeInt(out, 4, destinationPointCode);
            out[8] = (byte) serviceIndicator;
            out[9] = (byte) networkIndicator;
            out[10] = (byte) messagePriority;
            out[11] = (byte) signallingLinkSelection;
            System.arraycopy(userData, 0, out, FIXED, userData.length);
            return out;
        }

        static ProtocolData parse(byte[] value) {
            if (value.length <= FIXED) {
                throw new MapException("M3UA_PROTOCOL_DATA",
                        "protocol data is " + value.length + " octets, too short for its header and user data");
            }
            return new ProtocolData(readInt(value, 0), readInt(value, 4),
                    value[8] & 0x0F, value[9] & 0x03, value[10] & 0xFF, value[11] & 0x0F,
                    Arrays.copyOfRange(value, FIXED, value.length));
        }

        private static void writeInt(byte[] target, int offset, int value) {
            target[offset] = (byte) (value >>> 24);
            target[offset + 1] = (byte) (value >>> 16);
            target[offset + 2] = (byte) (value >>> 8);
            target[offset + 3] = (byte) value;
        }

        private static int readInt(byte[] source, int offset) {
            return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16)
                    | ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
        }
    }
}
