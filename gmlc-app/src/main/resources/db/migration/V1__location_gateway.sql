-- ============================================================================
-- GMLC schema, version 1
--
-- The gateway's own data: tenants, capability profiles, the client registry,
-- consent, the authoritative cell database, requests, results, CDRs and the
-- audit trail. Every table keys on tenant_id first (decision D11 in the core
-- repository's docs/location/DECISIONS.md).
--
-- Subscriber identifiers are stored only where signalling needs them
-- (location_request). Consent and audit rows carry a hashed reference, never
-- the MSISDN or IMSI itself, and no row here stores a position in an audit
-- record.
-- ============================================================================

CREATE TABLE tenant (
    tenant_id    BIGINT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenant (tenant_id, name) VALUES (1, 'Hamiltel');

-- ---------------------------------------------------------------------------
-- Capability profile: the full JSON document, validated before serving.
-- ---------------------------------------------------------------------------

CREATE TABLE capability_profile (
    tenant_id    BIGINT PRIMARY KEY REFERENCES tenant (tenant_id),
    version      VARCHAR(16) NOT NULL,
    profile      JSONB NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Client registry. key_hash is the hex SHA-256 of the API key; the key itself
-- is shown once at creation and never stored.
-- ---------------------------------------------------------------------------

CREATE TABLE api_client (
    client_id        UUID PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenant (tenant_id),
    name             VARCHAR(128) NOT NULL,
    key_hash         CHAR(64) NOT NULL UNIQUE,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    purposes         TEXT[] NOT NULL DEFAULT '{}',
    max_accuracy_m   INTEGER NOT NULL DEFAULT 5000 CHECK (max_accuracy_m > 0),
    rate_per_second  INTEGER NOT NULL DEFAULT 5 CHECK (rate_per_second > 0),
    rate_per_day     INTEGER NOT NULL DEFAULT 1000 CHECK (rate_per_day > 0),
    ip_allowlist     TEXT[],
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------------
-- Consent per subscriber (hashed), client, purpose and channel.
-- ---------------------------------------------------------------------------

CREATE TABLE consent (
    consent_id   UUID PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenant (tenant_id),
    subject_ref  CHAR(64) NOT NULL,
    client_id    UUID REFERENCES api_client (client_id),
    purpose      VARCHAR(64) NOT NULL,
    status       VARCHAR(16) NOT NULL
                 CHECK (status IN ('PENDING', 'GRANTED', 'REVOKED', 'EXPIRED')),
    channel      VARCHAR(16),
    granted_at   TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    evidence     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT consent_granted_has_time
        CHECK (status <> 'GRANTED' OR granted_at IS NOT NULL)
);

CREATE UNIQUE INDEX consent_active_unique
    ON consent (tenant_id, subject_ref, purpose,
                COALESCE(client_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status IN ('PENDING', 'GRANTED');

CREATE INDEX consent_subject ON consent (tenant_id, subject_ref, purpose);

-- ---------------------------------------------------------------------------
-- Authoritative cell database. Identity strings follow the core repository's
-- canonical form: mcc-mnc-lac-ci (GSM), mcc-mnc-lac-sac (UMTS),
-- mcc-mnc-eci (LTE), mcc-mnc-nci (NR).
-- ---------------------------------------------------------------------------

CREATE TABLE cell (
    tenant_id        BIGINT NOT NULL REFERENCES tenant (tenant_id),
    rat              VARCHAR(8) NOT NULL CHECK (rat IN ('GSM', 'UMTS', 'LTE', 'NR')),
    cell_identity    VARCHAR(32) NOT NULL,
    plmn_mcc         CHAR(3) NOT NULL,
    plmn_mnc         VARCHAR(3) NOT NULL,
    area_code        VARCHAR(16),
    cell_code        VARCHAR(16) NOT NULL,
    site_name        VARCHAR(128),
    latitude         NUMERIC(9, 7),
    longitude        NUMERIC(10, 7),
    antenna_height_m NUMERIC(6, 1),
    azimuth_deg      SMALLINT CHECK (azimuth_deg BETWEEN 0 AND 359),
    beamwidth_deg    SMALLINT CHECK (beamwidth_deg BETWEEN 0 AND 360),
    radius_m         INTEGER CHECK (radius_m > 0),
    coverage_polygon JSONB,
    pci              SMALLINT,
    arfcn            INTEGER,
    prs_config       JSONB,
    timing_advance   JSONB,
    cell_type        VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN'
                     CHECK (cell_type IN ('MACRO', 'MICRO', 'INDOOR', 'UNKNOWN')),
    data_source      VARCHAR(64),
    data_quality     SMALLINT CHECK (data_quality BETWEEN 0 AND 100),
    last_verified    DATE,
    valid_from       TIMESTAMPTZ,
    valid_to         TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, rat, cell_identity),
    CONSTRAINT cell_has_geometry
        CHECK (coverage_polygon IS NOT NULL OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);

CREATE INDEX cell_area ON cell (tenant_id, rat, area_code);

-- ---------------------------------------------------------------------------
-- Requests: one row per location request, with the state machine's state.
-- ---------------------------------------------------------------------------

CREATE TABLE location_request (
    request_id             UUID PRIMARY KEY,
    tenant_id              BIGINT NOT NULL REFERENCES tenant (tenant_id),
    client_id              UUID NOT NULL REFERENCES api_client (client_id),
    idempotency_key        VARCHAR(80),
    purpose                VARCHAR(64) NOT NULL,
    subscriber_msisdn      VARCHAR(20),
    subscriber_imsi        VARCHAR(15),
    requested_accuracy_m   INTEGER CHECK (requested_accuracy_m > 0),
    requested_priority     VARCHAR(8) NOT NULL DEFAULT 'NORMAL'
                           CHECK (requested_priority IN ('LOW', 'NORMAL', 'HIGH')),
    state                  VARCHAR(24) NOT NULL
                           CHECK (state IN ('RECEIVED', 'AUTHORISED', 'PRIVACY_CHECKED',
                                            'ROUTED', 'REQUESTED', 'ANSWERED', 'TIMEOUT',
                                            'FAILED', 'DELIVERED')),
    rat                    VARCHAR(8) CHECK (rat IN ('GSM', 'UMTS', 'LTE', 'NR')),
    path                   VARCHAR(16) CHECK (path IN ('CELL_ID', 'E_SMLC', 'LMF')),
    serving_node           VARCHAR(64),
    positioning_node       VARCHAR(64),
    result_id              UUID,
    error_code             VARCHAR(64),
    error_detail           TEXT,
    paging_performed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at            TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    CONSTRAINT request_has_a_subject
        CHECK (subscriber_msisdn IS NOT NULL OR subscriber_imsi IS NOT NULL)
);

CREATE UNIQUE INDEX request_idempotency
    ON location_request (tenant_id, client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX request_open ON location_request (tenant_id, state, deadline_at);
CREATE INDEX request_subject ON location_request (tenant_id, subscriber_imsi, created_at);
CREATE INDEX request_client_time ON location_request (tenant_id, client_id, created_at);

-- ---------------------------------------------------------------------------
-- Results: immutable, with the honesty envelope. A result is written before
-- it is delivered and is never updated.
-- ---------------------------------------------------------------------------

CREATE TABLE location_result (
    result_id               UUID PRIMARY KEY,
    tenant_id               BIGINT NOT NULL REFERENCES tenant (tenant_id),
    request_id              UUID NOT NULL,
    method                  VARCHAR(16) NOT NULL,
    methods_attempted       TEXT[] NOT NULL,
    shape                   VARCHAR(8) NOT NULL
                            CHECK (shape IN ('POINT', 'CIRCLE', 'ELLIPSE', 'POLYGON', 'ARC')),
    latitude                NUMERIC(9, 7),
    longitude               NUMERIC(10, 7),
    altitude_m              NUMERIC(7, 1),
    uncertainty_m           INTEGER NOT NULL CHECK (uncertainty_m > 0),
    confidence              SMALLINT NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    age_seconds             INTEGER NOT NULL CHECK (age_seconds >= 0),
    accuracy_requested_m    INTEGER,
    accuracy_fulfilled      BOOLEAN NOT NULL,
    serving_cell            VARCHAR(32),
    geometry_data_quality   SMALLINT CHECK (geometry_data_quality BETWEEN 0 AND 100),
    engine                  VARCHAR(16) CHECK (engine IN ('E_SMLC', 'LMF', 'SMLC', 'CELL_DB')),
    positioning_node        VARCHAR(64),
    produced_at             TIMESTAMPTZ NOT NULL,
    payload                 JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT result_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT result_geodetic
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE INDEX result_request ON location_result (tenant_id, request_id);
CREATE INDEX result_time ON location_result (tenant_id, produced_at);

-- ---------------------------------------------------------------------------
-- CDR: the PAiCore field set plus the accuracy, method and timing fields it
-- lacks. One row per answered or failed request.
-- ---------------------------------------------------------------------------

CREATE TABLE cdr (
    cdr_id                BIGSERIAL,
    tenant_id             BIGINT NOT NULL REFERENCES tenant (tenant_id),
    request_id            UUID NOT NULL,
    client_id             UUID NOT NULL,
    consent_id            UUID,
    subscriber_ref        CHAR(64) NOT NULL,
    rat_type              VARCHAR(8),
    positioning_method    VARCHAR(16),
    methods_attempted     TEXT[],
    result_code           VARCHAR(64) NOT NULL,
    accuracy_requested_m  INTEGER,
    accuracy_achieved_m   INTEGER,
    accuracy_fulfilled    BOOLEAN,
    response_time_ms      INTEGER,
    positioning_node      VARCHAR(64),
    serving_node          VARCHAR(64),
    age_of_location_s     INTEGER,
    paging_performed      BOOLEAN NOT NULL DEFAULT FALSE,
    shape                 VARCHAR(8),
    latitude              NUMERIC(9, 7),
    longitude             NUMERIC(10, 7),
    uncertainty_m         INTEGER,
    confidence            SMALLINT,
    polygon               JSONB,
    record_status         VARCHAR(16) NOT NULL
                          CHECK (record_status IN ('ANSWERED', 'FAILED', 'ABORTED', 'TIMEOUT')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, cdr_id)
);

CREATE INDEX cdr_request ON cdr (tenant_id, request_id);
CREATE INDEX cdr_time ON cdr (tenant_id, created_at);
CREATE INDEX cdr_client ON cdr (tenant_id, client_id, created_at);

-- ---------------------------------------------------------------------------
-- Audit: who asked, for whom (hashed), when, why and with what outcome.
-- Deliberately no coordinates: a reference to the result is enough.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_event (
    audit_id        BIGSERIAL,
    tenant_id       BIGINT NOT NULL REFERENCES tenant (tenant_id),
    actor_client_id UUID,
    action          VARCHAR(48) NOT NULL,
    subject_ref     CHAR(64),
    request_id      UUID,
    result_ref      UUID,
    outcome         VARCHAR(16) NOT NULL CHECK (outcome IN ('ALLOWED', 'DENIED', 'ERROR')),
    detail          JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, audit_id)
);

CREATE INDEX audit_time ON audit_event (tenant_id, occurred_at);
CREATE INDEX audit_request ON audit_event (tenant_id, request_id);
