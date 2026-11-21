package com.hamiltel.gmlc.cell;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The authoritative cell store. Upsert keyed on the canonical identity, so a
 * second import of the same plan updates rows instead of duplicating them.
 */
@Repository
public class CellRepository {

    private static final String COLUMNS = """
            tenant_id, rat, cell_identity, plmn_mcc, plmn_mnc, area_code, cell_code,
            site_name, latitude, longitude, antenna_height_m, azimuth_deg, beamwidth_deg,
            radius_m, coverage_polygon::text as coverage_polygon, pci, arfcn, cell_type,
            data_source, data_quality, last_verified, updated_at
            """;

    private final JdbcTemplate jdbc;

    public CellRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when the row did not exist before */
    public boolean upsert(long tenantId, CellIdentity.Rat rat, String identity, CellInput input,
                          String coveragePolygon, int dataQuality) {
        Boolean inserted = jdbc.queryForObject("""
                INSERT INTO cell (tenant_id, rat, cell_identity, plmn_mcc, plmn_mnc,
                    area_code, cell_code, site_name, latitude, longitude, antenna_height_m,
                    azimuth_deg, beamwidth_deg, radius_m, coverage_polygon, pci, arfcn,
                    cell_type, data_source, data_quality, last_verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, rat, cell_identity) DO UPDATE SET
                    plmn_mcc = EXCLUDED.plmn_mcc,
                    plmn_mnc = EXCLUDED.plmn_mnc,
                    area_code = EXCLUDED.area_code,
                    cell_code = EXCLUDED.cell_code,
                    site_name = EXCLUDED.site_name,
                    latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude,
                    antenna_height_m = EXCLUDED.antenna_height_m,
                    azimuth_deg = EXCLUDED.azimuth_deg,
                    beamwidth_deg = EXCLUDED.beamwidth_deg,
                    radius_m = EXCLUDED.radius_m,
                    coverage_polygon = EXCLUDED.coverage_polygon,
                    pci = EXCLUDED.pci,
                    arfcn = EXCLUDED.arfcn,
                    cell_type = EXCLUDED.cell_type,
                    data_source = EXCLUDED.data_source,
                    data_quality = EXCLUDED.data_quality,
                    last_verified = EXCLUDED.last_verified,
                    updated_at = now()
                RETURNING (xmax = 0)
                """, Boolean.class,
                tenantId, rat.name(), identity, input.plmnMcc(), input.plmnMnc(),
                input.areaCode(), input.cellCode(), input.siteName(), input.latitude(),
                input.longitude(), input.antennaHeightM(), input.azimuthDeg(),
                input.beamwidthDeg(), input.radiusM(), coveragePolygon, input.pci(),
                input.arfcn(), CellValidator.normalise(input.cellType()), input.dataSource(),
                dataQuality, input.lastVerified());
        return Boolean.TRUE.equals(inserted);
    }

    public Optional<CellRow> find(long tenantId, CellIdentity.Rat rat, String identity) {
        List<CellRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM cell"
                        + " WHERE tenant_id = ? AND rat = ? AND cell_identity = ?",
                MAPPER, tenantId, rat.name(), identity);
        return rows.stream().findFirst();
    }

    /**
     * The lookup an ECGI or CGI permits: no area code is carried. Cell codes
     * compare canonically, so a plan row imported as {@code 000123} matches an
     * identifier decoded as {@code 123}.
     */
    public List<CellRow> findByPlmnAndCellCode(long tenantId, String rat, String mcc, String mnc,
                                               String cellCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cell"
                        + " WHERE tenant_id = ? AND rat = ? AND plmn_mcc = ? AND plmn_mnc = ?"
                        + " AND regexp_replace(cell_code, '^0+(?=.)', '') = ?",
                MAPPER, tenantId, rat, mcc, mnc, cellCode);
    }

    public List<CellRow> list(long tenantId, String rat, String area, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM cell WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (rat != null && !rat.isBlank()) {
            sql.append(" AND rat = ?");
            args.add(CellIdentity.Rat.of(rat).name());
        }
        if (area != null && !area.isBlank()) {
            sql.append(" AND area_code = ?");
            args.add(area);
        }
        sql.append(" ORDER BY rat, cell_identity LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long tenantId, String rat, String area) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM cell WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (rat != null && !rat.isBlank()) {
            sql.append(" AND rat = ?");
            args.add(CellIdentity.Rat.of(rat).name());
        }
        if (area != null && !area.isBlank()) {
            sql.append(" AND area_code = ?");
            args.add(area);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private static final RowMapper<CellRow> MAPPER = (rs, rowNum) -> new CellRow(
            rs.getLong("tenant_id"),
            rs.getString("rat"),
            rs.getString("cell_identity"),
            rs.getString("plmn_mcc"),
            rs.getString("plmn_mnc"),
            rs.getString("area_code"),
            rs.getString("cell_code"),
            rs.getString("site_name"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getBigDecimal("antenna_height_m"),
            rs.getObject("azimuth_deg", Integer.class),
            rs.getObject("beamwidth_deg", Integer.class),
            rs.getObject("radius_m", Integer.class),
            rs.getString("coverage_polygon"),
            rs.getObject("pci", Integer.class),
            rs.getObject("arfcn", Integer.class),
            rs.getString("cell_type"),
            rs.getString("data_source"),
            rs.getObject("data_quality", Integer.class),
            date(rs, "last_verified"),
            instant(rs, "updated_at"));

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * @param coveragePolygon stored GeoJSON text, or null when none was
     *                        computed; the view can recompute it
     */
    public record CellRow(
            long tenantId,
            String rat,
            String cellIdentity,
            String plmnMcc,
            String plmnMnc,
            String areaCode,
            String cellCode,
            String siteName,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal antennaHeightM,
            Integer azimuthDeg,
            Integer beamwidthDeg,
            Integer radiusM,
            String coveragePolygon,
            Integer pci,
            Integer arfcn,
            String cellType,
            String dataSource,
            Integer dataQuality,
            LocalDate lastVerified,
            Instant updatedAt) {
    }
}
