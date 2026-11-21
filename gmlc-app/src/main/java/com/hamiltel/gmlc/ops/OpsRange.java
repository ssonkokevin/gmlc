package com.hamiltel.gmlc.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A closed-open reporting window and the width of one point on its chart.
 * Windows are fixed rather than free-form so the dashboard's 1h/24h/7d/30d
 * chips mean the same thing to every reader, and buckets are always a whole
 * divisor of the window so the last point is not a stub.
 *
 * <p>Bucket timestamps are produced by the database ({@code date_bin}) from the
 * stored timestamps; nothing here invents a point for an interval with no
 * requests.
 */
public final class OpsRange {

    /** A window may not ask the database for more than this many buckets. */
    static final int MAX_BUCKETS = 1000;

    private static final Map<String, Duration> WINDOWS = new LinkedHashMap<>();

    static {
        WINDOWS.put("1h", Duration.ofHours(1));
        WINDOWS.put("24h", Duration.ofHours(24));
        WINDOWS.put("7d", Duration.ofDays(7));
        WINDOWS.put("30d", Duration.ofDays(30));
    }

    private static final Map<String, Duration> BUCKETS = new LinkedHashMap<>();

    static {
        BUCKETS.put("1m", Duration.ofMinutes(1));
        BUCKETS.put("5m", Duration.ofMinutes(5));
        BUCKETS.put("15m", Duration.ofMinutes(15));
        BUCKETS.put("30m", Duration.ofMinutes(30));
        BUCKETS.put("1h", Duration.ofHours(1));
        BUCKETS.put("3h", Duration.ofHours(3));
        BUCKETS.put("6h", Duration.ofHours(6));
        BUCKETS.put("12h", Duration.ofHours(12));
        BUCKETS.put("1d", Duration.ofDays(1));
    }

    private final String key;
    private final Instant from;
    private final Instant to;
    private final Duration bucket;

    private OpsRange(String key, Instant from, Instant to, Duration bucket) {
        this.key = key;
        this.from = from;
        this.to = to;
        this.bucket = bucket;
    }

    /**
     * @param range  one of 1h, 24h, 7d, 30d; empty means 24h
     * @param bucket optional override, one of 1m..1d
     */
    public static OpsRange parse(String range, String bucket) {
        String rangeKey = normalise(range, "24h");
        Duration span = WINDOWS.get(rangeKey);
        if (span == null) {
            throw new OpsBadRequestException("RANGE_NOT_SUPPORTED",
                    "range must be one of " + WINDOWS.keySet());
        }

        String bucketKey = normalise(bucket, defaultBucketKey(span));
        Duration width = BUCKETS.get(bucketKey);
        if (width == null) {
            throw new OpsBadRequestException("BUCKET_NOT_SUPPORTED",
                    "bucket must be one of " + BUCKETS.keySet());
        }
        if (width.compareTo(span) > 0) {
            throw new OpsBadRequestException("BUCKET_LARGER_THAN_RANGE",
                    "bucket " + bucketKey + " is wider than range " + rangeKey);
        }
        if (span.dividedBy(width) > MAX_BUCKETS) {
            throw new OpsBadRequestException("BUCKET_TOO_SMALL",
                    "bucket " + bucketKey + " would produce more than " + MAX_BUCKETS
                            + " points for range " + rangeKey);
        }

        Instant to = Instant.now();
        return new OpsRange(rangeKey, to.minus(span), to, width);
    }

    /** The same window immediately before this one, for deltas. */
    public OpsRange previous() {
        return new OpsRange(key, from.minus(Duration.between(from, to)), from, bucket);
    }

    public String key() {
        return key;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }

    public Duration bucket() {
        return bucket;
    }

    /** {@code date_bin} interval literal, e.g. {@code "300 seconds"}. */
    public String bucketInterval() {
        return bucket.getSeconds() + " seconds";
    }

    private static String defaultBucketKey(Duration span) {
        if (span.equals(Duration.ofHours(1))) {
            return "5m";
        }
        if (span.equals(Duration.ofHours(24))) {
            return "1h";
        }
        if (span.equals(Duration.ofDays(7))) {
            return "6h";
        }
        return "1d";
    }

    private static String normalise(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
