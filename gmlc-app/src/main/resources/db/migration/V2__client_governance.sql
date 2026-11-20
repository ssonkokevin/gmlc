-- ============================================================================
-- Client governance, version 2
--
-- Fixed-window usage counters. The limits live on api_client; the counters
-- live here so a restart, a second instance or a crash does not hand a client
-- a fresh allowance. Windows are one second and one UTC day.
--
-- Rows are disposable: nothing reads a window older than yesterday, and the
-- gateway sweeps them hourly.
-- ============================================================================

CREATE TABLE client_usage (
    tenant_id    BIGINT NOT NULL REFERENCES tenant (tenant_id),
    client_id    UUID NOT NULL REFERENCES api_client (client_id),
    window_kind  VARCHAR(8) NOT NULL CHECK (window_kind IN ('SECOND', 'DAY')),
    window_start TIMESTAMPTZ NOT NULL,
    counter      INTEGER NOT NULL CHECK (counter >= 0),
    PRIMARY KEY (tenant_id, client_id, window_kind, window_start)
);

CREATE INDEX client_usage_sweep ON client_usage (window_start);
