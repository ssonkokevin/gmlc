package com.hamiltel.gmlc.diameter;

/**
 * Reads the result out of a Diameter answer and turns a 3GPP error into a
 * stable failure code. The mapping is fixed so a client sees the same code for
 * "subscriber not known" whether it came from SLh or SLg.
 */
public final class DiameterResult {

    private DiameterResult() {
    }

    public static int codeOf(DiameterMessage message) {
        Integer result = message.integer(DiameterDictionary.RESULT_CODE);
        if (result != null) {
            return result;
        }
        return message.first(DiameterDictionary.EXPERIMENTAL_RESULT)
                .flatMap(experimental -> experimental.children().stream()
                        .filter(avp -> avp.code() == DiameterDictionary.EXPERIMENTAL_RESULT_CODE)
                        .findFirst())
                .map(Avp::asInt)
                .orElse(-1);
    }

    public static String errorText(DiameterMessage message) {
        String text = message.text(DiameterDictionary.ERROR_MESSAGE);
        return text == null ? "" : text;
    }

    public static void requireSuccess(DiameterMessage answer, int expectedCommand, String operation) {
        if (answer.request()) {
            throw new SignallingException("DIAMETER_PROTOCOL_ERROR",
                    operation + " answered with a request instead of an answer");
        }
        if (answer.commandCode() != expectedCommand) {
            throw new SignallingException("DIAMETER_PROTOCOL_ERROR",
                    operation + " answered with command " + answer.commandCode());
        }
        int code = codeOf(answer);
        if (code == DiameterDictionary.DIAMETER_SUCCESS) {
            return;
        }
        String detail = errorText(answer);
        throw new SignallingException(failureCode(code), operation + " failed: "
                + DiameterDictionary.nameOf(code) + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    public static String failureCode(int resultCode) {
        return switch (resultCode) {
            case DiameterDictionary.DIAMETER_USER_UNKNOWN -> "SUBSCRIBER_UNKNOWN";
            case DiameterDictionary.DIAMETER_ABSENT_USER,
                 DiameterDictionary.DIAMETER_UNREACHABLE_USER,
                 DiameterDictionary.DIAMETER_SUSPENDED_USER,
                 DiameterDictionary.DIAMETER_DETACHED_USER -> "SUBSCRIBER_NOT_REACHABLE";
            case DiameterDictionary.DIAMETER_POSITIONING_DENIED -> "POSITIONING_DENIED";
            case DiameterDictionary.DIAMETER_POSITIONING_FAILED -> "POSITIONING_FAILED";
            case DiameterDictionary.DIAMETER_UNAUTHORIZED_REQUESTING_NETWORK -> "GMLC_NOT_AUTHORISED";
            default -> "DIAMETER_ERROR_" + resultCode;
        };
    }
}
