package com.hamiltel.gmlc.map;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The BER subset MAP needs: single-octet tags, definite lengths, and a parser
 * that refuses a truncated or ambiguous element instead of guessing. Every
 * encode pads nothing (MAP does not use Diameter's four-octet alignment); every
 * length uses the shortest form.
 */
public final class Ber {

    public static final int BOOLEAN = 0x01;
    public static final int INTEGER = 0x02;
    public static final int BIT_STRING = 0x03;
    public static final int OCTET_STRING = 0x04;
    public static final int NULL = 0x05;
    public static final int OBJECT_IDENTIFIER = 0x06;
    public static final int ENUMERATED = 0x0a;
    public static final int SEQUENCE = 0x30;
    public static final int SET = 0x31;
    public static final int EXTERNAL = 0x28;

    private Ber() {
    }

    public static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        writeLength(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    public static byte[] sequence(byte[]... elements) {
        return tlv(SEQUENCE, concat(elements));
    }

    public static byte[] set(byte[]... elements) {
        return tlv(SET, concat(elements));
    }

    public static byte[] octets(byte[] value) {
        return tlv(OCTET_STRING, value);
    }

    public static byte[] octets(String ascii) {
        return octets(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] nullValue(int tag) {
        return new byte[] {(byte) tag, 0};
    }

    public static byte[] integer(int value) {
        return tlv(INTEGER, minimal(value));
    }

    public static byte[] enumerated(int value) {
        return tlv(ENUMERATED, minimal(value));
    }

    public static byte[] taggedInteger(int tag, int value) {
        return tlv(tag, minimal(value));
    }

    /** Context-class primitive tag, e.g. {@code 0x80} for {@code [0]}. */
    public static int context(int number) {
        return 0x80 | (number & 0x1f);
    }

    /** Context-class constructed tag, e.g. {@code 0xa0} for an explicit {@code [0]}. */
    public static int contextConstructed(int number) {
        return 0xa0 | (number & 0x1f);
    }

    public static byte[] oid(int... arcs) {
        if (arcs.length < 2 || arcs[0] < 0 || arcs[0] > 2 || arcs[1] < 0
                || (arcs[0] < 2 && arcs[1] >= 40)) {
            throw new IllegalArgumentException("not a valid object identifier prefix");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeArc(out, arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            if (arcs[i] < 0) {
                throw new IllegalArgumentException("object identifier arc cannot be negative");
            }
            writeArc(out, arcs[i]);
        }
        return tlv(OBJECT_IDENTIFIER, out.toByteArray());
    }

    private static void writeArc(ByteArrayOutputStream out, int value) {
        byte[] buffer = new byte[5];
        int index = buffer.length;
        buffer[--index] = (byte) (value & 0x7f);
        value >>>= 7;
        while (value > 0) {
            buffer[--index] = (byte) ((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(buffer, index, buffer.length - index);
    }

    public static Tlv parse(byte[] data) {
        List<Tlv> all = parseAll(data);
        if (all.size() != 1) {
            throw new BerException("expected exactly one element, found " + all.size());
        }
        return all.get(0);
    }

    public static List<Tlv> parseAll(byte[] data) {
        List<Tlv> elements = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int tag = data[offset] & 0xff;
            if ((tag & 0x1f) == 0x1f) {
                throw new BerException("high-tag-number form is not supported");
            }
            if (offset + 1 >= data.length) {
                throw new BerException("truncated length at offset " + offset);
            }
            int first = data[offset + 1] & 0xff;
            int headerLength;
            int length;
            if (first < 0x80) {
                length = first;
                headerLength = 2;
            } else if (first == 0x80) {
                throw new BerException("indefinite length is not allowed");
            } else {
                int count = first & 0x7f;
                if (count > 3) {
                    throw new BerException("length uses more than three octets");
                }
                if (offset + 2 + count > data.length) {
                    throw new BerException("truncated long-form length at offset " + offset);
                }
                length = 0;
                for (int i = 0; i < count; i++) {
                    length = (length << 8) | (data[offset + 2 + i] & 0xff);
                }
                if (length < 0x80) {
                    throw new BerException("length uses the long form when the short form would do");
                }
                headerLength = 2 + count;
            }
            int start = offset + headerLength;
            int end = start + length;
            if (end > data.length) {
                throw new BerException("element at offset " + offset + " runs past the input");
            }
            elements.add(new Tlv(tag, Arrays.copyOfRange(data, start, end)));
            offset = end;
        }
        return elements;
    }

    static byte[] minimal(int value) {
        byte[] four = {(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
        int start = 0;
        while (start < 3) {
            byte current = four[start];
            byte next = four[start + 1];
            if ((current == 0 && (next & 0x80) == 0)
                    || (current == (byte) 0xff && (next & 0x80) != 0)) {
                start++;
            } else {
                break;
            }
        }
        return Arrays.copyOfRange(four, start, 4);
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        if (length <= 0xff) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xffff) {
            out.write(0x82);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    public record Tlv(int tag, byte[] value) {

        public boolean constructed() {
            return (tag & 0x20) != 0;
        }

        public List<Tlv> children() {
            if (!constructed()) {
                throw new BerException("tag " + Integer.toHexString(tag) + " is primitive");
            }
            return parseAll(value);
        }

        public int intValue() {
            if (value.length == 0 || value.length > 4) {
                throw new BerException("integer has " + value.length + " octets");
            }
            int result = (value[0] & 0x80) != 0 ? -1 : 0;
            for (byte b : value) {
                result = (result << 8) | (b & 0xff);
            }
            return result;
        }

        public String ascii() {
            return new String(value, StandardCharsets.US_ASCII);
        }

        public int[] oidArcs() {
            if (value.length == 0) {
                throw new BerException("empty object identifier");
            }
            if ((value[value.length - 1] & 0x80) != 0) {
                throw new BerException("truncated object identifier");
            }
            List<Integer> arcs = new ArrayList<>();
            int current = 0;
            boolean prefix = true;
            for (byte octetValue : value) {
                int octet = octetValue & 0xff;
                current = (current << 7) | (octet & 0x7f);
                if ((octet & 0x80) != 0) {
                    continue;
                }
                if (prefix) {
                    int head = Math.min(current / 40, 2);
                    arcs.add(head);
                    arcs.add(current - head * 40);
                    prefix = false;
                } else {
                    arcs.add(current);
                }
                current = 0;
            }
            return arcs.stream().mapToInt(Integer::intValue).toArray();
        }

        public byte[] encoded() {
            return tlv(tag, value);
        }
    }
}
