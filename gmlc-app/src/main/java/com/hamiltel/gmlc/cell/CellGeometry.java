package com.hamiltel.gmlc.cell;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a cell's coordinates, azimuth, beamwidth and radius into a shape a
 * result can carry. WGS-84 degrees in, GeoJSON order ([longitude, latitude])
 * out, seven decimal places, spherical arcs on the local mean Earth radius
 * (decision D16).
 *
 * <p>The polygon here is a drawing of the radio plan, not a surveyed
 * boundary; the uncertainty a result reports is the radius, and the data
 * quality score says how much the geometry is worth.
 */
public final class CellGeometry {

    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private static final int MAX_ARC_STEP_DEG = 5;

    public enum Shape {
        POINT,
        CIRCLE,
        ARC,
        POLYGON;

        public String value() {
            return name();
        }
    }

    private CellGeometry() {
    }

    /**
     * POINT when there is no radius (honest but unusable for a result),
     * ARC when a beam is known, CIRCLE otherwise.
     */
    public static Shape shapeOf(Integer radiusM, Integer azimuthDeg, Integer beamwidthDeg) {
        if (radiusM == null) {
            return Shape.POINT;
        }
        if (azimuthDeg != null && beamwidthDeg != null) {
            return Shape.ARC;
        }
        return Shape.CIRCLE;
    }

    /** A full circle is a sector of 360 degrees. */
    public static List<List<BigDecimal>> circle(double lat, double lon, int radiusM) {
        return sector(lat, lon, 0, 360, radiusM);
    }

    /**
     * A sector ring: the arc from {@code azimuth - beamwidth/2} to
     * {@code azimuth + beamwidth/2}, plus the antenna for a partial sector.
     * The ring is closed (first point repeated).
     */
    public static List<List<BigDecimal>> sector(double lat, double lon, int azimuth,
                                                int beamwidth, int radiusM) {
        if (radiusM <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
        if (beamwidth < 1 || beamwidth > 360) {
            throw new IllegalArgumentException("beamwidth must be between 1 and 360 degrees");
        }

        double start = azimuth - beamwidth / 2.0;
        int segments = Math.max(8, (int) Math.ceil(beamwidth / (double) MAX_ARC_STEP_DEG));
        boolean fullCircle = beamwidth >= 360;

        List<List<BigDecimal>> ring = new ArrayList<>();
        if (!fullCircle) {
            ring.add(point(lat, lon));
        }
        for (int i = 0; i <= segments; i++) {
            double bearing = start + beamwidth * (i / (double) segments);
            ring.add(point(destination(lat, lon, bearing, radiusM)));
        }
        if (!fullCircle) {
            ring.add(point(lat, lon));
        } else {
            ring.add(ring.get(0));
        }
        return ring;
    }

    /** Great-circle distance on the mean-radius sphere, in metres. */
    public static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** GeoJSON ring for a cell, or null when there is no radius to draw. */
    public static List<List<BigDecimal>> ringOf(double lat, double lon, Integer radiusM,
                                                Integer azimuthDeg, Integer beamwidthDeg) {
        if (radiusM == null) {
            return null;
        }
        if (azimuthDeg != null && beamwidthDeg != null) {
            return sector(lat, lon, azimuthDeg, beamwidthDeg, radiusM);
        }
        return circle(lat, lon, radiusM);
    }

    public static String describe(Shape shape) {
        return shape.name().toLowerCase(Locale.ROOT);
    }

    private static double[] destination(double lat, double lon, double bearingDeg, double distanceM) {
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lon);

        double sinPhi2 = Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta);
        double phi2 = Math.asin(Math.max(-1, Math.min(1, sinPhi2)));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * sinPhi2);

        return new double[] {Math.toDegrees(phi2), normaliseLongitude(Math.toDegrees(lambda2))};
    }

    private static double normaliseLongitude(double lon) {
        double wrapped = (lon + 540) % 360 - 180;
        return wrapped == -180 && lon > 0 ? 180 : wrapped;
    }

    private static List<BigDecimal> point(double lat, double lon) {
        return List.of(round(lon), round(lat));
    }

    private static List<BigDecimal> point(double[] latLon) {
        return point(latLon[0], latLon[1]);
    }

    private static BigDecimal round(double degrees) {
        return BigDecimal.valueOf(degrees).setScale(7, RoundingMode.HALF_UP);
    }
}
