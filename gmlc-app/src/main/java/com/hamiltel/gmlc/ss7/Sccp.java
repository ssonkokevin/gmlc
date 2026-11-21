package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The SCCP subset MAP needs: connectionless unitdata (UDT) with point-code,
 * subsystem-number and E.164 global-title addresses (ITU-T Q.713 sections 3.4
 * and 4.10). The routing label is not part of SCCP; M3UA carries the point
 * codes in its protocol-data header.
 *
 * <p>An address indicator is: bit 1 point code present, bit 2 subsystem number
 * present, bits 3-6 global title indicator (4 = translation type, numbering
 * plan, encoding scheme and nature of address), bit 7 route on the point code
 * and subsystem rather than on the global title. A global title of type 4 is
 * encoded as translation type 0, E.164 numbering plan, BCD with an odd/even
 * flag, international nature of address, then the digits low nibble first,
 * padded with 0000 when the digit count is odd (Q.713 figure 8). This is the
 * one place where the filler is 0000, not the 1111 used by MAP's own TBCD.
 */
public final class Sccp {

    public static final int UDT = 0x09;

    public static final int SSN_HLR = 6;
    public static final int SSN_VLR = 7;
    public static final int SSN_MSC = 8;
    public static final int SSN_SGSN = 149;
    public static final int SSN_GMLC = 145;

    private static final int GT_INDICATOR_E164 = 4;
    private static final int NATURE_OF_ADDRESS_INTERNATIONAL = 4;
    private static final int MAX_MESSAGE = 255;
    private static final int MAX_GLOBAL_TITLE_DIGITS = 15;

    private Sccp() {
    }

    /**
     * A called or calling party address. An address with no element at all is
     * the Q.713 section 3.5 form for "not available": one indicator octet,
     * bits 1-7 zero.
     */
    public record Address(int pointCode, Integer subsystem, String globalTitle) {

        public Address {
            if (pointCode < 0 || pointCode > 0x3FFF) {
                throw new IllegalArgumentException("a point code fits 14 bits");
            }
            if (subsystem != null && (subsystem < 1 || subsystem > 0xFE)) {
                throw new IllegalArgumentException("a subsystem number is 1-254");
            }
            globalTitle = blank(globalTitle) ? null : globalTitle.trim();
            if (globalTitle != null) {
                if (globalTitle.length() > MAX_GLOBAL_TITLE_DIGITS) {
                    throw new IllegalArgumentException("an E.164 global title is at most 15 digits");
                }
                if (!globalTitle.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    throw new IllegalArgumentException("an E.164 global title holds decimal digits only");
                }
            }
        }

        /** Route on the destination point code and this subsystem. */
        public static Address subsystem(int ssn) {
            return new Address(0, ssn, null);
        }

        /** Route on the destination point code and this subsystem, naming the point code too. */
        public static Address subsystem(int pointCode, int ssn) {
            return new Address(pointCode, ssn, null);
        }

        /** Route on the global title, carrying the subsystem number of the addressee. */
        public static Address globalTitle(int ssn, String digits) {
            return new Address(0, ssn, digits);
        }

        public boolean routedByGlobalTitle() {
            return globalTitle != null;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int indicator = pointCode > 0 ? 0x01 : 0;
            if (subsystem != null) {
                indicator |= 0x02;
            }
            if (globalTitle != null) {
                indicator |= GT_INDICATOR_E164 << 2;
            } else if (pointCode > 0 || subsystem != null) {
                // Route on the point code and subsystem, not on a global title.
                indicator |= 0x40;
            }
            out.write(indicator);
            if (pointCode > 0) {
                out.write(pointCode & 0xFF);
                out.write((pointCode >>> 8) & 0x3F);
            }
            if (subsystem != null) {
                out.write(subsystem);
            }
            if (globalTitle != null) {
                out.writeBytes(Sccp.globalTitle(globalTitle));
            }
            return out.toByteArray();
        }

        static Address parse(byte[] data) {
            if (data.length == 0) {
                throw new MapException("SCCP_ADDRESS", "an SCCP address has no indicator octet");
            }
            int indicator = data[0] & 0xFF;
            int index = 1;
            int pointCode = 0;
            if ((indicator & 0x01) != 0) {
                if (index + 2 > data.length) {
                    throw new MapException("SCCP_ADDRESS", "the point code is cut short");
                }
                if ((data[index + 1] & 0xC0) != 0) {
                    throw new MapException("SCCP_ADDRESS", "the second point-code octet has bits 7-8 set");
                }
                pointCode = (data[index] & 0xFF) | ((data[index + 1] & 0x3F) << 8);
                index += 2;
            }
            Integer subsystem = null;
            if ((indicator & 0x02) != 0) {
                if (index >= data.length) {
                    throw new MapException("SCCP_ADDRESS", "the subsystem number is cut short");
                }
                subsystem = data[index++] & 0xFF;
            }
            int globalTitleIndicator = (indicator >>> 2) & 0x0F;
            String globalTitle = null;
            if (globalTitleIndicator != 0) {
                if (globalTitleIndicator != GT_INDICATOR_E164) {
                    throw new MapException("SCCP_GT_UNSUPPORTED",
                            "global title indicator " + globalTitleIndicator
                                    + " is not the E.164 type 4 this gateway sends");
                }
                if (index + 3 > data.length) {
                    throw new MapException("SCCP_GT", "the global title is shorter than its three-octet header");
                }
                int numberingPlan = (data[index + 1] & 0xF0) >>> 4;
                int encodingScheme = data[index + 1] & 0x0F;
                int natureOfAddress = data[index + 2] & 0xFF;
                if (numberingPlan != 1) {
                    throw new MapException("SCCP_GT_UNSUPPORTED",
                            "numbering plan " + numberingPlan + " is not ISDN/E.164");
                }
                if (encodingScheme > 1) {
                    throw new MapException("SCCP_GT_UNSUPPORTED",
                            "encoding scheme " + encodingScheme + " is not BCD");
                }
                if ((natureOfAddress & 0x80) != 0
                        || natureOfAddress != NATURE_OF_ADDRESS_INTERNATIONAL) {
                    throw new MapException("SCCP_GT_UNSUPPORTED",
                            "nature of address " + natureOfAddress + " is not international");
                }
                index += 3;
                globalTitle = Sccp.digits(data, index, encodingScheme == 1);
                index = data.length;
            }
            if (index != data.length) {
                throw new MapException("SCCP_ADDRESS",
                        (data.length - index) + " unused octet(s) after the last address element");
            }
            return new Address(pointCode, subsystem, globalTitle);
        }
    }

    /** A connectionless unitdata message: called party, calling party and the MAP payload. */
    public record UnitData(Address calledParty, Address callingParty, byte[] data) {

        public UnitData {
            if (calledParty == null || callingParty == null) {
                throw new IllegalArgumentException("a unitdata message needs both party addresses");
            }
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("a unitdata message needs user data");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        public byte[] encode() {
            byte[] called = calledParty.encode();
            byte[] calling = callingParty.encode();
            int total = 5 + called.length + calling.length + data.length;
            if (total > MAX_MESSAGE) {
                throw new MapException("SCCP_TOO_LONG", "the unitdata message is " + total
                        + " octets; Q.713 class 0 allows " + MAX_MESSAGE);
            }
            byte[] out = new byte[total];
            out[0] = (byte) UDT;
            // Protocol class 0, no special handling options.
            out[1] = 0;
            // A pointer counts itself: called starts at 2+3, calling at 3+2+called, data at 4+1+both.
            out[2] = 3;
            out[3] = (byte) (2 + called.length);
            out[4] = (byte) (1 + called.length + calling.length);
            System.arraycopy(called, 0, out, 5, called.length);
            System.arraycopy(calling, 0, out, 5 + called.length, calling.length);
            System.arraycopy(data, 0, out, 5 + called.length + calling.length, data.length);
            return out;
        }

        public static UnitData parse(byte[] message) {
            if (message.length < 5) {
                throw new MapException("SCCP_FRAME", "a unitdata message is at least five octets");
            }
            int type = message[0] & 0xFF;
            if (type != UDT) {
                throw new MapException("SCCP_UNSUPPORTED",
                        "message type " + Integer.toHexString(type) + " is not unitdata");
            }
            int protocolClass = message[1] & 0x0F;
            if (protocolClass > 1) {
                throw new MapException("SCCP_PROTOCOL_CLASS",
                        "protocol class " + protocolClass
                                + " is connection-oriented; MAP uses class 0 or 1");
            }
            int calledStart = 2 + (message[2] & 0xFF);
            int callingStart = 3 + (message[3] & 0xFF);
            int dataStart = 4 + (message[4] & 0xFF);
            if (calledStart < 5 || calledStart >= message.length) {
                throw new MapException("SCCP_POINTER", "the called party pointer points outside the message");
            }
            if (callingStart <= calledStart || callingStart >= message.length) {
                throw new MapException("SCCP_POINTER", "the calling party pointer is not after the called party");
            }
            if (dataStart <= callingStart || dataStart >= message.length) {
                throw new MapException("SCCP_POINTER", "the data pointer is not after the calling party");
            }
            Address called = Address.parse(Arrays.copyOfRange(message, calledStart, callingStart));
            Address calling = Address.parse(Arrays.copyOfRange(message, callingStart, dataStart));
            return new UnitData(called, calling, Arrays.copyOfRange(message, dataStart, message.length));
        }
    }

    private static byte[] globalTitle(String digits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(3 + (digits.length() + 1) / 2);
        // Translation type 0: the STP's routing key translates the E.164 number.
        out.write(0);
        // ISDN/E.164 numbering plan in bits 8-5, BCD odd (0) or even (1) in bits 4-1.
        out.write(0x10 | (digits.length() % 2 == 0 ? 0x01 : 0x00));
        out.write(NATURE_OF_ADDRESS_INTERNATIONAL);
        for (int i = 0; i < digits.length(); i += 2) {
            int low = digits.charAt(i) - '0';
            int high = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0;
            out.write((high << 4) | low);
        }
        return out.toByteArray();
    }

    private static String digits(byte[] data, int start, boolean even) {
        int octets = data.length - start;
        if (octets == 0) {
            throw new MapException("SCCP_GT", "the global title has no digits");
        }
        StringBuilder digits = new StringBuilder(octets * 2);
        for (int i = 0; i < octets; i++) {
            int octet = data[start + i] & 0xFF;
            int low = octet & 0x0F;
            int high = (octet >>> 4) & 0x0F;
            digits.append(decimal(low));
            if (i == octets - 1 && !even) {
                if (high != 0) {
                    throw new MapException("SCCP_GT_DIGITS",
                            "the filler nibble after an odd digit count is not 0000");
                }
            } else {
                digits.append(decimal(high));
            }
        }
        return digits.toString();
    }

    private static char decimal(int nibble) {
        if (nibble > 9) {
            throw new MapException("SCCP_GT_DIGITS",
                    "digit code " + nibble + " is not a decimal digit");
        }
        return (char) ('0' + nibble);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
