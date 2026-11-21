package com.hamiltel.gmlc.tracking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Schedules, the runs they produce and the join a run needs: the subject's
 * identifier (server-side only), the client that owns it and the channel that
 * may receive a notification.
 */
@Repository
public class TrackingScheduleRepository {

    private static final String VIEW = """
            SELECT s.schedule_id, s.tracked_id, s.tenant_id, t.client_id,
                   t.subject_label, t.purpose, c.name AS client_name,
                   s.interval_seconds, s.starts_at, s.ends_at, s.accuracy_m, s.notify,
                   s.channel_id, ch.name AS channel_name, s.enabled, s.next_run_at,
                   s.last_run_at, s.last_state, s.last_error, s.created_at, s.updated_at,
                   (SELECT count(*) FROM tracking_run r
                    WHERE r.schedule_id = s.schedule_id) AS runs
            FROM tracking_schedule s
            JOIN tracked_subject t ON t.tracked_id = s.tracked_id
            JOIN api_client c ON c.client_id = t.client_id
            LEFT JOIN notification_channel ch ON ch.channel_id = s.channel_id
            """;

    private static final String RUN_TARGET = """
            SELECT s.schedule_id, s.tenant_id, s.tracked_id, t.client_id, t.subject_ref,
                   t.subject_label, t.subject_msisdn, t.subject_imsi, t.purpose, s.accuracy_m,
                   s.notify, s.channel_id, s.interval_seconds, c.enabled AS client_enabled
            FROM tracking_schedule s
            JOIN tracked_subject t ON t.tracked_id = s.tracked_id
            JOIN api_client c ON c.client_id = t.client_id
            """;

    private final JdbcTemplate jdbc;

    public TrackingScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the subject already has a schedule */
    public boolean insert(UUID scheduleId, long tenantId, UUID trackedId, int intervalSeconds,
                          Instant startsAt, Instant endsAt, Integer accuracyM, boolean notify,
                          UUID channelId, boolean enabled, Instant nextRunAt, UUID createdBy) {
        return jdbc.update("""
                INSERT INTO tracking_schedule (schedule_id, tenant_id, tracked_id,
                    interval_seconds, starts_at, ends_at, accuracy_m, notify, channel_id,
                    enabled, next_run_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, tracked_id) DO NOTHING
                """, scheduleId, tenantId, trackedId, intervalSeconds, Timestamp.from(startsAt),
                endsAt == null ? null : Timestamp.from(endsAt), accuracyM, notify, channelId,
                enabled, Timestamp.from(nextRunAt), createdBy) == 1;
    }

    public List<ScheduleView> list(long tenantId) {
        return jdbc.query(VIEW + " WHERE s.tenant_id = ? ORDER BY t.subject_label",
                VIEW_MAPPER, tenantId);
    }

    public Optional<ScheduleView> find(long tenantId, UUID scheduleId) {
        return jdbc.query(VIEW + " WHERE s.tenant_id = ? AND s.schedule_id = ?",
                VIEW_MAPPER, tenantId, scheduleId).stream().findFirst();
    }

    public Optional<RunTarget> findForRun(long tenantId, UUID scheduleId) {
        return jdbc.query(RUN_TARGET + " WHERE s.tenant_id = ? AND s.schedule_id = ?",
                TARGET_MAPPER, tenantId, scheduleId).stream().findFirst();
    }

    /** Enabled schedules whose window is open and whose next run is due. */
    public List<RunTarget> due(Instant now, int limit) {
        return jdbc.query(RUN_TARGET + """
                WHERE s.enabled AND t.enabled AND s.next_run_at <= ?
                  AND (s.ends_at IS NULL OR s.ends_at > ?)
                ORDER BY s.next_run_at
                LIMIT ?
                """, TARGET_MAPPER, Timestamp.from(now), Timestamp.from(now), limit);
    }

    public boolean setEnabled(long tenantId, UUID scheduleId, boolean enabled, Instant nextRunAt) {
        return jdbc.update("""
                UPDATE tracking_schedule SET enabled = ?, updated_at = now(),
                    next_run_at = COALESCE(?, next_run_at)
                WHERE tenant_id = ? AND schedule_id = ?
                """, enabled, nextRunAt == null ? null : Timestamp.from(nextRunAt),
                tenantId, scheduleId) == 1;
    }

    /** A null {@code nextRunAt} leaves the automatic timer untouched (manual run). */
    public void markRun(UUID scheduleId, Instant lastRunAt, String state, String error,
                        Instant nextRunAt) {
        jdbc.update("""
                UPDATE tracking_schedule SET last_run_at = ?, last_state = ?, last_error = ?,
                    next_run_at = COALESCE(?, next_run_at), updated_at = now()
                WHERE schedule_id = ?
                """, Timestamp.from(lastRunAt), state, error,
                nextRunAt == null ? null : Timestamp.from(nextRunAt), scheduleId);
    }

    public boolean delete(long tenantId, UUID scheduleId) {
        return jdbc.update("DELETE FROM tracking_schedule"
                + " WHERE tenant_id = ? AND schedule_id = ?", tenantId, scheduleId) == 1;
    }

    public long insertRun(long tenantId, UUID scheduleId, String trigger, String state,
                          UUID requestId, String errorCode, String notifyState,
                          String notifyDetail) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO tracking_run (tenant_id, schedule_id, trigger_kind, state,
                    request_id, error_code, notify_state, notify_detail, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                RETURNING run_id
                """, Long.class, tenantId, scheduleId, trigger, state, requestId, errorCode,
                notifyState, notifyDetail);
        return runId == null ? 0 : runId;
    }

    public List<RunView> runs(long tenantId, UUID scheduleId, int limit) {
        return jdbc.query("""
                SELECT run_id, schedule_id, trigger_kind, state, request_id, error_code,
                       notify_state, notify_detail, occurred_at
                FROM tracking_run
                WHERE tenant_id = ? AND schedule_id = ?
                ORDER BY occurred_at DESC, run_id DESC
                LIMIT ?
                """, RUN_MAPPER, tenantId, scheduleId, limit);
    }

    public Stats stats(long tenantId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM tracking_schedule
                        WHERE tenant_id = ?) AS schedules,
                       (SELECT count(*) FROM tracking_schedule
                        WHERE tenant_id = ? AND enabled) AS enabled,
                       (SELECT count(*) FROM tracking_run
                        WHERE tenant_id = ?) AS runs
                """, (rs, rowNum) -> new Stats(rs.getLong("schedules"), rs.getLong("enabled"),
                        rs.getLong("runs")), tenantId, tenantId, tenantId);
    }

    private static final RowMapper<ScheduleView> VIEW_MAPPER = (rs, rowNum) -> new ScheduleView(
            rs.getObject("schedule_id", UUID.class),
            rs.getObject("tracked_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getObject("client_id", UUID.class),
            rs.getString("client_name"),
            rs.getString("subject_label"),
            rs.getString("purpose"),
            rs.getInt("interval_seconds"),
            rs.getTimestamp("starts_at").toInstant(),
            timestamp(rs.getTimestamp("ends_at")),
            rs.getObject("accuracy_m", Integer.class),
            rs.getBoolean("notify"),
            rs.getObject("channel_id", UUID.class),
            rs.getString("channel_name"),
            rs.getBoolean("enabled"),
            rs.getTimestamp("next_run_at").toInstant(),
            timestamp(rs.getTimestamp("last_run_at")),
            rs.getString("last_state"),
            rs.getString("last_error"),
            rs.getLong("runs"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<RunTarget> TARGET_MAPPER = (rs, rowNum) -> new RunTarget(
            rs.getObject("schedule_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getObject("tracked_id", UUID.class),
            rs.getObject("client_id", UUID.class),
            rs.getString("subject_ref"),
            rs.getString("subject_label"),
            rs.getString("subject_msisdn"),
            rs.getString("subject_imsi"),
            rs.getString("purpose"),
            rs.getObject("accuracy_m", Integer.class),
            rs.getBoolean("notify"),
            rs.getObject("channel_id", UUID.class),
            rs.getInt("interval_seconds"),
            rs.getBoolean("client_enabled"));

    private static final RowMapper<RunView> RUN_MAPPER = (rs, rowNum) -> new RunView(
            rs.getLong("run_id"),
            rs.getObject("schedule_id", UUID.class),
            rs.getString("trigger_kind"),
            rs.getString("state"),
            rs.getObject("request_id", UUID.class),
            rs.getString("error_code"),
            rs.getString("notify_state"),
            rs.getString("notify_detail"),
            rs.getTimestamp("occurred_at").toInstant());

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ScheduleView(UUID scheduleId, UUID trackedId, long tenantId, UUID clientId,
                               String clientName, String subjectLabel, String purpose,
                               int intervalSeconds, Instant startsAt, Instant endsAt,
                               Integer accuracyM, boolean notifyOnFix, UUID channelId,
                               String channelName, boolean enabled, Instant nextRunAt,
                               Instant lastRunAt, String lastState, String lastError, long runs,
                               Instant createdAt, Instant updatedAt) {
    }

    public record RunTarget(UUID scheduleId, long tenantId, UUID trackedId, UUID clientId,
                            String subjectRef, String subjectLabel, String msisdn, String imsi,
                            String purpose, Integer accuracyM, boolean notifyOnFix,
                            UUID channelId,
                            int intervalSeconds, boolean clientEnabled) {
    }

    public record RunView(long runId, UUID scheduleId, String trigger, String state,
                          UUID requestId, String errorCode, String notifyState,
                          String notifyDetail, Instant occurredAt) {
    }

    public record Stats(long schedules, long enabled, long runs) {
    }
}
