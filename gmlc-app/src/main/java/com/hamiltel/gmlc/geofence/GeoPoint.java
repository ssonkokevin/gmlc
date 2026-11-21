package com.hamiltel.gmlc.geofence;

/** A WGS-84 coordinate. Longitude is -180 to 180 inclusive. */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (!isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new GeofenceShapeException("GEOFENCE_POINT_INVALID",
                    "latitude must be between -90 and 90 degrees");
        }
        if (!isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new GeofenceShapeException("GEOFENCE_POINT_INVALID",
                    "longitude must be between -180 and 180 degrees");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
