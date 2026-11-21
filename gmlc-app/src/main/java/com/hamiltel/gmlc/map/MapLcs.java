package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.diameter.Tbcd;
import com.hamiltel.gmlc.diameter.UncertaintyCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The three MAP operations the GMLC speaks, with the argument and result
 * shapes of TS 29.002 as the 2024 (version 22) text defines them. The modules
 * are {@code IMPLICIT TAGS}: a context tag on a SEQUENCE replaces the
 * universal tag, while a context tag on a CHOICE is explicit because a CHOICE
 * has no tag of its own to replace.
 *
 * <p>Only what the cell-ID first phase needs is modelled: routing to the
 * serving node, a provide-subscriber-location answer, and an any-time
 * interrogation answer in either domain. A field this side does not read is
 * skipped by tag, and an answer that carries something other than an
 * ellipsoid point with uncertainty circle fails rather than being approximated.
 */
public final class MapLcs {

    /** locationSvcGatewayContext-v3, for sendRoutingInfoForLCS and provideSubscriberLocation. */
    public static final byte[] AC_LOCATION_SERVICE_GATEWAY = Ber.oid(0, 4, 0, 0, 1, 0, 37, 3);
    /** anyTimeEnquiryContext-v3, for anyTimeInterrogation. */
    public static final byte[] AC_ANY_TIME_ENQUIRY = Ber.oid(0, 4, 0, 0, 1, 0, 29, 3);

    public static final int OP_PROVIDE_SUBSCRIBER_LOCATION = 83;
    public static final int OP_SEND_ROUTING_INFO_FOR_LCS = 85;
    public static final int OP_ANY_TIME_INTERROGATION = 71;

    /** International E.164: extension bit set, nature of address 001, ISDN numbering plan 0001. */
    private static final byte INTERNATIONAL = (byte) 0x91;

    private static final Map<Integer, String> ERRORS = Map.ofEntries(
            Map.entry(1, "UNKNOWN_SUBSCRIBER"),
            Map.entry(5, "UNIDENTIFIED_SUBSCRIBER"),
            Map.entry(9, "ILLEGAL_SUBSCRIBER"),
            Map.entry(12, "ILLEGAL_EQUIPMENT"),
            Map.entry(21, "FACILITY_NOT_SUPPORTED"),
            Map.entry(27, "ABSENT_SUBSCRIBER"),
            Map.entry(34, "SYSTEM_FAILURE"),
            Map.entry(35, "DATA_MISSING"),
            Map.entry(36, "UNEXPECTED_DATA_VALUE"),
            Map.entry(51, "RESOURCE_LIMITATION"),
            Map.entry(52, "UNAUTHORIZED_REQUESTING_NETWORK"),
            Map.entry(53, "UNAUTHORIZED_LCS_CLIENT"),
            Map.entry(54, "POSITION_METHOD_FAILURE"),
            Map.entry(58, "UNKNOWN_OR_UNREACHABLE_LCS_CLIENT"));

    private MapLcs() {
    }

    public record CellArea(String mcc, String mnc, int areaCode, Integer cellCode) {

        /** The cell plan's canonical form: mcc-mnc-lac-ci, or mcc-mnc-lac when it is an LAI. */
        public String identity() {
            return mcc + "-" + mnc + "-" + areaCode + (cellCode == null ? "" : "-" + cellCode);
        }
    }

    public record EutranCell(String mcc, String mnc, Integer tac, long cellCode) {
    }

    public record CsCell(Integer ageMinutes, Gad.Circle circle, String vlrNumber,
                         String mscNumber, CellArea cell, boolean serviceArea,
                         boolean currentLocationRetrieved) {
    }

    public record PsCell(Integer ageMinutes, Gad.Circle circle, String sgsnNumber,
                         CellArea cell, boolean serviceArea, boolean currentLocationRetrieved) {
    }

    public record EpsCell(Integer ageMinutes, Gad.Circle circle, EutranCell cell,
                          String mmeName, boolean currentLocationRetrieved) {
    }

    public record AnyTimeResult(CsCell cs, PsCell ps, EpsCell eps) {
    }

    public record RoutingInfo(String imsi, String msisdn, String networkNode,
                              boolean gprsNode, String mmeName, boolean sgsnNamePresent) {
    }

    public record ProvideSubscriberLocationResult(Gad.Circle circle, Integer ageMinutes,
                                                  CellArea cell, boolean serviceArea,
                                                  Boolean accuracyFulfilled) {
    }

    public static String errorName(int code) {
        return ERRORS.getOrDefault(code, "MAP_ERROR_" + code);
    }

    // ---------------------------------------------------------------- routing

    /**
     * {@code RoutingInfoForLCS-Arg ::= SEQUENCE { mlcNumber [0] ISDN-AddressString,
     * targetMS [1] SubscriberIdentity }}.
     */
    public static byte[] routingInfoForLcs(String mlcNumber, String imsi, String msisdn) {
        return Ber.sequence(
                Ber.tlv(Ber.context(0), address(mlcNumber)),
                Ber.tlv(Ber.contextConstructed(1), subscriberChoice(imsi, msisdn)));
    }

    public static RoutingInfo parseRoutingInfoForLcs(byte[] argument) {
        Ber.Tlv root = Ber.parse(argument);
        String imsi = null;
        String msisdn = null;
        String networkNode = null;
        String mmeName = null;
        boolean gprsNode = false;
        boolean sgsnNamePresent = false;
        for (Ber.Tlv field : root.children()) {
            if (field.tag() == Ber.contextConstructed(0)) {
                String[] target = decodeSubscriberChoice(field);
                imsi = target[0];
                msisdn = target[1];
            } else if (field.tag() == Ber.contextConstructed(1)) {
                for (Ber.Tlv item : field.children()) {
                    if (item.tag() == Ber.OCTET_STRING) {
                        networkNode = digits(item.value());
                    } else if (item.tag() == Ber.context(2)) {
                        gprsNode = true;
                    } else if (item.tag() == Ber.context(6)) {
                        mmeName = new String(item.value(), StandardCharsets.US_ASCII);
                    } else if (item.tag() == Ber.context(9)) {
                        sgsnNamePresent = true;
                    }
                }
            }
        }
        return new RoutingInfo(imsi, msisdn, networkNode, gprsNode, mmeName, sgsnNamePresent);
    }

    // ----------------------------------------------------- provide location

    /**
     * {@code ProvideSubscriberLocation-Arg}: current location, our MLC number,
     * the LCS client, the target and, when given, the requested horizontal
     * accuracy as the smallest TS 23.032 uncertainty code that covers it.
     */
    public static byte[] provideSubscriberLocation(String mlcNumber, String imsi, String msisdn,
                                                   Integer accuracyMetres, String clientName) {
        List<byte[]> fields = new ArrayList<>();
        fields.add(Ber.sequence(Ber.tlv(Ber.context(0), new byte[] {0})));
        fields.add(Ber.octets(address(mlcNumber)));
        fields.add(Ber.tlv(Ber.contextConstructed(0), clientId(clientName)));
        fields.add(imsi != null
                ? Ber.tlv(Ber.context(2), imsiBytes(imsi))
                : Ber.tlv(Ber.context(3), address(msisdn)));
        if (accuracyMetres != null) {
            int code = UncertaintyCode.codeForMetres(accuracyMetres);
            fields.add(Ber.tlv(Ber.contextConstructed(7),
                    Ber.tlv(Ber.context(0), new byte[] {(byte) code})));
        }
        return Ber.sequence(fields.toArray(new byte[0][]));
    }

    public static ProvideSubscriberLocationResult parseProvideSubscriberLocation(byte[] argument) {
        Ber.Tlv root = Ber.parse(argument);
        Gad.Circle circle = null;
        Integer ageMinutes = null;
        CellArea cell = null;
        boolean serviceArea = false;
        Boolean accuracyFulfilled = null;
        for (Ber.Tlv field : root.children()) {
            if (field.tag() == Ber.OCTET_STRING) {
                circle = Gad.decodeUncertaintyCircle(field.value());
            } else if (field.tag() == Ber.context(0)) {
                ageMinutes = field.intValue();
            } else if (field.tag() == Ber.contextConstructed(6)) {
                cell = area(field);
            } else if (field.tag() == Ber.context(7)) {
                serviceArea = true;
            } else if (field.tag() == Ber.context(8)) {
                accuracyFulfilled = switch (field.intValue()) {
                    case 0 -> Boolean.TRUE;
                    case 1 -> Boolean.FALSE;
                    default -> null;
                };
            }
        }
        if (circle == null) {
            throw new MapException("MAP_NO_LOCATION_ESTIMATE",
                    "the provide-subscriber-location answer carried no usable location estimate");
        }
        return new ProvideSubscriberLocationResult(circle, ageMinutes, cell, serviceArea,
                accuracyFulfilled);
    }

    // ---------------------------------------------------------- any time

    /**
     * {@code AnyTimeInterrogationArg}: the target, the information asked for
     * (location, optionally an up-to-date location and the subscriber state)
     * and the requesting node's address.
     */
    public static byte[] anyTimeInterrogation(String gsmScfAddress, String imsi, String msisdn,
                                              boolean currentLocation, boolean subscriberState) {
        List<byte[]> requested = new ArrayList<>();
        requested.add(Ber.nullValue(Ber.context(0)));
        if (subscriberState) {
            requested.add(Ber.nullValue(Ber.context(1)));
        }
        if (currentLocation) {
            requested.add(Ber.nullValue(Ber.context(3)));
        }
        return Ber.sequence(
                Ber.tlv(Ber.contextConstructed(0), subscriberChoice(imsi, msisdn)),
                Ber.tlv(Ber.contextConstructed(1), Ber.concat(requested.toArray(new byte[0][]))),
                Ber.tlv(Ber.context(3), address(gsmScfAddress)));
    }

    public static AnyTimeResult parseAnyTimeInterrogation(byte[] argument) {
        Ber.Tlv root = Ber.parse(argument);
        Ber.Tlv subscriberInfo = null;
        for (Ber.Tlv field : root.children()) {
            if (field.tag() == Ber.SEQUENCE && subscriberInfo == null) {
                subscriberInfo = field;
            }
        }
        if (subscriberInfo == null) {
            throw new MapException("MAP_NO_SUBSCRIBER_INFO",
                    "the any-time answer carried no subscriber information");
        }
        CsCell cs = null;
        PsCell ps = null;
        EpsCell eps = null;
        for (Ber.Tlv part : subscriberInfo.children()) {
            if (part.tag() == Ber.contextConstructed(0)) {
                cs = parseCsLocation(part);
            } else if (part.tag() == Ber.contextConstructed(3)) {
                ps = parsePsLocation(part);
            } else if (part.tag() == Ber.contextConstructed(13)) {
                eps = parseEpsLocation(part);
            }
        }
        return new AnyTimeResult(cs, ps, eps);
    }

    private static CsCell parseCsLocation(Ber.Tlv locationInformation) {
        Integer age = null;
        Gad.Circle circle = null;
        String vlrNumber = null;
        String mscNumber = null;
        CellArea cell = null;
        boolean serviceArea = false;
        boolean currentLocationRetrieved = false;
        for (Ber.Tlv item : locationInformation.children()) {
            if (item.tag() == Ber.INTEGER) {
                age = item.intValue();
            } else if (item.tag() == Ber.context(0)) {
                circle = Gad.decodeUncertaintyCircle(item.value());
            } else if (item.tag() == Ber.context(1)) {
                vlrNumber = digits(item.value());
            } else if (item.tag() == Ber.contextConstructed(3)) {
                cell = area(item);
            } else if (item.tag() == Ber.context(6)) {
                mscNumber = digits(item.value());
            } else if (item.tag() == Ber.context(8)) {
                currentLocationRetrieved = true;
            } else if (item.tag() == Ber.context(9)) {
                serviceArea = true;
            }
        }
        return new CsCell(age, circle, vlrNumber, mscNumber, cell, serviceArea,
                currentLocationRetrieved);
    }

    private static PsCell parsePsLocation(Ber.Tlv locationInformationGprs) {
        CellArea cell = null;
        Gad.Circle circle = null;
        String sgsnNumber = null;
        boolean serviceArea = false;
        boolean currentLocationRetrieved = false;
        Integer age = null;
        for (Ber.Tlv item : locationInformationGprs.children()) {
            if (item.tag() == Ber.contextConstructed(0)) {
                cell = area(item);
            } else if (item.tag() == Ber.context(2)) {
                circle = Gad.decodeUncertaintyCircle(item.value());
            } else if (item.tag() == Ber.context(3)) {
                sgsnNumber = digits(item.value());
            } else if (item.tag() == Ber.context(6)) {
                serviceArea = true;
            } else if (item.tag() == Ber.context(8)) {
                currentLocationRetrieved = true;
            } else if (item.tag() == Ber.context(9)) {
                age = item.intValue();
            }
        }
        return new PsCell(age, circle, sgsnNumber, cell, serviceArea, currentLocationRetrieved);
    }

    private static EpsCell parseEpsLocation(Ber.Tlv locationInformationEps) {
        Gad.Circle circle = null;
        String mmeName = null;
        boolean currentLocationRetrieved = false;
        Integer age = null;
        byte[] cgi = null;
        byte[] trackingAreaId = null;
        for (Ber.Tlv item : locationInformationEps.children()) {
            if (item.tag() == Ber.context(0)) {
                cgi = item.value();
            } else if (item.tag() == Ber.context(1)) {
                trackingAreaId = item.value();
            } else if (item.tag() == Ber.context(3)) {
                circle = Gad.decodeUncertaintyCircle(item.value());
            } else if (item.tag() == Ber.context(5)) {
                currentLocationRetrieved = true;
            } else if (item.tag() == Ber.context(6)) {
                age = item.intValue();
            } else if (item.tag() == Ber.context(7)) {
                mmeName = new String(item.value(), StandardCharsets.US_ASCII);
            }
        }
        return new EpsCell(age, circle, eutranCell(cgi, trackingAreaId), mmeName,
                currentLocationRetrieved);
    }

    // ------------------------------------------------------------ address

    static byte[] address(String digits) {
        return Ber.concat(new byte[] {INTERNATIONAL}, Tbcd.encode(e164(digits)));
    }

    static String digits(byte[] address) {
        if (address.length < 2 || (address[0] & 0x80) == 0) {
            throw new MapException("MAP_ADDRESS", "an address string has no extension bit set");
        }
        String digits = Tbcd.decode(Arrays.copyOfRange(address, 1, address.length));
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new MapException("MAP_ADDRESS", "an address string carries no digits");
        }
        return digits;
    }

    static byte[] imsiBytes(String imsi) {
        if (imsi == null || imsi.length() < 6 || imsi.length() > 15
                || !imsi.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("an IMSI is 6 to 15 decimal digits");
        }
        return Tbcd.encode(imsi);
    }

    /** The CHOICE body: imsi [0] or msisdn [1]; a caller wraps it in its own tag. */
    static byte[] subscriberChoice(String imsi, String msisdn) {
        if ((imsi == null) == (msisdn == null)) {
            throw new IllegalArgumentException("exactly one of imsi and msisdn is required");
        }
        return imsi != null
                ? Ber.tlv(Ber.context(0), imsiBytes(imsi))
                : Ber.tlv(Ber.context(1), address(msisdn));
    }

    private static String[] decodeSubscriberChoice(Ber.Tlv choice) {
        Ber.Tlv inner = single(choice);
        if (inner.tag() == Ber.context(0)) {
            return new String[] {Tbcd.decode(inner.value()), null};
        }
        if (inner.tag() == Ber.context(1)) {
            return new String[] {null, digits(inner.value())};
        }
        throw new MapException("MAP_TARGET", "unknown subscriber identity tag "
                + Integer.toHexString(inner.tag()));
    }

    private static String e164(String digits) {
        if (digits == null || digits.length() < 5 || digits.length() > 15
                || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("an E.164 number is 5 to 15 decimal digits");
        }
        return digits;
    }

    // -------------------------------------------------------------- cells

    private static CellArea area(Ber.Tlv choice) {
        Ber.Tlv inner = single(choice);
        if (inner.tag() == Ber.context(0)) {
            byte[] fixed = inner.value();
            if (fixed.length != 7) {
                throw new MapException("MAP_CELL", "a cell global identity is seven octets");
            }
            String[] plmn = plmn(fixed);
            return new CellArea(plmn[0], plmn[1], unsigned16(fixed, 3), unsigned16(fixed, 5));
        }
        if (inner.tag() == Ber.context(1)) {
            byte[] fixed = inner.value();
            if (fixed.length != 5) {
                throw new MapException("MAP_CELL", "a location area identity is five octets");
            }
            String[] plmn = plmn(fixed);
            return new CellArea(plmn[0], plmn[1], unsigned16(fixed, 3), null);
        }
        throw new MapException("MAP_CELL", "unknown cell identity tag "
                + Integer.toHexString(inner.tag()));
    }

    /**
     * E-UTRAN CGI is PLMN (3 octets) and ECI (4 octets); the TAC comes from a
     * separate TA-Id (PLMN and 2 octets). A TAC on its own names a tracking
     * area, not a cell, so the answer yields no cell until a CGI arrives.
     */
    private static EutranCell eutranCell(byte[] cgi, byte[] taId) {
        if (cgi == null) {
            return null;
        }
        if (cgi.length != 7) {
            throw new MapException("MAP_CELL", "an E-UTRAN CGI is seven octets");
        }
        String[] plmn = plmn(cgi);
        long cellCode = ((long) (cgi[3] & 0xff) << 24) | ((cgi[4] & 0xff) << 16)
                | ((cgi[5] & 0xff) << 8) | (cgi[6] & 0xff);
        Integer tac = null;
        if (taId != null) {
            if (taId.length != 5) {
                throw new MapException("MAP_CELL", "a tracking area identity is five octets");
            }
            tac = unsigned16(taId, 3);
        }
        return new EutranCell(plmn[0], plmn[1], tac, cellCode);
    }

    private static String[] plmn(byte[] firstThree) {
        StringBuilder digits = new StringBuilder(6);
        for (int i = 0; i < 3; i++) {
            int low = firstThree[i] & 0x0f;
            int high = (firstThree[i] >> 4) & 0x0f;
            if (low > 9 || (high > 9 && high != 0xf)) {
                throw new MapException("MAP_PLMN", "a PLMN carries a non-decimal digit");
            }
            digits.append((char) ('0' + low));
            if (high != 0xf) {
                digits.append((char) ('0' + high));
            }
        }
        String mnc = digits.substring(3);
        if (mnc.isEmpty() || mnc.length() > 3) {
            throw new MapException("MAP_PLMN", "a PLMN carries " + mnc.length() + " MNC digits");
        }
        return new String[] {digits.substring(0, 3), mnc};
    }

    static int unsigned16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static byte[] clientId(String name) {
        byte[] type = Ber.tlv(Ber.context(0), new byte[] {1});
        if (name == null || name.isBlank()) {
            return type;
        }
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > 63) {
            throw new IllegalArgumentException("the LCS client name is at most 63 octets");
        }
        // GSM 7-bit default alphabet (0x0F); the basic Latin letters are identical.
        byte[] nameString = Ber.concat(
                Ber.tlv(Ber.context(0), new byte[] {0x0f}),
                Ber.tlv(Ber.context(2), encoded));
        return Ber.concat(type, Ber.tlv(Ber.contextConstructed(4), nameString));
    }

    private static Ber.Tlv single(Ber.Tlv constructed) {
        List<Ber.Tlv> children = constructed.children();
        if (children.size() != 1) {
            throw new MapException("MAP_CHOICE",
                    "a CHOICE must carry one alternative, found " + children.size());
        }
        return children.get(0);
    }
}
