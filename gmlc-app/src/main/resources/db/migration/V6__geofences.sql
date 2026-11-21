-- ============================================================================
-- Geofences, version 6
--
-- A geofence is a tenant-owned shape with one of the four TS 23.032 area
-- shapes the console can define: circle, polygon of 3 to 15 points, ellipse
-- and ellipsoid arc. The geometry is stored canonically as JSON so the exact
-- parameters the operator entered remain readable.
--
-- Arming binds a pseudonymous subject reference (never an MSISDN or IMSI) to a
-- geofence. Events are derived only from location results the gateway already
-- stored after a governed request: this deployment has no network-initiated
-- deferred, periodic or motion reporting, so no event here claims one.
--
-- A subject label is a masked MSISDN/IMSI kept for operators to recognise an
-- arm; the matching and the foreign key use the SHA-256/HMAC reference.
-- ============================================================================

CREATE TABLE geofence (
    geofence_id   UUID PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenant (tenant_id),
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    group_name    VARCHAR(64),
    shape         VARCHAR(16) NOT NULL
                  CHECK (shape IN ('CIRCLE', 'POLYGON', 'ELLIPSE', 'ARC')),
    geometry      JSONB NOT NULL,
    report_inside BOOLEAN NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by    UUID REFERENCES ops_user (user_id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX geofence_tenant ON geofence (tenant_id, enabled, group_name);

CREATE TABLE geofence_arm (
    geofence_id       UUID NOT NULL REFERENCES geofence (geofence_id) ON DELETE CASCADE,
    tenant_id         BIGINT NOT NULL REFERENCES tenant (tenant_id),
    subject_ref       CHAR(64) NOT NULL,
    subject_label     VARCHAR(24) NOT NULL,
    state             VARCHAR(8) CHECK (state IN ('INSIDE', 'OUTSIDE')),
    armed_by          UUID REFERENCES ops_user (user_id),
    armed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_evaluated_at TIMESTAMPTZ,
    last_result_id    UUID,
    PRIMARY KEY (geofence_id, subject_ref)
);

CREATE INDEX geofence_arm_subject ON geofence_arm (tenant_id, subject_ref);
CREATE INDEX geofence_arm_state ON geofence_arm (tenant_id, state);

CREATE TABLE geofence_event (
    event_id      BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenant (tenant_id),
    geofence_id   UUID NOT NULL REFERENCES geofence (geofence_id) ON DELETE CASCADE,
    subject_ref   CHAR(64) NOT NULL,
    subject_label VARCHAR(24) NOT NULL,
    event_type    VARCHAR(8) NOT NULL
                  CHECK (event_type IN ('ENTER', 'LEAVE', 'INSIDE')),
    occurred_at   TIMESTAMPTZ NOT NULL,
    result_id     UUID NOT NULL,
    request_id    UUID NOT NULL,
    latitude      NUMERIC(9, 6) NOT NULL,
    longitude     NUMERIC(9, 6) NOT NULL,
    uncertainty_m INTEGER NOT NULL,
    method        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (geofence_id, subject_ref, result_id, event_type)
);

CREATE INDEX geofence_event_range ON geofence_event (tenant_id, occurred_at DESC);
CREATE INDEX geofence_event_type ON geofence_event (tenant_id, event_type, occurred_at DESC);
CREATE INDEX geofence_event_geofence ON geofence_event (geofence_id, occurred_at DESC);
