package com.hamiltel.gmlc.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.capability.CapabilityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A request or consent names its subject by MSISDN or IMSI. IMSIs are checked
 * against the tenant's PLMNs from the capability profile, so a typo or an
 * attempt to point the gateway at another operator's subscriber is refused
 * before any signalling.
 */
@Component
public class SubscriberValidator {

    private final CapabilityService capabilities;

    public SubscriberValidator(CapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    public void validate(long tenantId, String msisdn, String imsi) {
        if (isBlank(msisdn) && isBlank(imsi)) {
            throw new RequestRejectedException("SUBJECT_REQUIRED",
                    "provide the subscriber's MSISDN or IMSI");
        }
        if (!isBlank(imsi)) {
            requireTenantPlmn(tenantId, imsi);
        }
    }

    private void requireTenantPlmn(long tenantId, String imsi) {
        List<String> prefixes = tenantPlmnPrefixes(tenantId);
        boolean known = prefixes.stream().anyMatch(imsi::startsWith);
        if (!known) {
            throw new RequestRejectedException("IMSI_NOT_IN_TENANT_PLMN",
                    "the IMSI does not belong to any PLMN this tenant operates");
        }
    }

    private List<String> tenantPlmnPrefixes(long tenantId) {
        JsonNode plmns = capabilities.current(tenantId).profile().path("plmns");
        List<String> prefixes = new ArrayList<>();
        for (JsonNode plmn : plmns) {
            String mcc = plmn.path("mcc").asText("");
            String mnc = plmn.path("mnc").asText("");
            if (!mcc.isEmpty() && !mnc.isEmpty()) {
                prefixes.add(mcc + mnc);
            }
        }
        return prefixes;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
