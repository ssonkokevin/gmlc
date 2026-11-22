package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The audit log as an administrator reads it. Rows name the operator or the
 * client that acted; a row with neither is a system decision such as a refused
 * unknown-user sign-in. The detail document is returned as stored - by design
 * it holds no position and no raw subscriber identifier.
 */
@Repository
public class OpsAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(OpsAuditRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OpsAuditRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<AuditRow> list(long tenantId, String action, String outcome, String actor,
                               Instant from, Instant to, Long beforeAuditId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.audit_id, a.occurred_at, a.action, a.outcome, a.request_id,
                       a.subject_ref, a.detail::text AS detail_text,
                       u.username, u.display_name, c.name AS client_name
                  FROM audit_event a
                  LEFT JOIN ops_user u ON u.user_id = a.actor_user_id
                  LEFT JOIN api_client c ON c.client_id = a.actor_client_id
                 WHERE a.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (action != null) {
            sql.append(" AND a.action = ?");
            args.add(action);
        }
        if (outcome != null) {
            sql.append(" AND a.outcome = ?");
            args.add(outcome);
        }
        if (actor != null) {
            sql.append(" AND (lower(u.username) = lower(?) OR lower(c.name) = lower(?))");
            args.add(actor);
            args.add(actor);
        }
        if (from != null) {
            sql.append(" AND a.occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND a.occurred_at < ?");
            args.add(Timestamp.from(to));
        }
        if (beforeAuditId != null) {
            sql.append(" AND a.audit_id < ?");
            args.add(beforeAuditId);
        }
        sql.append(" ORDER BY a.audit_id DESC LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    private AuditRow map(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("username");
        String clientName = rs.getString("client_name");
        String actorType = username != null ? "OPERATOR" : clientName != null ? "CLIENT" : "SYSTEM";
        JsonNode detail = parse(rs.getString("detail_text"));
        String remoteAddr = detail == null ? null : detail.path("remoteAddr").asText(null);
        return new AuditRow(
                rs.getLong("audit_id"),
                instant(rs, "occurred_at"),
                rs.getString("action"),
                rs.getString("outcome"),
                rs.getObject("request_id", UUID.class),
                rs.getString("subject_ref"),
                actorType,
                username,
                rs.getString("display_name"),
                clientName,
                remoteAddr,
                detail);
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("audit detail is not valid JSON: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record AuditRow(
            long auditId,
            Instant occurredAt,
            String action,
            String outcome,
            UUID requestId,
            String subjectRef,
            String actorType,
            String actorUsername,
            String actorDisplayName,
            String clientName,
            String remoteAddr,
            JsonNode detail) {
    }
}
