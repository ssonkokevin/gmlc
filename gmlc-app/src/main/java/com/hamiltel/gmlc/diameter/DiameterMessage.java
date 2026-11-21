package com.hamiltel.gmlc.diameter;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Diameter message (RFC 6733 section 3). The wire form is a 20-octet header
 * and a run of AVPs; parsing refuses a wrong version or a length that does not
 * fit the frame.
 */
public record DiameterMessage(
        boolean request,
        boolean proxiable,
        boolean error,
        boolean retransmitted,
        int commandCode,
        long applicationId,
        int hopByHopId,
        int endToEndId,
        List<Avp> avps) {

    public static final int HEADER_BYTES = 20;
    public static final long VENDOR = Avp.THREE_GPP;

    private static final int FLAG_REQUEST = 0x80;
    private static final int FLAG_PROXIABLE = 0x40;
    private static final int FLAG_ERROR = 0x20;
    private static final int FLAG_RETRANSMITTED = 0x10;
    private static final int MAX_FRAME = 1 << 20;

    public DiameterMessage {
        if (commandCode <= 0 || commandCode > 0xFF_FFFF) {
            throw new IllegalArgumentException("command code out of range: " + commandCode);
        }
        if (applicationId < 0 || applicationId > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("application id out of range: " + applicationId);
        }
        avps = avps == null ? List.of() : List.copyOf(avps);
    }

    /**
     * A request with no hop or end-to-end identifiers yet; the transport fills
     * them in so a caller never has to invent correlation ids.
     */
    public static DiameterMessage request(int commandCode, long applicationId, List<Avp> avps) {
        return new DiameterMessage(true, true, false, false, commandCode, applicationId, 0, 0, avps);
    }

    public DiameterMessage withIds(int hop, int endToEnd) {
        return new DiameterMessage(request, proxiable, error, retransmitted, commandCode,
                applicationId, hop, endToEnd, avps);
    }

    public DiameterMessage answer(int hop, int endToEnd, List<Avp> answerAvps) {
        return new DiameterMessage(false, proxiable, error, false, commandCode, applicationId,
                hop, endToEnd, answerAvps);
    }

    public byte[] encode() {
        byte[] avpBytes = Avp.encodeAll(avps);
        int length = HEADER_BYTES + avpBytes.length;
        byte[] out = new byte[length];
        out[0] = 1;
        out[1] = (byte) (length >>> 16);
        out[2] = (byte) (length >>> 8);
        out[3] = (byte) length;
        int flags = (request ? FLAG_REQUEST : 0) | (proxiable ? FLAG_PROXIABLE : 0)
                | (error ? FLAG_ERROR : 0) | (retransmitted ? FLAG_RETRANSMITTED : 0);
        out[4] = (byte) flags;
        out[5] = (byte) (commandCode >>> 16);
        out[6] = (byte) (commandCode >>> 8);
        out[7] = (byte) commandCode;
        writeInt(out, 8, (int) applicationId);
        writeInt(out, 12, hopByHopId);
        writeInt(out, 16, endToEndId);
        System.arraycopy(avpBytes, 0, out, HEADER_BYTES, avpBytes.length);
        return out;
    }

    public static DiameterMessage parse(byte[] frame) {
        if (frame.length < HEADER_BYTES) {
            throw new IllegalArgumentException("frame shorter than a Diameter header: " + frame.length);
        }
        if (frame.length > MAX_FRAME) {
            throw new IllegalArgumentException("frame larger than " + MAX_FRAME + " bytes");
        }
        if (frame[0] != 1) {
            throw new IllegalArgumentException("unsupported Diameter version " + (frame[0] & 0xFF));
        }
        int length = ((frame[1] & 0xFF) << 16) | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (length < HEADER_BYTES || length > frame.length) {
            throw new IllegalArgumentException("declared length " + length + " in a " + frame.length + "-octet frame");
        }
        int flags = frame[4] & 0xFF;
        int commandCode = ((frame[5] & 0xFF) << 16) | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        long applicationId = Avp.readInt(frame, 8) & 0xFFFF_FFFFL;
        List<Avp> avps = new ArrayList<>();
        int at = HEADER_BYTES;
        while (at < length) {
            if (length - at < 8) {
                throw new IllegalArgumentException("trailing octets after the last AVP");
            }
            int avpLength = Avp.readLength(frame, at + 5);
            int next = at + ((avpLength + 3) & ~3);
            if (avpLength < 8 || next > length) {
                throw new IllegalArgumentException("AVP at offset " + at + " runs past the message");
            }
            byte[] slice = java.util.Arrays.copyOfRange(frame, at, next);
            avps.addAll(Avp.parseAll(slice));
            at = next;
        }
        return new DiameterMessage(
                (flags & FLAG_REQUEST) != 0,
                (flags & FLAG_PROXIABLE) != 0,
                (flags & FLAG_ERROR) != 0,
                (flags & FLAG_RETRANSMITTED) != 0,
                commandCode, applicationId,
                Avp.readInt(frame, 12), Avp.readInt(frame, 16),
                avps);
    }

    public Optional<Avp> first(int avpCode) {
        return avps.stream().filter(avp -> avp.code() == avpCode).findFirst();
    }

    public List<Avp> all(int avpCode) {
        return avps.stream().filter(avp -> avp.code() == avpCode).toList();
    }

    public String text(int avpCode) {
        return first(avpCode).map(Avp::asText).orElse(null);
    }

    public Integer integer(int avpCode) {
        return first(avpCode).map(Avp::asInt).orElse(null);
    }

    private static void writeInt(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    /** The four-octet length prefix of a Diameter-over-TCP stream. */
    public static byte[] frame(byte[] payload) {
        if (payload.length > 0xFF_FFFF) {
            throw new IllegalArgumentException("message too large for a stream frame");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 4);
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }
}
