package com.hamiltel.gmlc.admin;

import java.util.List;
import java.util.UUID;

/**
 * The only response that ever contains the API key. It is returned once, at
 * registration; nothing can retrieve it later.
 */
public record ClientRegistrationResponse(
        UUID clientId,
        long tenantId,
        String name,
        List<String> purposes,
        int maxAccuracyM,
        int ratePerSecond,
        int ratePerDay,
        List<String> ipAllowlist,
        String apiKey,
        String apiKeyNote) {
}
