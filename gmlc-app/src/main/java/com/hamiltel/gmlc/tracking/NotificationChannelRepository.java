package com.hamiltel.gmlc.tracking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notification channels. The view never selects the sealed secret; only the
 * delivery lookup does, and only to sign one message.
 */
@Repository
public class NotificationChannelRepository {

    private static final String VIEW = """
            SELECT c.channel_id, c.name, c.channel_type, c.url, c.enabled,
                   c.created_at, c.updated_at,
                   (SELECT count(*) FROM tracking_schedule s
                    WHERE s.channel_id = c.channel_id) AS schedules
            FROM notification_channel c
            """;

    private final JdbcTemplate jdbc;

    public NotificationChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID channelId, long tenantId, String name, String type, String url,
                       String sealedSecret, boolean enabled, UUID createdBy) {
        jdbc.update("""
                INSERT INTO notification_channel (channel_id, tenant_id, name, channel_type, url,
                    secret_sealed, enabled, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, channelId, tenantId, name, type, url, sealedSecret, enabled, createdBy);
    }

    public List<ChannelView> list(long tenantId) {
        return jdbc.query(VIEW + " WHERE c.tenant_id = ? ORDER BY c.name",
                VIEW_MAPPER, tenantId);
    }

    public Optional<ChannelView> find(long tenantId, UUID channelId) {
        return jdbc.query(VIEW + " WHERE c.tenant_id = ? AND c.channel_id = ?",
                VIEW_MAPPER, tenantId, channelId).stream().findFirst();
    }

    public long count(long tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_channel"
                + " WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public boolean existsName(long tenantId, String name) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_channel"
                + " WHERE tenant_id = ? AND lower(name) = lower(?)", Long.class, tenantId, name);
        return count != null && count > 0;
    }

    public boolean setEnabled(long tenantId, UUID channelId, boolean enabled) {
        return jdbc.update("""
                UPDATE notification_channel SET enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND channel_id = ?
                """, enabled, tenantId, channelId) == 1;
    }

    public boolean delete(long tenantId, UUID channelId) {
        return jdbc.update("DELETE FROM notification_channel"
                + " WHERE tenant_id = ? AND channel_id = ?", tenantId, channelId) == 1;
    }

    /** Delivery lookup: the only read that carries the sealed secret. */
    public Optional<DeliveryChannel> findForDelivery(long tenantId, UUID channelId) {
        return jdbc.query("""
                SELECT channel_id, name, channel_type, url, secret_sealed, enabled
                FROM notification_channel
                WHERE tenant_id = ? AND channel_id = ?
                """, DELIVERY_MAPPER, tenantId, channelId).stream().findFirst();
    }

    private static final RowMapper<ChannelView> VIEW_MAPPER = (rs, rowNum) -> new ChannelView(
            rs.getObject("channel_id", UUID.class),
            rs.getString("name"),
            rs.getString("channel_type"),
            rs.getString("url"),
            rs.getBoolean("enabled"),
            rs.getLong("schedules"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<DeliveryChannel> DELIVERY_MAPPER = (rs, rowNum) ->
            new DeliveryChannel(
                    rs.getObject("channel_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("channel_type"),
                    rs.getString("url"),
                    rs.getString("secret_sealed"),
                    rs.getBoolean("enabled"));

    public record ChannelView(UUID channelId, String name, String type, String url,
                              boolean enabled, long schedules, Instant createdAt,
                              Instant updatedAt) {
    }

    public record DeliveryChannel(UUID channelId, String name, String type, String url,
                                  String sealedSecret, boolean enabled) {
    }
}
