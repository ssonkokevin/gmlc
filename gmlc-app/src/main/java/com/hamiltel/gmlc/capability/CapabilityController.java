package com.hamiltel.gmlc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class CapabilityController {

    private final CapabilityService capabilities;

    public CapabilityController(CapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * The authenticated client's capability profile. The tenant comes from the
     * API key, never from a deployment default: a client registered to another
     * tenant must not receive this tenant's claims.
     */
    @GetMapping("/capabilities")
    public ResponseEntity<JsonNode> capabilities(@CurrentClient ApiClient client) {
        CapabilityService.Snapshot snapshot = capabilities.current(client.tenantId());
        return ResponseEntity.ok()
                .header("X-Capability-Source", snapshot.source())
                .body(snapshot.profile());
    }
}
