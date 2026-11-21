package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.diameter.UncertaintyCode;

/**
 * The one geographic area description the MAP location answer uses: an
 * ellipsoid point with uncertainty circle (TS 23.032 section 6.1 and 6.2).
 * Latitude is a sign bit and 23 bits of degrees over 90; longitude is a 24-bit
 * two's complement over 180; the last octet is the 7-bit uncertainty code,
 * whose radius is 10 * (1.1^K - 1) metres at 67% confidence.
 */
public final class Gad {

    public static final int POINT = 0x00;
    public static final int POINT_WITH_UNCERTAINTY_CIRCLE = 0x01;

    private static final int SCALE = 1 << 23;

    private Gad() {
    }

    public record Circle(double latitude, double longitude, long uncertaintyMetres) {

        public boolean inside(double pointLatitude, double pointLongitude) {
            return distanceMetres(latitude, longitude, pointLatitude, pointLongitude) <= uncertaintyMetres;
        }
    }

    public static Circle decodeUncertaintyCircle(byte[] description) {
        if (description == null || description.length == 0) {
            throw new MapException("GAD_EMPTY", "the location estimate is empty");
        }
        int shape = description[0] & 0xff;
        if (shape != POINT_WITH_UNCERTAINTY_CIRCLE) {
            throw new MapException("GAD_SHAPE",
                    "shape 0x" + Integer.toHexString(shape)
                            + " is not an ellipsoid point with uncertainty circle");
        }
        if (description.length != 8) {
            throw new MapException("GAD_LENGTH",
                    "an uncertainty circle is eight octets, not " + description.length);
        }
        if ((description[7] & 0x80) != 0) {
            throw new MapException("GAD_UNCERTAINTY",
                    "the uncertainty octet has its spare bit set");
        }
        int rawLatitude = ((description[1] & 0x7f) << 16)
                | ((description[2] & 0xff) << 8)
                | (description[3] & 0xff);
        double latitude = rawLatitude * 90.0 / SCALE;
        if ((description[1] & 0x80) != 0) {
            latitude = -latitude;
        }
        int rawLongitude = ((description[4] & 0xff) << 16)
                | ((description[5] & 0xff) << 8)
                | (description[6] & 0xff);
        if ((rawLongitude & 0x800000) != 0) {
            rawLongitude |= 0xff000000;
        }
        double longitude = rawLongitude * 180.0 / SCALE;
        long uncertainty = UncertaintyCode.radiusMetres(description[7] & 0x7f);
        return new Circle(latitude, longitude, uncertainty);
    }

    public static byte[] encodeCircle(double latitude, double longitude, int uncertaintyCode) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
        UncertaintyCode.radiusMetres(uncertaintyCode);
        long rawLatitude = (long) Math.floor(Math.abs(latitude) * SCALE / 90.0);
        long rawLongitude = (long) Math.floor(longitude * SCALE / 180.0);
        byte[] encoded = new byte[8];
        encoded[0] = POINT_WITH_UNCERTAINTY_CIRCLE;
        encoded[1] = (byte) ((rawLatitude >>> 16) & 0x7f);
        if (latitude < 0) {
            encoded[1] |= (byte) 0x80;
        }
        encoded[2] = (byte) (rawLatitude >>> 8);
        encoded[3] = (byte) rawLatitude;
        encoded[4] = (byte) (rawLongitude >>> 16);
        encoded[5] = (byte) (rawLongitude >>> 8);
        encoded[6] = (byte) rawLongitude;
        encoded[7] = (byte) uncertaintyCode;
        return encoded;
    }

    /** Haversine distance, so a circle's radius can be checked against a point. */
    public static double distanceMetres(double latitude1, double longitude1,
                                        double latitude2, double longitude2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(latitude2 - latitude1);
        double dLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
