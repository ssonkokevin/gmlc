-- ============================================================================
-- Console metrics, version 4
--
-- The dashboard has to show throughput split by signalling protocol and a
-- request mix by operation. Neither can be inferred from the path afterwards,
-- so the gateway records what it actually used: MAP or DIAMETER at request
-- time, and the completed operation on the CDR. A failed request keeps the
-- protocol but no operation - the mix then says UNATTRIBUTED rather than
-- inventing one.
-- ============================================================================

ALTER TABLE location_request ADD COLUMN protocol VARCHAR(16);
ALTER TABLE cdr ADD COLUMN protocol VARCHAR(16);
ALTER TABLE cdr ADD COLUMN operation VARCHAR(24);

CREATE INDEX request_protocol_time ON location_request (tenant_id, protocol, created_at);
CREATE INDEX cdr_protocol_time ON cdr (tenant_id, protocol, created_at);
