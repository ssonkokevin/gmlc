-- ============================================================================
-- Operator console, version 3
--
-- Operators are people, not API clients: they have an account, a password
-- hash (Argon2id), a TOTP secret and a role. A session row keeps only the
-- SHA-256 of the bearer token, so a database read cannot be replayed as a
-- cookie. The console never consumes a client quota and its records are
-- separate from api_client.
--
-- The audit trail gains actor_user_id so console actions name the person
-- rather than a client. The column is nullable: client actions keep NULL.
-- ============================================================================

CREATE TABLE ops_user (
    user_id             UUID PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenant (tenant_id),
    username            VARCHAR(64) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    role                VARCHAR(16) NOT NULL
                        CHECK (role IN ('ADMIN', 'OPERATOR', 'ANALYST', 'VIEWER')),
    password_hash       VARCHAR(255) NOT NULL,
    totp_secret         VARCHAR(64) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts     INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, username)
);

CREATE INDEX ops_user_role ON ops_user (tenant_id, role, enabled);

CREATE TABLE ops_session (
    session_id          UUID PRIMARY KEY,
    token_hash          CHAR(64) NOT NULL UNIQUE,
    user_id             UUID NOT NULL REFERENCES ops_user (user_id),
    tenant_id           BIGINT NOT NULL REFERENCES tenant (tenant_id),
    csrf_token          VARCHAR(64) NOT NULL,
    remote_addr         VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_expires_at     TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT ops_session_expiry_order
        CHECK (idle_expires_at <= absolute_expires_at)
);

CREATE INDEX ops_session_user ON ops_session (tenant_id, user_id, revoked_at);
CREATE INDEX ops_session_sweep ON ops_session (absolute_expires_at);

ALTER TABLE audit_event ADD COLUMN actor_user_id UUID;

CREATE INDEX audit_actor_user ON audit_event (tenant_id, actor_user_id, occurred_at);
