package com.hamiltel.gmlc.client;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A registered API client. The key itself is not part of this record: the
 * registry stores only its SHA-256 hash, and nothing outside registration ever
 * sees the plaintext.
 */
public record ApiClient(
        UUID clientId,
        long tenantId,
        String name,
        Set<String> purposes,
        int maxAccuracyM,
        int ratePerSecond,
        int ratePerDay,
        List<String> ipAllowlist) {

    public boolean allowsPurpose(String purpose) {
        return purposes.contains(purpose);
    }

    /** An empty allowlist means "no address restriction", not "deny everything". */
    public boolean addressAllowed(String remoteAddress) {
        if (ipAllowlist == null || ipAllowlist.isEmpty()) {
            return true;
        }
        return ipAllowlist.stream().anyMatch(rule -> CidrMatcher.matches(rule, remoteAddress));
    }
}
