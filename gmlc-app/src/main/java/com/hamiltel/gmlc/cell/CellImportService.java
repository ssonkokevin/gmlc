package com.hamiltel.gmlc.cell;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.location.RequestRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV import with a dry-run report. Every row is validated before any row is
 * written; a commit happens inside one transaction, so a bad file changes
 * nothing. The same format is the Phase 2 contract with the E-SMLC import.
 */
@Service
public class CellImportService {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 50_000;
    private static final int MAX_REPORTED_ERRORS = 200;

    private static final Set<String> REQUIRED = Set.of(
            "rat", "plmn_mcc", "plmn_mnc", "area_code", "cell_code",
            "latitude", "longitude", "radius_m");

    private final CellRepository cells;
    private final CellService service;
    private final AuditTrail audit;
    private final TransactionTemplate transaction;

    public CellImportService(CellRepository cells, CellService service, AuditTrail audit,
                             PlatformTransactionManager transactionManager) {
        this.cells = cells;
        this.service = service;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Report importCsv(long tenantId, String text, boolean dryRun) {
        if (text == null || text.isBlank()) {
            throw new RequestRejectedException("CSV_EMPTY", "the CSV body is empty");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new RequestRejectedException("CSV_TOO_LARGE",
                    "the CSV body must be at most " + MAX_BYTES + " bytes");
        }

        List<Csv.Row> rows = Csv.parse(text);
        int headerAt = firstNonBlank(rows);
        if (headerAt < 0) {
            throw new RequestRejectedException("CSV_EMPTY", "the CSV body has no header row");
        }

        Map<String, Integer> index = headerIndex(rows.get(headerAt));
        List<String> missing = REQUIRED.stream()
                .filter(column -> !index.containsKey(column))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new RequestRejectedException("CSV_MISSING_COLUMNS",
                    "missing columns: " + String.join(", ", missing));
        }

        List<RowError> errors = new ArrayList<>();
        List<Prepared> prepared = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        int totalRows = 0;

        for (int i = headerAt + 1; i < rows.size(); i++) {
            Csv.Row row = rows.get(i);
            if (blank(row)) {
                continue;
            }
            totalRows++;
            if (totalRows > MAX_ROWS) {
                throw new RequestRejectedException("CSV_TOO_MANY_ROWS",
                        "an import may contain at most " + MAX_ROWS + " rows");
            }

            Values values = new Values(row, index);
            CellInput input = values.toInput();
            if (!values.errors.isEmpty()) {
                errors.addAll(values.errors);
                continue;
            }
            Map<String, String> invalid = CellValidator.validate(input);
            if (!invalid.isEmpty()) {
                invalid.forEach((field, message) -> errors.add(new RowError(row.line(), field, message)));
                continue;
            }

            CellInput canonical = CellValidator.canonical(input);
            CellIdentity.Rat rat = CellValidator.rat(canonical);
            String identity = CellIdentity.compose(rat, canonical.plmnMcc(), canonical.plmnMnc(),
                    canonical.areaCode(), canonical.cellCode());
            Integer firstLine = seen.putIfAbsent(rat.name() + "|" + identity, row.line());
            if (firstLine != null) {
                errors.add(new RowError(row.line(), "cell_code",
                        "duplicates the cell on line " + firstLine));
                continue;
            }

            int quality = canonical.dataQuality() != null
                    ? canonical.dataQuality()
                    : CellQuality.score(canonical);
            prepared.add(new Prepared(rat, identity, canonical,
                    service.polygonJson(canonical), quality));
        }

        int inserted = 0;
        int updated = 0;
        if (dryRun) {
            for (Prepared row : prepared) {
                if (cells.find(tenantId, row.rat(), row.identity()).isPresent()) {
                    updated++;
                } else {
                    inserted++;
                }
            }
        } else if (!prepared.isEmpty()) {
            int[] counts = transaction.execute(status -> {
                int inserts = 0;
                int updates = 0;
                for (Prepared row : prepared) {
                    if (cells.upsert(tenantId, row.rat(), row.identity(), row.input(),
                            row.polygon(), row.quality())) {
                        inserts++;
                    } else {
                        updates++;
                    }
                }
                return new int[] {inserts, updates};
            });
            inserted = counts == null ? 0 : counts[0];
            updated = counts == null ? 0 : counts[1];
        }

        boolean truncated = errors.size() > MAX_REPORTED_ERRORS;
        List<RowError> reported = truncated
                ? List.copyOf(errors.subList(0, MAX_REPORTED_ERRORS))
                : List.copyOf(errors);

        if (!dryRun) {
            audit.record(tenantId, null, "CELL_IMPORT", null, AuditTrail.Outcome.ALLOWED,
                    Map.<String, Object>of("inserted", inserted, "updated", updated,
                            "totalRows", totalRows));
        }

        return new Report(dryRun, totalRows, prepared.size(), totalRows - prepared.size(),
                inserted, updated, truncated, reported);
    }

    private static int firstNonBlank(List<Csv.Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (!blank(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean blank(Csv.Row row) {
        return row.values().stream().allMatch(value -> value == null || value.isBlank());
    }

    private static Map<String, Integer> headerIndex(Csv.Row header) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.values().size(); i++) {
            String name = normaliseHeader(header.values().get(i));
            if (name.isEmpty()) {
                continue;
            }
            name = switch (name) {
                case "mcc" -> "plmn_mcc";
                case "mnc" -> "plmn_mnc";
                default -> name;
            };
            index.putIfAbsent(name, i);
        }
        return index;
    }

    private static String normaliseHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Prepared(CellIdentity.Rat rat, String identity, CellInput input,
                            String polygon, int quality) {
    }

    private static final class Values {
        private final Csv.Row row;
        private final Map<String, Integer> index;
        private final List<RowError> errors = new ArrayList<>();

        private Values(Csv.Row row, Map<String, Integer> index) {
            this.row = row;
            this.index = index;
        }

        private String text(String column) {
            Integer at = index.get(column);
            if (at == null || at >= row.values().size()) {
                return null;
            }
            String value = row.values().get(at).trim();
            return value.isEmpty() ? null : value;
        }

        private BigDecimal decimal(String column) {
            String value = text(column);
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                errors.add(new RowError(row.line(), column, "must be a decimal number"));
                return null;
            }
        }

        private Integer integer(String column) {
            String value = text(column);
            if (value == null) {
                return null;
            }
            if (!value.matches("-?[0-9]{1,9}")) {
                errors.add(new RowError(row.line(), column, "must be a whole number"));
                return null;
            }
            return Integer.valueOf(value);
        }

        private LocalDate date(String column) {
            String value = text(column);
            if (value == null) {
                return null;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                errors.add(new RowError(row.line(), column, "must be an ISO date, for example 2026-01-15"));
                return null;
            }
        }

        private CellInput toInput() {
            return new CellInput(
                    text("rat"), text("plmn_mcc"), text("plmn_mnc"),
                    text("area_code"), text("cell_code"), text("site_name"),
                    decimal("latitude"), decimal("longitude"), decimal("antenna_height_m"),
                    integer("azimuth_deg"), integer("beamwidth_deg"), integer("radius_m"),
                    integer("pci"), integer("arfcn"), text("cell_type"), text("data_source"),
                    integer("data_quality"), date("last_verified"));
        }
    }

    public record Report(
            boolean dryRun,
            int totalRows,
            int validRows,
            int invalidRows,
            int inserted,
            int updated,
            boolean errorsTruncated,
            List<RowError> errors) {
    }

    public record RowError(int line, String field, String message) {
    }
}
