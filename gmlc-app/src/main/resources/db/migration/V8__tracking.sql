-- ============================================================================
-- Tracking roster, version 8
--
-- A tracked subject is one subscriber a client has asked the console to
-- follow for one purpose. The row keeps the pseudonymous reference used by
-- consent and audit, a masked label for the operator and - only so the trail
-- can find the requests the network already carried - the identifier itself.
-- No read endpoint returns the identifier; the label is masked, and deleting
-- the roster row deletes the identifier.
--
-- A trail is the stored results of governed requests this client placed for
-- this purpose. This deployment has no deferred or periodic location
-- subscription, so a tracked subject does not move by itself: a new fix
-- appears only when a request stores one. The console says so rather than
-- implying a live feed.
-- ============================================================================

CREATE TABLE tracked_subject (
    tracked_id     UUID PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenant (tenant_id),
    client_id      UUID NOT NULL REFERENCES api_client (client_id),
    subject_ref    CHAR(64) NOT NULL,
    subject_msisdn VARCHAR(20),
    subject_imsi   VARCHAR(15),
    subject_label  VARCHAR(24) NOT NULL,
    purpose        VARCHAR(32) NOT NULL
                   CHECK (purpose IN ('SAFETY', 'FLEET_MANAGEMENT',
                                      'COMMERCIAL_LBS', 'IOT_ASSET')),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by     UUID REFERENCES ops_user (user_id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tracked_subject_has_identifier
        CHECK (subject_msisdn IS NOT NULL OR subject_imsi IS NOT NULL),
    UNIQUE (tenant_id, client_id, subject_ref, purpose)
);

CREATE INDEX tracked_subject_tenant ON tracked_subject (tenant_id, enabled, purpose);
CREATE INDEX tracked_subject_client ON tracked_subject (tenant_id, client_id);

-- The trail resolves a roster row to the requests that carried its identifier.
-- These indexes keep that join proportional to the subjects tracked, not to
-- the whole request history.
CREATE INDEX request_tracked_msisdn
    ON location_request (tenant_id, client_id, purpose, subscriber_msisdn, created_at);
CREATE INDEX request_tracked_imsi
    ON location_request (tenant_id, client_id, purpose, subscriber_imsi, created_at);
