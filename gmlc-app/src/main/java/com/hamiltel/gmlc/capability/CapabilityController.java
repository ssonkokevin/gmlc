package com.hamiltel.gmlc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.config.GmlcProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class CapabilityController {

    private final CapabilityService capabilities;
    private final GmlcProperties properties;

    public CapabilityController(CapabilityService capabilities, GmlcProperties properties) {
        this.capabilities = capabilities;
        this.properties = properties;
    }

    /**
     * The tenant's capability profile. Authentication arrives with the Le API
     * phase; until then the default tenant is served and the header says where
     * the profile came from.
     */
    @GetMapping("/capabilities")
    public ResponseEntity<JsonNode> capabilities() {
        CapabilityService.Snapshot snapshot = capabilities.current(properties.defaultTenantId());
        return ResponseEntity.ok()
                .header("X-Capability-Source", snapshot.source())
                .body(snapshot.profile());
    }
}
