-- ============================================================================
-- Operator access, version 11
--
-- Break-glass is an emergency elevation: an operator who holds the sealed
-- deployment code may raise a short, reason-bound grant that carries the
-- ADMIN permission set on that session only. The grant is created with a review
-- obligation attached; a different administrator must review it once it has
-- ended. Nothing here can widen location powers quietly: every grant is a
-- row an administrator sees, and the code itself is never stored.
--
-- session_id is a correlation reference, not an owner: sessions are swept one
-- day after they expire, and the grant must survive that sweep for the review.
-- ============================================================================

CREATE TABLE ops_break_glass (
    grant_id       UUID PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenant (tenant_id),
    user_id        UUID NOT NULL REFERENCES ops_user (user_id),
    session_id     UUID REFERENCES ops_session (session_id) ON DELETE SET NULL,
    reason         VARCHAR(500) NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ,
    review_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                   CHECK (review_status IN ('PENDING', 'REVIEWED')),
    reviewed_by    UUID REFERENCES ops_user (user_id),
    reviewed_at    TIMESTAMPTZ,
    review_note    VARCHAR(500),
    CONSTRAINT ops_break_glass_reason_present CHECK (length(btrim(reason)) >= 20),
    CONSTRAINT ops_break_glass_expiry_after_grant CHECK (expires_at > granted_at),
    CONSTRAINT ops_break_glass_end_after_grant CHECK (ended_at IS NULL OR ended_at >= granted_at),
    CONSTRAINT ops_break_glass_review_pair
        CHECK (review_status <> 'REVIEWED'
               OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
                   AND review_note IS NOT NULL)),
    CONSTRAINT ops_break_glass_reviewer_is_other CHECK (reviewed_by IS NULL OR reviewed_by <> user_id)
);

CREATE UNIQUE INDEX ops_break_glass_one_active
    ON ops_break_glass (tenant_id, user_id) WHERE ended_at IS NULL;
CREATE INDEX ops_break_glass_time ON ops_break_glass (tenant_id, granted_at DESC);
CREATE INDEX ops_break_glass_session ON ops_break_glass (session_id);
