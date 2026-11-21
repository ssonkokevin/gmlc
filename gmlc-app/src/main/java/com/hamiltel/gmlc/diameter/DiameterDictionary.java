package com.hamiltel.gmlc.diameter;

/**
 * Codes used on SLh and SLg. Every number here is taken from the 3GPP text
 * next to it; nothing is estimated, because a wrong AVP code is silently
 * ignored by a peer rather than rejected.
 */
public final class DiameterDictionary {

    private DiameterDictionary() {
    }

    // RFC 6733 base protocol.
    public static final int USER_NAME = 1;
    public static final int HOST_IP_ADDRESS = 257;
    public static final int AUTH_APPLICATION_ID = 258;
    public static final int VENDOR_SPECIFIC_APPLICATION_ID = 260;
    public static final int SESSION_ID = 263;
    public static final int ORIGIN_HOST = 264;
    public static final int SUPPORTED_VENDOR_ID = 265;
    public static final int VENDOR_ID = 266;
    public static final int FIRMWARE_REVISION = 267;
    public static final int RESULT_CODE = 268;
    public static final int PRODUCT_NAME = 269;
    public static final int ERROR_MESSAGE = 281;
    public static final int ORIGIN_STATE_ID = 278;
    public static final int DESTINATION_REALM = 283;
    public static final int DESTINATION_HOST = 293;
    public static final int ORIGIN_REALM = 296;
    public static final int EXPERIMENTAL_RESULT = 297;
    public static final int EXPERIMENTAL_RESULT_CODE = 298;
    public static final int AUTH_SESSION_STATE = 277;
    public static final int CAPABILITIES_EXCHANGE = 257;
    public static final int CAPABILITIES_EXCHANGE_ANSWER = 257;

    // Shared 3GPP AVPs.
    public static final int MSISDN = 701;                     // TS 29.329
    public static final int IMEI = 1402;                      // TS 29.272
    public static final int LCS_FORMAT_INDICATOR = 1237;      // TS 32.299
    public static final int LCS_NAME_STRING = 1238;           // TS 32.299
    public static final int LCS_CLIENT_TYPE = 1241;           // TS 32.299
    public static final int LOCATION_ESTIMATE = 1242;         // TS 32.299
    public static final int CELL_GLOBAL_IDENTITY = 1604;      // TS 29.272 7.3.119
    public static final int SERVICE_AREA_IDENTITY = 1607;     // TS 29.272 7.3.122
    public static final int E_UTRAN_CELL_GLOBAL_IDENTITY = 1602; // TS 29.272 7.3.117
    public static final int TRACKING_AREA_IDENTITY = 1603;    // TS 29.272 7.3.118

    // SLh, TS 29.173.
    public static final long SLH_APPLICATION_ID = 16777291L;
    public static final int LCS_ROUTING_INFO = 8388622;
    public static final int LMSI = 2400;
    public static final int SERVING_NODE = 2401;
    public static final int MME_NAME = 2402;
    public static final int MSC_NUMBER = 2403;
    public static final int LCS_CAPABILITIES_SETS = 2404;
    public static final int GMLC_ADDRESS = 2405;
    public static final int ADDITIONAL_SERVING_NODE = 2406;
    public static final int PPR_ADDRESS = 2407;
    public static final int MME_REALM = 2408;
    public static final int SGSN_NAME = 2409;
    public static final int SGSN_REALM = 2410;
    public static final int RIA_FLAGS = 2411;
    public static final int SGSN_NUMBER = 1489;               // TS 29.272 7.3.102

    // SLg, TS 29.172.
    public static final long SLG_APPLICATION_ID = 16777255L;
    public static final int PROVIDE_LOCATION = 8388620;
    public static final int LOCATION_REPORT = 8388621;
    public static final int SLG_LOCATION_TYPE = 2500;
    public static final int LCS_EPS_CLIENT_NAME = 2501;
    public static final int LCS_REQUESTOR_NAME = 2502;
    public static final int LCS_PRIORITY = 2503;
    public static final int LCS_QOS = 2504;
    public static final int HORIZONTAL_ACCURACY = 2505;
    public static final int VERTICAL_ACCURACY = 2506;
    public static final int VERTICAL_REQUESTED = 2507;
    public static final int VELOCITY_REQUESTED = 2508;
    public static final int RESPONSE_TIME = 2509;
    public static final int SUPPORTED_GAD_SHAPES = 2510;
    public static final int ACCURACY_FULFILMENT_INDICATOR = 2513;
    public static final int AGE_OF_LOCATION_ESTIMATE = 2514;
    public static final int EUTRAN_POSITIONING_DATA = 2516;
    public static final int ECGI = 2517;
    public static final int PLR_FLAGS = 2545;
    public static final int PLA_FLAGS = 2546;

    // Enumerated values.
    public static final int AUTH_SESSION_STATE_NO_STATE_MAINTAINED = 1;
    public static final int SLG_LOCATION_TYPE_CURRENT_OR_LAST_KNOWN = 1;
    public static final int RESPONSE_TIME_LOW_DELAY = 0;
    public static final int LCS_CLIENT_TYPE_EMERGENCY = 0;
    public static final int LCS_CLIENT_TYPE_VALUE_ADDED = 1;
    public static final int ACCURACY_FULFILLED = 0;
    public static final int ACCURACY_NOT_FULFILLED = 1;

    // Result codes.
    public static final int DIAMETER_SUCCESS = 2001;
    public static final int DIAMETER_UNABLE_TO_COMPLY = 5012;
    public static final int DIAMETER_USER_UNKNOWN = 5001;
    public static final int DIAMETER_UNAUTHORIZED_REQUESTING_NETWORK = 5490;
    public static final int DIAMETER_ABSENT_USER = 4201;
    public static final int DIAMETER_UNREACHABLE_USER = 4221;
    public static final int DIAMETER_SUSPENDED_USER = 4222;
    public static final int DIAMETER_DETACHED_USER = 4223;
    public static final int DIAMETER_POSITIONING_DENIED = 4224;
    public static final int DIAMETER_POSITIONING_FAILED = 4225;

    /** A stable name for the result code so a caller never compares bare numbers. */
    public static String nameOf(int resultCode) {
        return switch (resultCode) {
            case DIAMETER_SUCCESS -> "DIAMETER_SUCCESS";
            case DIAMETER_USER_UNKNOWN -> "DIAMETER_ERROR_USER_UNKNOWN";
            case DIAMETER_UNAUTHORIZED_REQUESTING_NETWORK -> "DIAMETER_ERROR_UNAUTHORIZED_REQUESTING_NETWORK";
            case DIAMETER_ABSENT_USER -> "DIAMETER_ERROR_ABSENT_USER";
            case DIAMETER_UNREACHABLE_USER -> "DIAMETER_ERROR_UNREACHABLE_USER";
            case DIAMETER_SUSPENDED_USER -> "DIAMETER_ERROR_SUSPENDED_USER";
            case DIAMETER_DETACHED_USER -> "DIAMETER_ERROR_DETACHED_USER";
            case DIAMETER_POSITIONING_DENIED -> "DIAMETER_ERROR_POSITIONING_DENIED";
            case DIAMETER_POSITIONING_FAILED -> "DIAMETER_ERROR_POSITIONING_FAILED";
            case DIAMETER_UNABLE_TO_COMPLY -> "DIAMETER_UNABLE_TO_COMPLY";
            default -> "DIAMETER_RESULT_" + resultCode;
        };
    }
}
