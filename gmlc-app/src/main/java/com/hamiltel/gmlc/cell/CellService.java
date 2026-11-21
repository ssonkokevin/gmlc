package com.hamiltel.gmlc.cell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.audit.AuditTrail;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Writes one cell after validation: derives the canonical identity, the
 * coverage ring and, when the operator did not give one, a data-quality
 * score. Identity is always derived here so JSON and CSV cannot name the same
 * cell differently.
 */
@Service
public class CellService {

    private final CellRepository cells;
    private final ObjectMapper mapper;
    private final AuditTrail audit;

    public CellService(CellRepository cells, ObjectMapper mapper, AuditTrail audit) {
        this.cells = cells;
        this.mapper = mapper;
        this.audit = audit;
    }

    public Outcome upsert(long tenantId, CellInput raw) {
        CellValidator.check(raw);
        CellInput input = CellValidator.canonical(raw);
        CellIdentity.Rat rat = CellValidator.rat(input);
        String identity = CellIdentity.compose(rat, input.plmnMcc(), input.plmnMnc(),
                input.areaCode(), input.cellCode());
        int quality = input.dataQuality() != null
                ? input.dataQuality()
                : CellQuality.score(input);

        boolean inserted = cells.upsert(tenantId, rat, identity, input,
                polygonJson(input), quality);
        audit.record(tenantId, null, "CELL_UPSERTED", null, AuditTrail.Outcome.ALLOWED,
                Map.<String, Object>of("rat", rat.name(), "cellIdentity", identity,
                        "inserted", inserted));
        CellRepository.CellRow stored = cells.find(tenantId, rat, identity)
                .orElseThrow(() -> new IllegalStateException("cell vanished after upsert"));
        return new Outcome(inserted, stored);
    }

    String polygonJson(CellInput input) {
        List<List<java.math.BigDecimal>> ring = ringOf(input);
        if (ring == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(Map.of(
                    "type", "Polygon",
                    "coordinates", List.of(ring)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a coordinate ring must serialise as JSON", e);
        }
    }

    static List<List<java.math.BigDecimal>> ringOf(CellInput input) {
        if (input.latitude() == null || input.longitude() == null || input.radiusM() == null) {
            return null;
        }
        return CellGeometry.ringOf(input.latitude().doubleValue(), input.longitude().doubleValue(),
                input.radiusM(), input.azimuthDeg(), input.beamwidthDeg());
    }

    public record Outcome(boolean inserted, CellRepository.CellRow cell) {
    }
}
