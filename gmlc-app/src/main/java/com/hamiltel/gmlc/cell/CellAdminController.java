package com.hamiltel.gmlc.cell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.admin.AdminKeyGuard;
import com.hamiltel.gmlc.config.GmlcProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator writes. The cell plan decides where every 2G/3G/LTE/NR cell-ID
 * answer points, so changing it requires the admin key, not an LBS client
 * key. An import is dry-run by default: the report is the product until the
 * operator asks to commit.
 */
@RestController
@RequestMapping("/v1/admin/cells")
public class CellAdminController {

    private final CellService service;
    private final CellImportService imports;
    private final AdminKeyGuard guard;
    private final GmlcProperties properties;
    private final ObjectMapper mapper;

    public CellAdminController(CellService service, CellImportService imports,
                               AdminKeyGuard guard, GmlcProperties properties,
                               ObjectMapper mapper) {
        this.service = service;
        this.imports = imports;
        this.guard = guard;
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<CellView> upsert(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody CellInput input) {
        guard.require(adminKey);
        CellService.Outcome outcome = service.upsert(properties.defaultTenantId(), input);
        return ResponseEntity
                .status(outcome.inserted() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(CellView.of(outcome.cell(), mapper));
    }

    @PostMapping(value = "/import", consumes = {"text/csv", "text/plain"})
    public CellImportService.Report importCsv(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestBody String csv) {
        guard.require(adminKey);
        return imports.importCsv(properties.defaultTenantId(), csv, dryRun);
    }
}
