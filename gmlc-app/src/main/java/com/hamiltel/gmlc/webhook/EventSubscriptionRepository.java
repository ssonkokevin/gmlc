package com.hamiltel.gmlc.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriptions, their deliveries and the join needed to send one. A delivery
 * is enqueued in the same transaction as the event, so an event that exists
 * always has its matching delivery rows; the unique key keeps a repeated
 * enqueue from creating a second one.
 */
@Repository
public class EventSubscriptionRepository {

    private static final String VIEW = """
            SELECT s.subscription_id, s.name, s.url, s.event_types, s.geofence_id,
                   g.name AS geofence_name, s.enabled, s.created_at, s.updated_at,
                   (SELECT count(*) FROM webhook_delivery d
                    WHERE d.subscription_id = s.subscription_id AND d.status = 'PENDING')
                       AS pending,
                   (SELECT count(*) FROM webhook_delivery d
                    WHERE d.subscription_id = s.subscription_id AND d.status = 'DELIVERED')
                       AS delivered,
                   (SELECT count(*) FROM webhook_delivery d
                    WHERE d.subscription_id = s.subscription_id AND d.status = 'FAILED')
                       AS failed,
                   (SELECT max(d.delivered_at) FROM webhook_delivery d
                    WHERE d.subscription_id = s.subscription_id AND d.status = 'DELIVERED')
                       AS last_delivered_at
            FROM event_subscription s
            LEFT JOIN geofence g ON g.geofence_id = s.geofence_id
            """;

    private final JdbcTemplate jdbc;

    public EventSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long tenantId, UUID subscriptionId, String name, String url, String secret,
                       List<String> eventTypes, UUID geofenceId, boolean enabled,
                       UUID createdBy) {
        jdbc.update("""
                INSERT INTO event_subscription (subscription_id, tenant_id, name, url, secret,
                    event_types, geofence_id, enabled, created_by)
                VALUES (?, ?, ?, ?, ?, string_to_array(?, ','), ?, ?, ?)
                """,
                subscriptionId, tenantId, name, url, secret, String.join(",", eventTypes),
                geofenceId, enabled, createdBy);
    }

    public void update(long tenantId, UUID subscriptionId, String name, String url,
                       List<String> eventTypes, UUID geofenceId, boolean enabled) {
        jdbc.update("""
                UPDATE event_subscription
                SET name = ?, url = ?, event_types = string_to_array(?, ','),
                    geofence_id = ?, enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND subscription_id = ?
                """,
                name, url, String.join(",", eventTypes), geofenceId, enabled,
                tenantId, subscriptionId);
    }

    public boolean delete(long tenantId, UUID subscriptionId) {
        return jdbc.update("DELETE FROM event_subscription WHERE tenant_id = ?"
                + " AND subscription_id = ?", tenantId, subscriptionId) == 1;
    }

    public List<SubscriptionView> list(long tenantId) {
        return jdbc.query(VIEW + " WHERE s.tenant_id = ? ORDER BY s.name",
                SUBSCRIPTION_MAPPER, tenantId);
    }

    public Optional<SubscriptionView> find(long tenantId, UUID subscriptionId) {
        List<SubscriptionView> rows = jdbc.query(VIEW
                        + " WHERE s.tenant_id = ? AND s.subscription_id = ?",
                SUBSCRIPTION_MAPPER, tenantId, subscriptionId);
        return rows.stream().findFirst();
    }

    public boolean existsName(long tenantId, String name, UUID excludeId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM event_subscription"
                        + " WHERE tenant_id = ? AND lower(name) = lower(?)"
                        + " AND subscription_id <> ?",
                Long.class, tenantId, name, excludeId == null ? new UUID(0, 0) : excludeId);
        return count != null && count > 0;
    }

    /** Inserts one PENDING delivery per matching enabled subscription. */
    public int enqueue(long tenantId, long eventId) {
        return jdbc.update("""
                INSERT INTO webhook_delivery (tenant_id, subscription_id, event_id, status)
                SELECT ?, s.subscription_id, ?, 'PENDING'
                FROM event_subscription s
                JOIN geofence_event e ON e.event_id = ?
                WHERE s.tenant_id = ? AND s.enabled
                  AND (s.geofence_id IS NULL OR s.geofence_id = e.geofence_id)
                  AND e.event_type = ANY (s.event_types)
                ON CONFLICT (subscription_id, event_id) DO NOTHING
                """, tenantId, eventId, eventId, tenantId);
    }

    public List<DeliveryRow> dueDeliveries(Instant now, int limit) {
        return jdbc.query("""
                SELECT d.delivery_id, d.attempts, s.subscription_id, s.name AS subscription_name,
                       s.url, s.secret, e.event_id, e.event_type, e.occurred_at, e.geofence_id,
                       g.name AS geofence_name, e.subject_ref, e.subject_label, e.latitude,
                       e.longitude, e.uncertainty_m, e.method, e.result_id, e.request_id
                FROM webhook_delivery d
                JOIN event_subscription s ON s.subscription_id = d.subscription_id
                JOIN geofence_event e ON e.event_id = d.event_id
                JOIN geofence g ON g.geofence_id = e.geofence_id
                WHERE d.status = 'PENDING' AND s.enabled AND d.next_attempt_at <= ?
                ORDER BY d.delivery_id
                LIMIT ?
                """, DELIVERY_MAPPER, Timestamp.from(now), limit);
    }

    public void markDelivered(long deliveryId, int statusCode) {
        jdbc.update("""
                UPDATE webhook_delivery
                SET status = 'DELIVERED', attempts = attempts + 1, delivered_at = now(),
                    last_status_code = ?, last_error = NULL
                WHERE delivery_id = ?
                """, statusCode, deliveryId);
    }

    public void markFailure(long deliveryId, boolean finalFailure, Instant nextAttemptAt,
                            Integer statusCode, String error) {
        jdbc.update("""
                UPDATE webhook_delivery
                SET status = ?, attempts = attempts + 1, next_attempt_at = ?,
                    last_status_code = ?, last_error = ?
                WHERE delivery_id = ?
                """, finalFailure ? "FAILED" : "PENDING",
                Timestamp.from(finalFailure ? Instant.now() : nextAttemptAt),
                statusCode, error, deliveryId);
    }

    public List<DeliveryView> deliveries(long tenantId, UUID subscriptionId, int limit) {
        return jdbc.query("""
                SELECT delivery_id, event_id, status, attempts, last_status_code, last_error,
                       created_at, next_attempt_at, delivered_at
                FROM webhook_delivery
                WHERE tenant_id = ? AND subscription_id = ?
                ORDER BY created_at DESC, delivery_id DESC
                LIMIT ?
                """, DELIVERY_VIEW_MAPPER, tenantId, subscriptionId, limit);
    }

    public long pendingCount(long tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery"
                + " WHERE tenant_id = ? AND status = 'PENDING'", Long.class, tenantId);
        return count == null ? 0 : count;
    }

    // --------------------------------------------------------------- mapping

    private static final RowMapper<SubscriptionView> SUBSCRIPTION_MAPPER = (rs, rowNum) ->
            new SubscriptionView(
                    rs.getObject("subscription_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("url"),
                    strings(rs.getArray("event_types")),
                    rs.getObject("geofence_id", UUID.class),
                    rs.getString("geofence_name"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("pending"),
                    rs.getLong("delivered"),
                    rs.getLong("failed"),
                    rs.getTimestamp("last_delivered_at") == null ? null
                            : rs.getTimestamp("last_delivered_at").toInstant());

    private static final RowMapper<DeliveryRow> DELIVERY_MAPPER = (rs, rowNum) ->
            new DeliveryRow(
                    rs.getLong("delivery_id"),
                    rs.getInt("attempts"),
                    rs.getObject("subscription_id", UUID.class),
                    rs.getString("subscription_name"),
                    rs.getString("url"),
                    rs.getString("secret"),
                    rs.getLong("event_id"),
                    rs.getString("event_type"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("geofence_id", UUID.class),
                    rs.getString("geofence_name"),
                    rs.getString("subject_ref"),
                    rs.getString("subject_label"),
                    rs.getBigDecimal("latitude"),
                    rs.getBigDecimal("longitude"),
                    rs.getInt("uncertainty_m"),
                    rs.getString("method"),
                    rs.getObject("result_id", UUID.class),
                    rs.getObject("request_id", UUID.class));

    private static final RowMapper<DeliveryView> DELIVERY_VIEW_MAPPER = (rs, rowNum) ->
            new DeliveryView(
                    rs.getLong("delivery_id"),
                    rs.getLong("event_id"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getObject("last_status_code", Integer.class),
                    rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getTimestamp("delivered_at") == null ? null
                            : rs.getTimestamp("delivered_at").toInstant());

    private static List<String> strings(Array array) {
        try {
            String[] values = (String[]) array.getArray();
            return List.of(values);
        } catch (SQLException e) {
            throw new IllegalStateException("event_types column is not a text array", e);
        }
    }

    // --------------------------------------------------------------- records

    public record SubscriptionView(UUID subscriptionId, String name, String url,
                                   List<String> eventTypes, UUID geofenceId, String geofenceName,
                                   boolean enabled, Instant createdAt, Instant updatedAt,
                                   long pending, long delivered, long failed,
                                   Instant lastDeliveredAt) {
    }

    public record DeliveryRow(long deliveryId, int attempts, UUID subscriptionId,
                              String subscriptionName, String url, String secret, long eventId,
                              String eventType, Instant occurredAt, UUID geofenceId,
                              String geofenceName, String subjectRef, String subjectLabel,
                              BigDecimal latitude, BigDecimal longitude, int uncertaintyM,
                              String method, UUID resultId, UUID requestId) {
    }

    public record DeliveryView(long deliveryId, long eventId, String status, int attempts,
                               Integer lastStatusCode, String lastError, Instant createdAt,
                               Instant nextAttemptAt, Instant deliveredAt) {
    }
}
