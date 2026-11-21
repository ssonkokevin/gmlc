package com.hamiltel.gmlc.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApiClientRepository {

    /**
     * Array columns are flattened into a delimited string rather than read as
     * java.sql.Array; validation rejects the delimiter from every value, so the
     * split is lossless.
     */
    private static final String SELECT_BY_HASH = """
            SELECT c.client_id, c.tenant_id, c.name,
                   array_to_string(c.purposes, '|') AS purposes_text,
                   c.max_accuracy_m, c.rate_per_second, c.rate_per_day,
                   array_to_string(c.ip_allowlist, '|') AS allowlist_text,
                   c.enabled, t.active AS tenant_active
              FROM api_client c
              JOIN tenant t ON t.tenant_id = c.tenant_id
             WHERE c.key_hash = ?
            """;

    private static final String SELECT_BY_ID = """
            SELECT c.client_id, c.tenant_id, c.name,
                   array_to_string(c.purposes, '|') AS purposes_text,
                   c.max_accuracy_m, c.rate_per_second, c.rate_per_day,
                   array_to_string(c.ip_allowlist, '|') AS allowlist_text,
                   c.enabled, t.active AS tenant_active
              FROM api_client c
              JOIN tenant t ON t.tenant_id = c.tenant_id
             WHERE c.tenant_id = ? AND c.client_id = ?
            """;

    private final JdbcTemplate jdbc;

    public ApiClientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredClient> findByKeyHash(String keyHash) {
        return jdbc.query(SELECT_BY_HASH, this::mapStored, keyHash).stream().findFirst();
    }

    public Optional<StoredClient> findByTenantAndId(long tenantId, UUID clientId) {
        return jdbc.query(SELECT_BY_ID, this::mapStored, tenantId, clientId).stream().findFirst();
    }

    /** Enabled clients of one tenant, without key material, for the console picker. */
    public List<ClientView> listEnabled(long tenantId) {
        return jdbc.query("""
                SELECT client_id, name, array_to_string(purposes, '|') AS purposes_text,
                       max_accuracy_m
                  FROM api_client
                 WHERE tenant_id = ? AND enabled
                 ORDER BY name
                """, (rs, rowNum) -> new ClientView(
                        rs.getObject("client_id", UUID.class),
                        rs.getString("name"),
                        List.copyOf(split(rs.getString("purposes_text"))),
                        rs.getInt("max_accuracy_m")),
                tenantId);
    }

    private StoredClient mapStored(ResultSet rs, int rowNum) throws SQLException {
        return new StoredClient(
                new ApiClient(
                        rs.getObject("client_id", UUID.class),
                        rs.getLong("tenant_id"),
                        rs.getString("name"),
                        split(rs.getString("purposes_text")),
                        rs.getInt("max_accuracy_m"),
                        rs.getInt("rate_per_second"),
                        rs.getInt("rate_per_day"),
                        List.copyOf(split(rs.getString("allowlist_text")))),
                rs.getBoolean("enabled"),
                rs.getBoolean("tenant_active"));
    }

    public void insert(UUID clientId, long tenantId, String name, String keyHash,
                       List<String> purposes, int maxAccuracyM,
                       int ratePerSecond, int ratePerDay, List<String> ipAllowlist) {
        jdbc.update("""
                INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,
                                        max_accuracy_m, rate_per_second, rate_per_day, ip_allowlist)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (PreparedStatement ps) -> {
            ps.setObject(1, clientId);
            ps.setLong(2, tenantId);
            ps.setString(3, name);
            ps.setString(4, keyHash);
            ps.setArray(5, ps.getConnection().createArrayOf("text", purposes.toArray()));
            ps.setInt(6, maxAccuracyM);
            ps.setInt(7, ratePerSecond);
            ps.setInt(8, ratePerDay);
            if (ipAllowlist == null || ipAllowlist.isEmpty()) {
                ps.setNull(9, Types.ARRAY);
            } else {
                ps.setArray(9, ps.getConnection().createArrayOf("text", ipAllowlist.toArray()));
            }
        });
    }

    public void setEnabled(UUID clientId, boolean enabled) {
        jdbc.update("UPDATE api_client SET enabled = ? WHERE client_id = ?", enabled, clientId);
    }

    private static Set<String> split(String delimited) {
        if (delimited == null || delimited.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList(delimited.split("\\|", -1)));
    }

    /** The credentials row plus the two facts that decide whether it may be used. */
    public record StoredClient(ApiClient client, boolean enabled, boolean tenantActive) {
    }

    public record ClientView(UUID clientId, String name, List<String> purposes, int maxAccuracyM) {
    }
}
