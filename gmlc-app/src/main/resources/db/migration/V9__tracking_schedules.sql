-- ============================================================================
-- Automated tracking and notifier channels, version 9
--
-- A schedule re-runs a governed location request for one tracked subject at a
-- fixed interval inside a from/to window. It is not a deferred or periodic
-- location subscription: every run goes through the same consent, purpose and
-- accuracy checks as a client request, and a run that is refused is recorded
-- as such instead of being retried in a tight loop.
--
-- The minimum interval is enforced here and in the service. Licence metering
-- is not wired in this deployment, so the floor is a conservative paging
-- guard rather than a purchased figure; the console says so.
--
-- A channel stores where a notification goes and the signing secret used to
-- sign it. The secret is sealed with AES-GCM under a key supplied by the
-- deployment; no read endpoint returns it, and no log line carries it.
-- ============================================================================

CREATE TABLE notification_channel (
    channel_id     UUID PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenant (tenant_id),
    name           VARCHAR(128) NOT NULL,
    channel_type   VARCHAR(16) NOT NULL
                   CHECK (channel_type IN ('WEBHOOK', 'EMAIL', 'TELEGRAM', 'SLACK')),
    url            VARCHAR(1024) NOT NULL,
    secret_sealed  TEXT NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by     UUID REFERENCES ops_user (user_id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX notification_channel_tenant ON notification_channel (tenant_id, enabled);

CREATE TABLE tracking_schedule (
    schedule_id      UUID PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenant (tenant_id),
    tracked_id       UUID NOT NULL REFERENCES tracked_subject (tracked_id) ON DELETE CASCADE,
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds BETWEEN 300 AND 86400),
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ,
    accuracy_m       INTEGER CHECK (accuracy_m IS NULL OR accuracy_m BETWEEN 1 AND 100000),
    notify           BOOLEAN NOT NULL DEFAULT FALSE,
    channel_id       UUID REFERENCES notification_channel (channel_id) ON DELETE SET NULL,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at      TIMESTAMPTZ NOT NULL,
    last_run_at      TIMESTAMPTZ,
    last_state       VARCHAR(24),
    last_error       VARCHAR(64),
    created_by       UUID REFERENCES ops_user (user_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tracking_schedule_notify_channel
        CHECK (NOT notify OR channel_id IS NOT NULL),
    CONSTRAINT tracking_schedule_window
        CHECK (ends_at IS NULL OR ends_at > starts_at),
    UNIQUE (tenant_id, tracked_id)
);

CREATE INDEX tracking_schedule_due ON tracking_schedule (enabled, next_run_at);

-- One row per attempt. A run names the governed request it placed, the state
-- the request ended in and what happened to the notification. It is evidence,
-- so it is never rewritten after the attempt finishes.
CREATE TABLE tracking_run (
    run_id        BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenant (tenant_id),
    schedule_id   UUID NOT NULL REFERENCES tracking_schedule (schedule_id) ON DELETE CASCADE,
    trigger_kind  VARCHAR(16) NOT NULL CHECK (trigger_kind IN ('SCHEDULE', 'MANUAL')),
    state         VARCHAR(24) NOT NULL
                  CHECK (state IN ('LOCATED', 'NO_FIX', 'SKIPPED', 'FAILED')),
    request_id    UUID,
    error_code    VARCHAR(64),
    notify_state  VARCHAR(16) NOT NULL DEFAULT 'NONE'
                  CHECK (notify_state IN ('NONE', 'SENT', 'FAILED', 'NOT_CONFIGURED')),
    notify_detail VARCHAR(256),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);

CREATE INDEX tracking_run_schedule ON tracking_run (schedule_id, occurred_at DESC);
