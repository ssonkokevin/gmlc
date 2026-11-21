package com.hamiltel.gmlc.geofence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four TS 23.032 shapes, checked against hand-computed distances at
 * Kampala's latitude. A degree of latitude is about 111.2 km and a degree of
 * longitude there is about 111.1 km, so the points below sit where the test
 * comments say they do.
 */
class GeofenceGeometryTest {

    private static final double KAMPALA_LAT = 0.3476;
    private static final double KAMPALA_LON = 32.5825;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void circleUsesGeodesicDistanceAndRefusesAnImpossibleRadius() {
        GeofenceGeometry circle = GeofenceGeometry.circle(KAMPALA_LAT, KAMPALA_LON, 500);

        assertTrue(circle.contains(KAMPALA_LAT, KAMPALA_LON));
        assertTrue(circle.contains(KAMPALA_LAT + 0.002, KAMPALA_LON), "222 m north is inside");
        assertFalse(circle.contains(KAMPALA_LAT + 0.01, KAMPALA_LON), "1.1 km north is outside");
        assertFalse(circle.contains(KAMPALA_LAT, KAMPALA_LON + 0.01), "1.1 km east is outside");

        assertEquals("GEOFENCE_RADIUS_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.circle(KAMPALA_LAT, KAMPALA_LON, 0)).code());
        assertEquals("GEOFENCE_RADIUS_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.circle(KAMPALA_LAT, KAMPALA_LON, 600_000)).code());
    }

    @Test
    void polygonRayCastingHandlesHolesAndTheAntimeridian() {
        GeofenceGeometry square = GeofenceGeometry.polygon(List.of(
                new GeoPoint(0.30, 32.55), new GeoPoint(0.30, 32.61),
                new GeoPoint(0.40, 32.61), new GeoPoint(0.40, 32.55)));

        assertTrue(square.contains(0.35, 32.58));
        assertFalse(square.contains(0.25, 32.58));
        assertFalse(square.contains(0.35, 32.70));

        // A closing point repeated from GeoJSON is dropped, not counted twice.
        GeofenceGeometry closed = GeofenceGeometry.polygon(List.of(
                new GeoPoint(0.30, 32.55), new GeoPoint(0.30, 32.61),
                new GeoPoint(0.40, 32.61), new GeoPoint(0.30, 32.55)));
        assertEquals(3, closed.points().size());

        GeofenceGeometry crossing = GeofenceGeometry.polygon(List.of(
                new GeoPoint(0.0, 179.9), new GeoPoint(0.0, -179.9),
                new GeoPoint(1.0, -179.9), new GeoPoint(1.0, 179.9)));
        assertTrue(crossing.contains(0.5, 180.0), "180 is inside the unwrapped polygon");
        assertTrue(crossing.contains(0.5, -179.95), "179.95W is inside");
        assertTrue(crossing.contains(0.5, 179.95), "179.95E is inside");
        assertFalse(crossing.contains(0.5, 179.8), "179.8E is outside");
        assertFalse(crossing.contains(0.5, -179.8), "179.8W is outside");
    }

    @Test
    void polygonLimitsFollowTs23032AndPoleAmbiguityIsRefused() {
        assertEquals("GEOFENCE_POLYGON_POINTS",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.polygon(List.of(
                                new GeoPoint(0, 0), new GeoPoint(1, 1)))).code());

        List<GeoPoint> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(new GeoPoint(0.001 * i, 32.0 + 0.001 * i));
        }
        assertEquals("GEOFENCE_POLYGON_POINTS",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.polygon(sixteen)).code());

        assertEquals("GEOFENCE_POLYGON_DUPLICATE",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.polygon(List.of(
                                new GeoPoint(0, 0), new GeoPoint(0, 0),
                                new GeoPoint(1, 1)))).code());

        assertEquals("GEOFENCE_NEAR_POLE",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.polygon(List.of(
                                new GeoPoint(89.9, 0), new GeoPoint(89.6, 1),
                                new GeoPoint(89.0, -1)))).code());

        assertEquals("GEOFENCE_POLYGON_DEGENERATE",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.polygon(List.of(
                                new GeoPoint(0, 0), new GeoPoint(0, 1),
                                new GeoPoint(0, 2)))).code());
    }

    @Test
    void ellipseFollowsItsOrientation() {
        GeofenceGeometry northSouth = GeofenceGeometry.ellipse(
                KAMPALA_LAT, KAMPALA_LON, 1000, 500, 0);
        assertTrue(northSouth.contains(KAMPALA_LAT + 0.005, KAMPALA_LON),
                "556 m north lies on the major axis");
        assertFalse(northSouth.contains(KAMPALA_LAT, KAMPALA_LON + 0.01),
                "1.1 km east exceeds the 500 m minor axis");

        GeofenceGeometry eastWest = GeofenceGeometry.ellipse(
                KAMPALA_LAT, KAMPALA_LON, 1000, 500, 90);
        assertTrue(eastWest.contains(KAMPALA_LAT, KAMPALA_LON + 0.006),
                "667 m east lies on the rotated major axis");
        assertFalse(eastWest.contains(KAMPALA_LAT + 0.008, KAMPALA_LON),
                "890 m north exceeds the rotated minor axis");

        assertEquals("GEOFENCE_ELLIPSE_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.ellipse(KAMPALA_LAT, KAMPALA_LON, 500, 600, 0))
                        .code());
    }

    @Test
    void arcCoversItsBearingSectorAndInnerVoid() {
        GeofenceGeometry quadrant = GeofenceGeometry.arc(
                KAMPALA_LAT, KAMPALA_LON, 1000, 0, 0, 90);

        assertTrue(quadrant.contains(KAMPALA_LAT + 0.004, KAMPALA_LON), "north is in the sector");
        assertTrue(quadrant.contains(KAMPALA_LAT, KAMPALA_LON + 0.0045), "east is in the sector");
        assertFalse(quadrant.contains(KAMPALA_LAT - 0.004, KAMPALA_LON), "south is outside");
        assertFalse(quadrant.contains(KAMPALA_LAT, KAMPALA_LON - 0.0045), "west is outside");
        assertFalse(quadrant.contains(KAMPALA_LAT + 0.02, KAMPALA_LON), "2.2 km is outside");

        GeofenceGeometry annulus = GeofenceGeometry.arc(
                KAMPALA_LAT, KAMPALA_LON, 1000, 200, 0, 360);
        assertFalse(annulus.contains(KAMPALA_LAT, KAMPALA_LON), "the inner void is excluded");

        assertEquals("GEOFENCE_ARC_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.arc(KAMPALA_LAT, KAMPALA_LON, 500, 500, 0, 90))
                        .code());
    }

    @Test
    void geometryRoundTripsThroughItsStoredJson() throws Exception {
        List<GeofenceGeometry> shapes = List.of(
                GeofenceGeometry.circle(KAMPALA_LAT, KAMPALA_LON, 750),
                GeofenceGeometry.polygon(List.of(new GeoPoint(0.3, 32.5),
                        new GeoPoint(0.3, 32.6), new GeoPoint(0.4, 32.55))),
                GeofenceGeometry.ellipse(KAMPALA_LAT, KAMPALA_LON, 1200, 600, 45),
                GeofenceGeometry.arc(KAMPALA_LAT, KAMPALA_LON, 900, 100, 30, 120));

        for (GeofenceGeometry shape : shapes) {
            JsonNode stored = MAPPER.valueToTree(shape.toJson());
            GeofenceGeometry read = GeofenceGeometry.parse(stored);
            assertEquals(shape.shapeName(), read.shapeName());
            assertEquals(shape.contains(KAMPALA_LAT + 0.002, KAMPALA_LON + 0.002),
                    read.contains(KAMPALA_LAT + 0.002, KAMPALA_LON + 0.002));
        }

        Map<String, Object> circle = shapes.get(0).toJson();
        assertEquals("CIRCLE", circle.get("shape"));
        assertEquals(750.0, (Double) circle.get("radiusM"), 0.0001);

        Map<String, Object> arc = shapes.get(3).toJson();
        assertEquals(30.0, (Double) arc.get("startAngleDeg"), 0.0001);
    }

    @Test
    void anArcIsParsedFromTheFieldNameTheConsoleSends() throws Exception {
        GeofenceGeometry arc = GeofenceGeometry.parse(MAPPER.readTree(
                "{\"shape\":\"ARC\",\"latitude\":0.3476,\"longitude\":32.5825,"
                        + "\"outerRadiusM\":2000,\"innerRadiusM\":500,"
                        + "\"startAngleDeg\":0,\"includedAngleDeg\":90}"));
        assertEquals("ARC", arc.shapeName());
        assertEquals(2000.0, arc.radiusM(), 0.0001);
        assertEquals(500.0, arc.innerRadiusM(), 0.0001);
    }

    @Test
    void aParseRefusesUnknownShapesAndImpossibleCoordinates() throws Exception {
        assertEquals("GEOFENCE_SHAPE_UNSUPPORTED",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.parse(MAPPER.readTree(
                                "{\"shape\":\"HEXAGON\"}"))).code());
        assertEquals("GEOFENCE_SHAPE_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> GeofenceGeometry.parse(MAPPER.readTree(
                                "{\"shape\":\"CIRCLE\",\"latitude\":0,\"longitude\":0}"))).code());
        assertEquals("GEOFENCE_POINT_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> new GeoPoint(91, 0)).code());
        assertEquals("GEOFENCE_POINT_INVALID",
                assertThrows(GeofenceShapeException.class,
                        () -> new GeoPoint(0, 181)).code());
    }
}
