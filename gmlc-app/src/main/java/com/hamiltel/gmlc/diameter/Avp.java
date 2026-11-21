package com.hamiltel.gmlc.diameter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One Diameter AVP (RFC 6733 section 4.1). Values are kept as raw octets; the
 * typed factories enforce the length rules and the parser refuses a truncated
 * or misaligned AVP rather than guessing at it.
 */
public record Avp(int code, boolean vendorSpecific, long vendorId, byte[] value) {

    /** The 3GPP vendor identifier every vendor-specific AVP on these interfaces carries. */
    public static final long THREE_GPP = 10415L;

    private static final int FLAG_VENDOR = 0x80;
    private static final int FLAG_MANDATORY = 0x40;
    private static final int BASE_HEADER = 8;
    private static final int VENDOR_HEADER = 12;

    public Avp {
        if (code <= 0 || code > 0xFF_FFFF) {
            throw new IllegalArgumentException("AVP code out of range: " + code);
        }
        if (vendorSpecific && (vendorId <= 0 || vendorId > 0xFFFF_FFFFL)) {
            throw new IllegalArgumentException("vendor id out of range: " + vendorId);
        }
        value = value == null ? new byte[0] : value.clone();
    }

    public static Avp base(int code, byte[] data) {
        return new Avp(code, false, 0, data);
    }

    public static Avp baseUtf8(int code, String text) {
        return base(code, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Avp baseU32(int code, long value) {
        return base(code, u32(value));
    }

    public static Avp vendor(int code, byte[] data) {
        return new Avp(code, true, THREE_GPP, data);
    }

    public static Avp vendorUtf8(int code, String text) {
        return vendor(code, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Avp vendorU32(int code, long value) {
        return vendor(code, u32(value));
    }

    public static Avp vendorGrouped(int code, Avp... children) {
        return vendor(code, encodeAll(List.of(children)));
    }

    public static Avp baseGrouped(int code, Avp... children) {
        return base(code, encodeAll(List.of(children)));
    }

    public static Avp address(int code, byte[] address) {
        if (address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException("an address AVP is 4 or 16 octets");
        }
        byte[] value = new byte[address.length + 2];
        value[1] = (byte) (address.length == 4 ? 1 : 2);
        System.arraycopy(address, 0, value, 2, address.length);
        return base(code, value);
    }

    private static byte[] u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Unsigned32 out of range: " + value);
        }
        return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    /** Header plus value, before padding. */
    public int unpaddedLength() {
        return (vendorSpecific ? VENDOR_HEADER : BASE_HEADER) + value.length;
    }

    public byte[] encode() {
        int length = unpaddedLength();
        byte[] out = new byte[(length + 3) & ~3];
        out[0] = (byte) (code >>> 24);
        out[1] = (byte) (code >>> 16);
        out[2] = (byte) (code >>> 8);
        out[3] = (byte) code;
        out[4] = (byte) (vendorSpecific ? FLAG_VENDOR | FLAG_MANDATORY : FLAG_MANDATORY);
        out[5] = (byte) (length >>> 16);
        out[6] = (byte) (length >>> 8);
        out[7] = (byte) length;
        int at = BASE_HEADER;
        if (vendorSpecific) {
            out[8] = (byte) (vendorId >>> 24);
            out[9] = (byte) (vendorId >>> 16);
            out[10] = (byte) (vendorId >>> 8);
            out[11] = (byte) vendorId;
            at = VENDOR_HEADER;
        }
        System.arraycopy(value, 0, out, at, value.length);
        return out;
    }

    /** Encodes a run of AVPs, including their padding, as they appear in a message. */
    public static byte[] encodeAll(List<Avp> avps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Avp avp : avps) {
            out.writeBytes(avp.encode());
        }
        return out.toByteArray();
    }

    public static List<Avp> parseAll(byte[] data) {
        List<Avp> avps = new ArrayList<>();
        int at = 0;
        while (at < data.length) {
            if (data.length - at < BASE_HEADER) {
                throw new IllegalArgumentException("truncated AVP header at offset " + at);
            }
            int code = readInt(data, at);
            int flags = data[at + 4] & 0xFF;
            int length = readLength(data, at + 5);
            boolean vendor = (flags & FLAG_VENDOR) != 0;
            int header = vendor ? VENDOR_HEADER : BASE_HEADER;
            if (length < header) {
                throw new IllegalArgumentException("AVP " + code + " declared length " + length);
            }
            if (at + length > data.length) {
                throw new IllegalArgumentException("AVP " + code + " runs past the message");
            }
            long vendorId = vendor ? readInt(data, at + 8) & 0xFFFF_FFFFL : 0;
            byte[] value = Arrays.copyOfRange(data, at + header, at + length);
            avps.add(new Avp(code, vendor, vendorId, value));
            at += (length + 3) & ~3;
        }
        return avps;
    }

    static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    static int readLength(byte[] data, int at) {
        return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
    }

    public String asText() {
        return new String(value, StandardCharsets.UTF_8);
    }

    public long asUnsigned32() {
        if (value.length != 4) {
            throw new IllegalStateException("AVP " + code + " is not an Unsigned32");
        }
        return readInt(value, 0) & 0xFFFF_FFFFL;
    }

    public int asInt() {
        long value = asUnsigned32();
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException("AVP " + code + " does not fit an int: " + value);
        }
        return (int) value;
    }

    /** The child AVPs of a Grouped AVP. */
    public List<Avp> children() {
        return parseAll(value);
    }

    public byte[] copyOfValue() {
        return value.clone();
    }
}
