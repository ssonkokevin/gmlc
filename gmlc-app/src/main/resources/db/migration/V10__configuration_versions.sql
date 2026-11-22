-- ============================================================================
-- Versioned configuration, version 10
--
-- A configuration item has one live value and a version. Every change is a
-- row in config_change carrying the value it replaces, the value proposed, the
-- reason and who asked. In a production deployment a change that affects
-- signalling waits as PENDING until a second operator with MANAGE_CONFIG
-- approves it; the same person can never approve their own request. In a lab
-- the change applies immediately but the row is still written, so the trail is
-- the same shape either way.
--
-- Alerts thresholds and the LCS client name are the items the gateway reads
-- at run time today. Anything else the console shows (peers, retention,
-- licence) is environment-managed and deliberately absent from this table:
-- a row here would claim an effect the process does not honour.
-- ============================================================================

CREATE TABLE config_item (
    tenant_id       BIGINT NOT NULL REFERENCES tenant (tenant_id),
    item_key        VARCHAR(64) NOT NULL,
    value           JSONB NOT NULL,
    version         INTEGER NOT NULL CHECK (version >= 1),
    updated_by      UUID NOT NULL REFERENCES ops_user (user_id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, item_key)
);

CREATE TABLE config_change (
    change_id           UUID PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenant (tenant_id),
    item_key            VARCHAR(64) NOT NULL,
    base_version        INTEGER NOT NULL CHECK (base_version >= 0),
    previous_value      JSONB,
    requested_value     JSONB NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    status              VARCHAR(16) NOT NULL
                        CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'CANCELLED')),
    requires_approval   BOOLEAN NOT NULL,
    requested_by        UUID NOT NULL REFERENCES ops_user (user_id),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by          UUID REFERENCES ops_user (user_id),
    decided_at          TIMESTAMPTZ,
    decision_note       VARCHAR(500),
    applied_version     INTEGER,
    applied_at          TIMESTAMPTZ,
    CONSTRAINT config_change_reason_present
        CHECK (length(btrim(reason)) >= 10),
    CONSTRAINT config_change_second_approver
        CHECK (status <> 'APPLIED' OR NOT requires_approval
               OR decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT config_change_decided_pair
        CHECK ((decided_by IS NULL) = (decided_at IS NULL)),
    CONSTRAINT config_change_applied_pair
        CHECK ((applied_version IS NULL) = (applied_at IS NULL))
);

-- One open request per item, so two operators cannot both wait on the same
-- base version and apply out of order.
CREATE UNIQUE INDEX config_change_one_pending
    ON config_change (tenant_id, item_key) WHERE status = 'PENDING';

CREATE INDEX config_change_time ON config_change (tenant_id, requested_at DESC);
