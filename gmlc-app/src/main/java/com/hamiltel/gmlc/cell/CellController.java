package com.hamiltel.gmlc.cell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import com.hamiltel.gmlc.location.RequestRejectedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read access to the authoritative cell store. Writing is an operator action
 * and lives under /v1/admin/cells; a client can see which cells exist, but
 * cannot change what the network is.
 */
@RestController
public class CellController {

    private static final int MAX_LIMIT = 500;

    private final CellRepository cells;
    private final ObjectMapper mapper;

    public CellController(CellRepository cells, ObjectMapper mapper) {
        this.cells = cells;
        this.mapper = mapper;
    }

    @GetMapping("/v1/cells")
    public CellPage list(@CurrentClient ApiClient client,
                         @RequestParam(required = false) String rat,
                         @RequestParam(required = false) String area,
                         @RequestParam(defaultValue = "100") int limit,
                         @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new RequestRejectedException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new RequestRejectedException("OFFSET_OUT_OF_RANGE", "offset must not be negative");
        }
        String ratFilter = rat == null || rat.isBlank() ? null : parseRat(rat).name();
        String areaFilter = area == null || area.isBlank() ? null : area.trim();

        List<CellView.CellSummary> page = cells.list(client.tenantId(), ratFilter, areaFilter,
                        limit, offset).stream()
                .map(CellView::summary)
                .toList();
        long total = cells.count(client.tenantId(), ratFilter, areaFilter);
        return new CellPage(page, limit, offset, total);
    }

    @GetMapping("/v1/cells/{rat}/{cellIdentity}")
    public CellView get(@CurrentClient ApiClient client,
                        @PathVariable("rat") String rat,
                        @PathVariable("cellIdentity") String cellIdentity) {
        CellIdentity.Rat parsedRat = parseRat(rat);
        CellIdentity.Parts parts;
        try {
            parts = CellIdentity.parse(parsedRat, cellIdentity);
        } catch (IllegalArgumentException e) {
            throw new RequestRejectedException("INVALID_CELL_IDENTITY", e.getMessage());
        }
        String canonical = CellIdentity.compose(parsedRat, parts.mcc(), parts.mnc(),
                parts.areaCode(), parts.cellCode());
        return cells.find(client.tenantId(), parsedRat, canonical)
                .map(row -> CellView.of(row, mapper))
                .orElseThrow(() -> new CellNotFoundException(parsedRat.name(), canonical));
    }

    private static CellIdentity.Rat parseRat(String rat) {
        try {
            return CellIdentity.Rat.of(rat);
        } catch (IllegalArgumentException e) {
            throw new RequestRejectedException("INVALID_RAT",
                    "rat must be one of GSM, UMTS, LTE, NR");
        }
    }

    public record CellPage(List<CellView.CellSummary> cells, int limit, int offset, long total) {
    }
}
