package com.hamiltel.gmlc.cdr;

import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import com.hamiltel.gmlc.location.RequestRejectedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A client reads its own records. Positions appear only inside the envelope
 * (method, shape, uncertainty, confidence, age) that makes their quality
 * visible.
 */
@RestController
public class CdrController {

    private static final int MAX_LIMIT = 200;

    private final CdrRepository cdrs;

    public CdrController(CdrRepository cdrs) {
        this.cdrs = cdrs;
    }

    @GetMapping("/v1/cdrs")
    public CdrPage list(@CurrentClient ApiClient client,
                        @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new RequestRejectedException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        List<CdrRepository.CdrRow> rows = cdrs.recent(client.tenantId(), client.clientId(), limit);
        return new CdrPage(rows, limit);
    }

    public record CdrPage(List<CdrRepository.CdrRow> cdrs, int limit) {
    }
}
