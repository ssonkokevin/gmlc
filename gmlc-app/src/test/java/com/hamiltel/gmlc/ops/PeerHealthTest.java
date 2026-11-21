package com.hamiltel.gmlc.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The peer panel's only evidence is an exchange that was attempted. These tests
 * pin that: silence is IDLE, a failure is not forgotten by the next success,
 * and a successful exchange clears the streak.
 */
class PeerHealthTest {

    private final PeerHealth health = new PeerHealth();

    @Test
    void aFaceWithoutTrafficIsIdle() {
        PeerHealth.Snapshot snapshot = health.snapshot(PeerHealth.DIAMETER);

        assertEquals(0, snapshot.attempts());
        assertNull(snapshot.lastAttemptAt());
        assertFalse(snapshot.failedLast(), "silence must not be read as health");
    }

    @Test
    void aSuccessfulExchangeIsRecorded() {
        health.success(PeerHealth.M3UA);
        PeerHealth.Snapshot snapshot = health.snapshot(PeerHealth.M3UA);

        assertEquals(1, snapshot.attempts());
        assertEquals(1, snapshot.successes());
        assertEquals(0, snapshot.failures());
        assertEquals(0, snapshot.consecutiveFailures());
        assertNotNull(snapshot.lastSuccessAt());
        assertFalse(snapshot.failedLast());
    }

    @Test
    void consecutiveFailuresAreCountedAndAClearanceResetsThem() {
        health.failure(PeerHealth.DIAMETER, "DIAMETER_TIMEOUT");
        health.failure(PeerHealth.DIAMETER, "DIAMETER_TIMEOUT");
        health.failure(PeerHealth.DIAMETER, "DIAMETER_IO");

        PeerHealth.Snapshot down = health.snapshot(PeerHealth.DIAMETER);
        assertEquals(3, down.attempts());
        assertEquals(3, down.failures());
        assertEquals(3, down.consecutiveFailures());
        assertEquals("DIAMETER_IO", down.lastCode());
        assertTrue(down.failedLast());
        assertNotNull(down.lastFailureAt());

        health.success(PeerHealth.DIAMETER);
        PeerHealth.Snapshot up = health.snapshot(PeerHealth.DIAMETER);
        assertEquals(4, up.attempts());
        assertEquals(0, up.consecutiveFailures());
        assertNull(up.lastCode(), "a success means there is no current failure code");
        assertFalse(up.failedLast());
    }

    @Test
    void unknownFacesSnapshotEmptyRatherThanThrow() {
        PeerHealth.Snapshot snapshot = health.snapshot("SMPP");

        assertEquals("SMPP", snapshot.face());
        assertEquals(0, snapshot.attempts());
    }
}
