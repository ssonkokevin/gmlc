-- ============================================================================
-- Operator alerts, version 5
--
-- The alert engine observes recorded traffic and peer outcomes and raises a
-- row per condition. A row is deduplicated while it is open, so one failure
-- storm is one alert rather than one per evaluation. Acknowledging records the
-- person; it does not hide the condition, which stays open until the engine
-- observes it cleared.
-- ============================================================================

CREATE TABLE ops_alert (
    alert_id        BIGSERIAL,
    tenant_id       BIGINT NOT NULL REFERENCES tenant (tenant_id),
    dedupe_key      VARCHAR(96) NOT NULL,
    kind            VARCHAR(32) NOT NULL
                    CHECK (kind IN ('PEER_DOWN', 'ERROR_RATE', 'LATENCY_SLO', 'CELL_DB_GAP')),
    severity        VARCHAR(8) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    summary         TEXT NOT NULL,
    detail          JSONB,
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by UUID,
    PRIMARY KEY (tenant_id, alert_id)
);

CREATE UNIQUE INDEX ops_alert_open_unique
    ON ops_alert (tenant_id, dedupe_key)
    WHERE closed_at IS NULL;

CREATE INDEX ops_alert_time ON ops_alert (tenant_id, closed_at, opened_at DESC);
