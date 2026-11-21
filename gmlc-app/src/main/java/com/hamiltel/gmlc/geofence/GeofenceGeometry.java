package com.hamiltel.gmlc.geofence;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The four area shapes the console can define, following TS 23.032: a circle,
 * a polygon of 3 to 15 points, an ellipse and an ellipsoid arc.
 *
 * <p>Circle and arc containment are geodesic (haversine distance and initial
 * bearing). Ellipse containment is a local planar approximation in metres at
 * the shape's own latitude, which is within a fraction of a percent for the
 * radius range accepted here; it refuses a centre beyond 80 degrees because
 * the approximation would stop being useful. Polygon containment uses ray
 * casting with longitudes unwrapped around the test point, so a polygon that
 * crosses the antimeridian is handled; a vertex within 0.5 degrees of a pole
 * is refused rather than answered with a guess.
 *
 * <p>The geometry is stored as the parameters the operator entered, not as a
 * bounding box, so the same shape can be re-read and re-checked later.
 */
public final class GeofenceGeometry {

    public static final double EARTH_RADIUS_M = 6_371_008.8;
    public static final double MAX_RADIUS_M = 500_000;
    public static final int MIN_POLYGON_POINTS = 3;
    public static final int MAX_POLYGON_POINTS = 15;

    private static final double POLE_LIMIT_DEG = 89.5;
    private static final double ELLIPSE_LAT_LIMIT_DEG = 80;

    public enum Shape {
        CIRCLE, POLYGON, ELLIPSE, ARC
    }

    private final Shape shape;
    private final double latitude;
    private final double longitude;
    private final double radiusM;
    private final List<GeoPoint> points;
    private final double semiMajorM;
    private final double semiMinorM;
    private final double orientationDeg;
    private final double innerRadiusM;
    private final double startAngleDeg;
    private final double includedAngleDeg;

    private GeofenceGeometry(Shape shape, double latitude, double longitude, double radiusM,
                             List<GeoPoint> points, double semiMajorM, double semiMinorM,
                             double orientationDeg, double innerRadiusM, double startAngleDeg,
                             double includedAngleDeg) {
        this.shape = shape;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusM = radiusM;
        this.points = points;
        this.semiMajorM = semiMajorM;
        this.semiMinorM = semiMinorM;
        this.orientationDeg = orientationDeg;
        this.innerRadiusM = innerRadiusM;
        this.startAngleDeg = startAngleDeg;
        this.includedAngleDeg = includedAngleDeg;
    }

    // ------------------------------------------------------------- factories

    public static GeofenceGeometry circle(double latitude, double longitude, double radiusM) {
        new GeoPoint(latitude, longitude);
        if (!(radiusM > 0) || radiusM > MAX_RADIUS_M) {
            throw new GeofenceShapeException("GEOFENCE_RADIUS_INVALID",
                    "radiusM must be greater than 0 and at most " + (long) MAX_RADIUS_M);
        }
        return new GeofenceGeometry(Shape.CIRCLE, latitude, longitude, radiusM,
                List.of(), 0, 0, 0, 0, 0, 0);
    }

    public static GeofenceGeometry polygon(List<GeoPoint> vertices) {
        List<GeoPoint> normalised = new ArrayList<>(vertices == null ? List.of() : vertices);
        if (normalised.size() > 1
                && normalised.get(0).equals(normalised.get(normalised.size() - 1))) {
            normalised.remove(normalised.size() - 1);
        }
        if (normalised.size() < MIN_POLYGON_POINTS || normalised.size() > MAX_POLYGON_POINTS) {
            throw new GeofenceShapeException("GEOFENCE_POLYGON_POINTS",
                    "a polygon needs " + MIN_POLYGON_POINTS + " to " + MAX_POLYGON_POINTS
                            + " points, not counting a repeated closing point");
        }
        for (int i = 0; i < normalised.size(); i++) {
            GeoPoint point = normalised.get(i);
            if (Math.abs(point.latitude()) > POLE_LIMIT_DEG) {
                throw new GeofenceShapeException("GEOFENCE_NEAR_POLE",
                        "a polygon vertex may not be within " + POLE_LIMIT_DEG
                                + " degrees of a pole; the interior is ambiguous there");
            }
            if (i > 0 && point.equals(normalised.get(i - 1))) {
                throw new GeofenceShapeException("GEOFENCE_POLYGON_DUPLICATE",
                        "consecutive polygon points must differ");
            }
        }
        if (Math.abs(shoelaceArea(normalised)) < 1e-12) {
            throw new GeofenceShapeException("GEOFENCE_POLYGON_DEGENERATE",
                    "the polygon encloses no area");
        }
        return new GeofenceGeometry(Shape.POLYGON, 0, 0, 0, List.copyOf(normalised),
                0, 0, 0, 0, 0, 0);
    }

    public static GeofenceGeometry ellipse(double latitude, double longitude, double semiMajorM,
                                           double semiMinorM, double orientationDeg) {
        new GeoPoint(latitude, longitude);
        if (Math.abs(latitude) > ELLIPSE_LAT_LIMIT_DEG) {
            throw new GeofenceShapeException("GEOFENCE_NEAR_POLE",
                    "an ellipse centre must be within " + ELLIPSE_LAT_LIMIT_DEG
                            + " degrees of the equator");
        }
        if (!(semiMajorM > 0) || semiMajorM > MAX_RADIUS_M) {
            throw new GeofenceShapeException("GEOFENCE_ELLIPSE_INVALID",
                    "semiMajorM must be greater than 0 and at most " + (long) MAX_RADIUS_M);
        }
        if (!(semiMinorM > 0) || semiMinorM > semiMajorM) {
            throw new GeofenceShapeException("GEOFENCE_ELLIPSE_INVALID",
                    "semiMinorM must be greater than 0 and no larger than semiMajorM");
        }
        if (!Double.isFinite(orientationDeg)) {
            throw new GeofenceShapeException("GEOFENCE_ELLIPSE_INVALID",
                    "orientationDeg must be a finite number of degrees");
        }
        return new GeofenceGeometry(Shape.ELLIPSE, latitude, longitude, 0, List.of(),
                semiMajorM, semiMinorM, normaliseDegrees(orientationDeg), 0, 0, 0);
    }

    public static GeofenceGeometry arc(double latitude, double longitude, double outerRadiusM,
                                       double innerRadiusM, double startAngleDeg,
                                       double includedAngleDeg) {
        new GeoPoint(latitude, longitude);
        if (!(outerRadiusM > 0) || outerRadiusM > MAX_RADIUS_M) {
            throw new GeofenceShapeException("GEOFENCE_ARC_INVALID",
                    "outerRadiusM must be greater than 0 and at most " + (long) MAX_RADIUS_M);
        }
        if (!(innerRadiusM >= 0) || innerRadiusM >= outerRadiusM) {
            throw new GeofenceShapeException("GEOFENCE_ARC_INVALID",
                    "innerRadiusM must be 0 or more and smaller than outerRadiusM");
        }
        if (!(includedAngleDeg > 0) || includedAngleDeg > 360) {
            throw new GeofenceShapeException("GEOFENCE_ARC_INVALID",
                    "includedAngleDeg must be greater than 0 and at most 360");
        }
        if (!Double.isFinite(startAngleDeg)) {
            throw new GeofenceShapeException("GEOFENCE_ARC_INVALID",
                    "startAngleDeg must be a finite number of degrees");
        }
        return new GeofenceGeometry(Shape.ARC, latitude, longitude, outerRadiusM, List.of(),
                0, 0, 0, innerRadiusM, normaliseDegrees(startAngleDeg), includedAngleDeg);
    }

    // ---------------------------------------------------------------- parse

    public static GeofenceGeometry parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new GeofenceShapeException("GEOFENCE_SHAPE_REQUIRED",
                    "a geometry object is required");
        }
        String shapeName = text(node, "shape");
        if (shapeName == null) {
            throw new GeofenceShapeException("GEOFENCE_SHAPE_REQUIRED",
                    "geometry.shape is required");
        }
        Shape shape;
        try {
            shape = Shape.valueOf(shapeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GeofenceShapeException("GEOFENCE_SHAPE_UNSUPPORTED",
                    "shape must be one of CIRCLE, POLYGON, ELLIPSE or ARC");
        }
        return switch (shape) {
            case CIRCLE -> circle(number(node, "latitude"), number(node, "longitude"),
                    number(node, "radiusM"));
            case POLYGON -> polygon(points(node));
            case ELLIPSE -> ellipse(number(node, "latitude"), number(node, "longitude"),
                    number(node, "semiMajorM"), number(node, "semiMinorM"),
                    number(node, "orientationDeg"));
            case ARC -> arc(number(node, "latitude"), number(node, "longitude"),
                    number(node, "outerRadiusM"), number(node, "innerRadiusM"),
                    number(node, "startAngleDeg"), number(node, "includedAngleDeg"));
        };
    }

    private static List<GeoPoint> points(JsonNode node) {
        JsonNode array = node.get("points");
        if (array == null || !array.isArray()) {
            throw new GeofenceShapeException("GEOFENCE_POLYGON_POINTS",
                    "geometry.points must be an array of vertices");
        }
        List<GeoPoint> vertices = new ArrayList<>(array.size());
        for (JsonNode point : array) {
            vertices.add(new GeoPoint(number(point, "latitude"), number(point, "longitude")));
        }
        return vertices;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isNumber()) {
            throw new GeofenceShapeException("GEOFENCE_SHAPE_INVALID",
                    field + " must be a number");
        }
        double result = value.asDouble();
        if (!Double.isFinite(result)) {
            throw new GeofenceShapeException("GEOFENCE_SHAPE_INVALID",
                    field + " must be finite");
        }
        return result;
    }

    // -------------------------------------------------------------- to JSON

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("shape", shape.name());
        switch (shape) {
            case CIRCLE -> {
                json.put("latitude", latitude);
                json.put("longitude", longitude);
                json.put("radiusM", radiusM);
            }
            case POLYGON -> {
                List<Map<String, Object>> vertices = new ArrayList<>(points.size());
                for (GeoPoint point : points) {
                    Map<String, Object> vertex = new LinkedHashMap<>();
                    vertex.put("latitude", point.latitude());
                    vertex.put("longitude", point.longitude());
                    vertices.add(vertex);
                }
                json.put("points", vertices);
            }
            case ELLIPSE -> {
                json.put("latitude", latitude);
                json.put("longitude", longitude);
                json.put("semiMajorM", semiMajorM);
                json.put("semiMinorM", semiMinorM);
                json.put("orientationDeg", orientationDeg);
            }
            case ARC -> {
                json.put("latitude", latitude);
                json.put("longitude", longitude);
                json.put("outerRadiusM", radiusM);
                json.put("innerRadiusM", innerRadiusM);
                json.put("startAngleDeg", startAngleDeg);
                json.put("includedAngleDeg", includedAngleDeg);
            }
        }
        return json;
    }

    // ------------------------------------------------------------ containment

    /** Whether a WGS-84 point lies inside the shape, boundary included. */
    public boolean contains(double testLatitude, double testLongitude) {
        GeoPoint point = new GeoPoint(testLatitude, testLongitude);
        return switch (shape) {
            case CIRCLE -> distanceM(latitude, longitude, point.latitude(), point.longitude())
                    <= radiusM;
            case POLYGON -> polygonContains(point);
            case ELLIPSE -> ellipseContains(point);
            case ARC -> arcContains(point);
        };
    }

    private boolean polygonContains(GeoPoint point) {
        double px = point.longitude();
        double py = point.latitude();
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            double xi = unwrapLongitude(points.get(i).longitude(), px);
            double yi = points.get(i).latitude();
            double xj = unwrapLongitude(points.get(j).longitude(), px);
            double yj = points.get(j).latitude();
            boolean crosses = (yi > py) != (yj > py);
            if (crosses && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private boolean ellipseContains(GeoPoint point) {
        double deltaLon = normaliseSigned(point.longitude() - longitude);
        double x = Math.toRadians(deltaLon) * Math.cos(Math.toRadians(latitude))
                * EARTH_RADIUS_M;
        double y = Math.toRadians(point.latitude() - latitude) * EARTH_RADIUS_M;
        // Orientation is the bearing of the major axis, clockwise from north.
        double theta = Math.toRadians(orientationDeg);
        double alongMajor = x * Math.sin(theta) + y * Math.cos(theta);
        double alongMinor = x * Math.cos(theta) - y * Math.sin(theta);
        return (alongMajor * alongMajor) / (semiMajorM * semiMajorM)
                + (alongMinor * alongMinor) / (semiMinorM * semiMinorM) <= 1.0;
    }

    private boolean arcContains(GeoPoint point) {
        double distance = distanceM(latitude, longitude, point.latitude(), point.longitude());
        if (distance < innerRadiusM || distance > radiusM) {
            return false;
        }
        if (includedAngleDeg >= 359.999) {
            return true;
        }
        double delta = normaliseDegrees(
                bearingDeg(latitude, longitude, point.latitude(), point.longitude())
                        - startAngleDeg);
        return delta <= includedAngleDeg;
    }

    static double distanceM(double fromLat, double fromLon, double toLat, double toLon) {
        double phi1 = Math.toRadians(fromLat);
        double phi2 = Math.toRadians(toLat);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(normaliseSigned(toLon - fromLon));
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double bearingDeg(double fromLat, double fromLon, double toLat, double toLon) {
        double phi1 = Math.toRadians(fromLat);
        double phi2 = Math.toRadians(toLat);
        double dLambda = Math.toRadians(normaliseSigned(toLon - fromLon));
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return normaliseDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    static double normaliseSigned(double degrees) {
        double value = degrees % 360;
        if (value >= 180) {
            value -= 360;
        } else if (value < -180) {
            value += 360;
        }
        return value;
    }

    static double normaliseDegrees(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }

    private static double unwrapLongitude(double longitude, double reference) {
        return reference + normaliseSigned(longitude - reference);
    }

    private static double shoelaceArea(List<GeoPoint> vertices) {
        double reference = vertices.get(0).longitude();
        double sum = 0;
        for (int i = 0; i < vertices.size(); i++) {
            GeoPoint a = vertices.get(i);
            GeoPoint b = vertices.get((i + 1) % vertices.size());
            double ax = unwrapLongitude(a.longitude(), reference);
            double bx = unwrapLongitude(b.longitude(), reference);
            sum += ax * b.latitude() - bx * a.latitude();
        }
        return sum / 2;
    }

    // ------------------------------------------------------------- accessors

    public Shape shape() {
        return shape;
    }

    public String shapeName() {
        return shape.name();
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double radiusM() {
        return radiusM;
    }

    public List<GeoPoint> points() {
        return points;
    }

    public double semiMajorM() {
        return semiMajorM;
    }

    public double semiMinorM() {
        return semiMinorM;
    }

    public double orientationDeg() {
        return orientationDeg;
    }

    public double innerRadiusM() {
        return innerRadiusM;
    }

    public double startAngleDeg() {
        return startAngleDeg;
    }

    public double includedAngleDeg() {
        return includedAngleDeg;
    }
}
