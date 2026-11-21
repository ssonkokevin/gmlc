package com.hamiltel.gmlc.ops;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the gateway has actually observed on each signalling face. A transport
 * wrapper records one outcome per exchange; nothing here probes a peer or
 * guesses that silence means health. That is why a face with no traffic
 * reports IDLE rather than UP.
 */
@Component
public class PeerHealth {

    public static final String DIAMETER = "DIAMETER";
    public static final String M3UA = "M3UA";

    private final Map<String, Counter> faces = new ConcurrentHashMap<>();

    public void success(String face) {
        Counter counter = counter(face);
        synchronized (counter) {
            counter.attempts++;
            counter.successes++;
            counter.consecutiveFailures = 0;
            counter.lastAttemptAt = Instant.now();
            counter.lastSuccessAt = counter.lastAttemptAt;
            counter.lastCode = null;
        }
    }

    public void failure(String face, String code) {
        Counter counter = counter(face);
        synchronized (counter) {
            counter.attempts++;
            counter.failures++;
            counter.consecutiveFailures++;
            counter.lastAttemptAt = Instant.now();
            counter.lastFailureAt = counter.lastAttemptAt;
            counter.lastCode = code;
        }
    }

    public Snapshot snapshot(String face) {
        Counter counter = faces.get(face);
        if (counter == null) {
            return new Snapshot(face, 0, 0, 0, 0, null, null, null, null);
        }
        synchronized (counter) {
            return new Snapshot(face, counter.attempts, counter.successes, counter.failures,
                    counter.consecutiveFailures, counter.lastAttemptAt, counter.lastSuccessAt,
                    counter.lastFailureAt, counter.lastCode);
        }
    }

    private Counter counter(String face) {
        return faces.computeIfAbsent(face, key -> new Counter());
    }

    private static final class Counter {
        long attempts;
        long successes;
        long failures;
        int consecutiveFailures;
        Instant lastAttemptAt;
        Instant lastSuccessAt;
        Instant lastFailureAt;
        String lastCode;
    }

    public record Snapshot(String face, long attempts, long successes, long failures,
                           int consecutiveFailures, Instant lastAttemptAt, Instant lastSuccessAt,
                           Instant lastFailureAt, String lastCode) {

        public boolean failedLast() {
            return lastAttemptAt != null && consecutiveFailures > 0;
        }
    }
}
