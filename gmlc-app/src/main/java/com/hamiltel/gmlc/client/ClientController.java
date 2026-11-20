package com.hamiltel.gmlc.client;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client's own configuration, which is what a dashboard or an integration
 * test needs: what it is allowed to ask for, and what it has left. The key hash
 * is never returned, and neither is the key.
 */
@RestController
public class ClientController {

    @GetMapping("/v1/clients/me")
    public Map<String, Object> me(@CurrentClient ApiClient client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", client.clientId());
        body.put("tenantId", client.tenantId());
        body.put("name", client.name());
        body.put("purposes", client.purposes());
        body.put("maxAccuracyM", client.maxAccuracyM());
        body.put("ratePerSecond", client.ratePerSecond());
        body.put("ratePerDay", client.ratePerDay());
        body.put("ipAllowlist", client.ipAllowlist());
        return body;
    }
}
