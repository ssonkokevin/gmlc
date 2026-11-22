package com.hamiltel.gmlc.mlp;

import com.hamiltel.gmlc.location.LocationResultRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes the OMA MLP answers this adapter produces. The shape is a bounded
 * circle in EPSG:4326 with the radius as the uncertainty and {@code lev_conf}
 * as the confidence, so a reader cannot take latitude and longitude without
 * the envelope that qualifies them.
 *
 * <p>MLP has no field for the positioning method. The method remains on the
 * Le record and the CDR; the answer does not invent a place to put it.
 */
final class MlpAnswer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private MlpAnswer() {
    }

    static String positions(String answerElement, String answerVersion,
                            List<Position> positions, Instant now) {
        StringBuilder xml = start(answerElement, answerVersion);
        for (Position position : positions) {
            xml.append(position.xml(now));
        }
        return xml.append("</").append(answerElement).append("></svc_result>").toString();
    }

    static String refusal(String answerElement, String answerVersion, int resid,
                          String addInfo, Instant now) {
        StringBuilder xml = start(answerElement, answerVersion);
        result(xml, resid);
        if (addInfo != null && !addInfo.isBlank()) {
            xml.append("<add_info>").append(escape(addInfo)).append("</add_info>");
        }
        return xml.append("</").append(answerElement).append("></svc_result>").toString();
    }

    static String fault(MlpFault fault) {
        return refusal("slia", "3.0.0", fault.resid(), fault.addInfo(), Instant.now());
    }

    private static StringBuilder start(String answerElement, String answerVersion) {
        return new StringBuilder(512)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<!DOCTYPE svc_result SYSTEM \"MLP_SVC_RESULT_320.DTD\">")
                .append("<svc_result><").append(answerElement)
                .append(" ver=\"").append(answerVersion).append("\">");
    }

    private static void result(StringBuilder xml, int resid) {
        xml.append("<result resid=\"").append(resid).append("\">")
                .append(escape(slogan(resid))).append("</result>");
    }

    static String slogan(int resid) {
        return switch (resid) {
            case 0 -> "OK";
            case 1 -> "SYSTEM FAILURE";
            case 2 -> "UNSPECIFIED ERROR";
            case 3 -> "UNAUTHORIZED APPLICATION";
            case 4 -> "UNKNOWN SUBSCRIBER";
            case 5 -> "ABSENT SUBSCRIBER";
            case 6 -> "POSITION METHOD FAILURE";
            case 7 -> "TIMEOUT";
            case 101 -> "CONGESTION IN LOCATION SERVER";
            case 103 -> "UNSUPPORTED VERSION";
            case 104 -> "TOO MANY POSITION ITEMS";
            case 105 -> "FORMAT ERROR";
            case 106 -> "SYNTAX ERROR";
            case 107 -> "PROTOCOL ELEMENT NOT SUPPORTED";
            case 108 -> "SERVICE NOT SUPPORTED";
            case 109 -> "PROTOCOL ELEMENT ATTRIBUTE NOT SUPPORTED";
            case 110 -> "INVALID PROTOCOL ELEMENT VALUE";
            case 111 -> "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE";
            case 201 -> "PROTOCOL ELEMENT VALUE NOT SUPPORTED";
            case 601 -> "QOP NOT ATTAINABLE";
            case 602 -> "POSITIONING NOT ALLOWED";
            case 603 -> "CONGESTION IN MOBILE NETWORK";
            case 604 -> "DISALLOWED BY LOCAL REGULATIONS";
            case 605 -> "MISCONFIGURATION OF LOCATION SERVER";
            case 606 -> "TARGET MOVED TO NEW MSC/SGSN";
            default -> "UNSPECIFIED ERROR";
        };
    }

    /** One {@code pos} element: either a position or the reason there is none. */
    record Position(MlpRequest.MlpSubject subject, LocationResultRepository.StoredResult result,
                    int resid, String addInfo) {

        static Position of(MlpRequest.MlpSubject subject,
                           LocationResultRepository.StoredResult result) {
            return new Position(subject, result, 0, null);
        }

        static Position error(MlpRequest.MlpSubject subject, int resid, String addInfo) {
            return new Position(subject, null, resid, addInfo);
        }

        String xml(Instant now) {
            StringBuilder xml = new StringBuilder(384).append("<pos><msid type=\"")
                    .append(subject.type()).append("\" enc=\"ASC\">")
                    .append(escape(subject.value())).append("</msid>");
            if (result == null) {
                xml.append("<poserr>");
                MlpAnswer.result(xml, resid);
                xml.append("<time utc_off=\"+0000\">").append(TIME.format(now)).append("</time>");
                if (addInfo != null && !addInfo.isBlank()) {
                    xml.append("<add_info>").append(escape(addInfo)).append("</add_info>");
                }
                xml.append("</poserr>");
            } else {
                appendPosition(xml, result);
            }
            return xml.append("</pos>").toString();
        }

        private static void appendPosition(StringBuilder xml,
                                           LocationResultRepository.StoredResult result) {
            Instant producedAt = result.producedAt() == null ? Instant.now() : result.producedAt();
            xml.append("<pd><time utc_off=\"+0000\">").append(TIME.format(producedAt))
                    .append("</time><shape><CircularArea srsName=\"www.epsg.org#4326\"><coord><X>")
                    .append(coordinate(result.latitude(), 'N', 'S'))
                    .append("</X><Y>")
                    .append(coordinate(result.longitude(), 'E', 'W'))
                    .append("</Y></coord><radius>").append(result.uncertaintyM())
                    .append("</radius></CircularArea></shape>");
            if (result.confidence() != null) {
                xml.append("<lev_conf>").append(result.confidence()).append("</lev_conf>");
            }
            if (result.accuracyRequestedM() != null && result.uncertaintyM() != null
                    && result.uncertaintyM() > result.accuracyRequestedM()) {
                xml.append("<qos_not_met/>");
            }
            xml.append("</pd>");
        }
    }

    /**
     * Degrees, minutes, seconds and hemisphere as MLP requires, e.g.
     * {@code 0 20 51.360N}. Rounding is carried into the minute and degree so
     * a value just below a boundary never prints sixty seconds.
     */
    static String coordinate(BigDecimal value, char positive, char negative) {
        if (value == null) {
            return "0 00 00.000" + positive;
        }
        double raw = value.doubleValue();
        char hemisphere = raw < 0 ? negative : positive;
        long thousandths = Math.round(Math.abs(raw) * 3_600_000d);
        long degrees = thousandths / 3_600_000L;
        thousandths %= 3_600_000L;
        long minutes = thousandths / 60_000L;
        thousandths %= 60_000L;
        return String.format(Locale.ROOT, "%d %02d %06.3f%c", degrees, minutes,
                thousandths / 1000d, hemisphere);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
