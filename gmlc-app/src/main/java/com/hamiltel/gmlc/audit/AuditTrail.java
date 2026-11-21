package com.hamiltel.gmlc.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * The append-only trail. It records who asked, for whom (hashed elsewhere),
 * what happened and why. It never records a position: audit rows are read by
 * more people than results are, and a coordinate in an audit log is a location
 * leak.
 *
 * <p>An audit write must never turn a refusal into a 500, so failures are
 * logged and swallowed.
 */
@Service
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    private static final String INSERT = """
            INSERT INTO audit_event (tenant_id, actor_client_id, action, request_id, outcome, detail)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String INSERT_WITH_SUBJECT = """
            INSERT INTO audit_event (tenant_id, actor_client_id, action, subject_ref, request_id,
                outcome, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(long tenantId, UUID actorClientId, String action, UUID requestId,
                       Outcome outcome, Map<String, Object> detail) {
        write(null, tenantId, actorClientId, action, requestId, outcome, detail);
    }

    /**
     * For consent and privacy decisions: the trail names the subject by the
     * same pseudonym the consent row uses, never by MSISDN or IMSI.
     */
    public void recordForSubject(long tenantId, UUID actorClientId, String action, String subjectRef,
                                 UUID requestId, Outcome outcome, Map<String, Object> detail) {
        write(subjectRef, tenantId, actorClientId, action, requestId, outcome, detail);
    }

    private void write(String subjectRef, long tenantId, UUID actorClientId, String action,
                       UUID requestId, Outcome outcome, Map<String, Object> detail) {
        String json;
        try {
            json = detail == null || detail.isEmpty() ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("audit detail not serialisable for action {}", action);
            return;
        }
        try {
            if (subjectRef == null) {
                jdbc.update(INSERT, tenantId, actorClientId, action, requestId, outcome.name(), json);
            } else {
                jdbc.update(INSERT_WITH_SUBJECT, tenantId, actorClientId, action, subjectRef,
                        requestId, outcome.name(), json);
            }
        } catch (RuntimeException e) {
            log.warn("audit write failed for action {}: {}", action, e.getClass().getSimpleName());
        }
    }

    public enum Outcome {
        ALLOWED,
        DENIED,
        ERROR
    }
}
