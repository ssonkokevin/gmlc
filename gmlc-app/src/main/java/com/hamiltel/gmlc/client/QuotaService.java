package com.hamiltel.gmlc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window quotas. Counters live in PostgreSQL so that a restart or a
 * second instance cannot hand out a fresh allowance; a per-second limit is a
 * burst guard, a per-day limit is the commercial one.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final long SWEEP_INTERVAL_MS = 3_600_000L;

    private static final String INCREMENT = """
            INSERT INTO client_usage (tenant_id, client_id, window_kind, window_start, counter)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT (tenant_id, client_id, window_kind, window_start)
            DO UPDATE SET counter = client_usage.counter + 1
            RETURNING counter
            """;

    private final JdbcTemplate jdbc;
    private final AtomicLong lastSweep = new AtomicLong();

    public QuotaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Decision consume(ApiClient client) {
        Instant now = Instant.now();

        int thisSecond = increment(client, "SECOND", now.truncatedTo(ChronoUnit.SECONDS));
        if (thisSecond > client.ratePerSecond()) {
            return Decision.denied("rate_per_second", 1);
        }

        Instant startOfDay = now.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        int today = increment(client, "DAY", startOfDay);
        if (today > client.ratePerDay()) {
            long untilMidnight = Duration.between(now, startOfDay.plus(Duration.ofDays(1))).toSeconds();
            return Decision.denied("rate_per_day", Math.max(1, untilMidnight));
        }

        sweepOccasionally(now);
        return Decision.permitted();
    }

    private int increment(ApiClient client, String windowKind, Instant windowStart) {
        Integer counter = jdbc.queryForObject(INCREMENT, Integer.class,
                client.tenantId(), client.clientId(), windowKind, Timestamp.from(windowStart));
        return counter == null ? Integer.MAX_VALUE : counter;
    }

    /** Usage rows are disposable; a failed sweep must never fail a request. */
    private void sweepOccasionally(Instant now) {
        long previous = lastSweep.get();
        long current = now.toEpochMilli();
        if (current - previous < SWEEP_INTERVAL_MS || !lastSweep.compareAndSet(previous, current)) {
            return;
        }
        try {
            jdbc.update("DELETE FROM client_usage WHERE window_start < now() - interval '2 days'");
        } catch (DataAccessException e) {
            log.debug("usage sweep skipped: {}", e.getClass().getSimpleName());
        }
    }

    public record Decision(boolean allowed, String limit, long retryAfterSeconds) {

        static Decision permitted() {
            return new Decision(true, null, 0);
        }

        static Decision denied(String limit, long retryAfterSeconds) {
            return new Decision(false, limit, retryAfterSeconds);
        }
    }
}
