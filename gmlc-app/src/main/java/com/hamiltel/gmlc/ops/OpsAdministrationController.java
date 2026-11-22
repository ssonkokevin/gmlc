package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.cell.CellIdentity;
import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.cell.CellView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only administration views. The cell plan is written through
 * {@code /v1/admin/cells} with the deployment admin key; this endpoint lets an
 * operator with MANAGE_CONFIG see what the plan actually holds without handing
 * the console a key that can rewrite the network.
 */
@RestController
@RequestMapping("/v1/ops/administration")
@OpsRequires(OpsPermission.MANAGE_CONFIG)
public class OpsAdministrationController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    private final CellRepository cells;

    public OpsAdministrationController(CellRepository cells) {
        this.cells = cells;
    }

    @GetMapping("/cells")
    public CellPlanView cells(@OpsCurrentSession OpsContext context,
                              @RequestParam(required = false) String rat,
                              @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                              @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new OpsBadRequestException("OFFSET_OUT_OF_RANGE",
                    "offset must not be negative");
        }
        String filter = rat == null || rat.isBlank() ? null : parseRat(rat).name();
        long tenantId = context.tenantId();
        List<CellView.CellSummary> page = cells.list(tenantId, filter, null, limit, offset).stream()
                .map(CellView::summary)
                .toList();
        return new CellPlanView(cells.count(tenantId, filter, null), limit, offset, filter,
                cells.countByRat(tenantId), cells.countByQuality(tenantId), page);
    }

    private static CellIdentity.Rat parseRat(String rat) {
        try {
            return CellIdentity.Rat.of(rat);
        } catch (IllegalArgumentException e) {
            throw new OpsBadRequestException("INVALID_RAT",
                    "rat must be one of GSM, UMTS, LTE, NR");
        }
    }

    public record CellPlanView(
            long total,
            int limit,
            int offset,
            String rat,
            List<CellRepository.GroupCount> byRat,
            List<CellRepository.GroupCount> byQuality,
            List<CellView.CellSummary> cells) {
    }
}
