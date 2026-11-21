package com.hamiltel.gmlc.admin;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClientRepository;
import com.hamiltel.gmlc.client.ApiKeyHasher;
import com.hamiltel.gmlc.config.GmlcProperties;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client registration. It is protected by a single operator key from the
 * environment, not by an API key, because it is the step that creates API keys.
 * With no admin key configured the endpoint does not exist as far as callers
 * are concerned.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminClientController {

    private static final Logger log = LoggerFactory.getLogger(AdminClientController.class);

    /** The purposes a client may be created for. Lawful interception is not one. */
    public static final Set<String> ALLOWED_PURPOSES = Set.of(
            "EMERGENCY",
            "SAFETY",
            "FLEET_MANAGEMENT",
            "COMMERCIAL_LBS",
            "IOT_ASSET",
            "TEST");

    private final ApiClientRepository clients;
    private final AuditTrail audit;
    private final GmlcProperties properties;
    private final AdminKeyGuard guard;

    public AdminClientController(ApiClientRepository clients, AuditTrail audit,
                                 GmlcProperties properties, AdminKeyGuard guard) {
        this.clients = clients;
        this.audit = audit;
        this.properties = properties;
        this.guard = guard;
    }

    @PostMapping("/clients")
    public ResponseEntity<ClientRegistrationResponse> register(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @Valid @RequestBody ClientRegistrationRequest request) {

        guard.require(adminKey);

        List<String> purposes = List.copyOf(new LinkedHashSet<>(request.purposes()));
        List<String> unknown = purposes.stream().filter(p -> !ALLOWED_PURPOSES.contains(p)).toList();
        if (!unknown.isEmpty()) {
            throw new PurposeNotAllowedException(unknown);
        }

        List<String> allowlist = request.ipAllowlist() == null
                ? List.of()
                : List.copyOf(request.ipAllowlist());
        for (String rule : allowlist) {
            if (!com.hamiltel.gmlc.client.CidrMatcher.isSupportedRule(rule)) {
                throw new InvalidAllowlistRuleException(rule);
            }
        }

        String rawKey = ApiKeyHasher.newKey();
        UUID clientId = UUID.randomUUID();
        long tenantId = properties.defaultTenantId();

        clients.insert(clientId, tenantId, request.name(), ApiKeyHasher.hash(rawKey),
                purposes, request.maxAccuracyMOrDefault(),
                request.ratePerSecondOrDefault(), request.ratePerDayOrDefault(), allowlist);

        audit.record(tenantId, null, "CLIENT_CREATED", null, AuditTrail.Outcome.ALLOWED,
                Map.of("clientId", clientId.toString(), "name", request.name()));
        log.info("registered API client {} ({}) for tenant {}", clientId, request.name(), tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(new ClientRegistrationResponse(
                clientId, tenantId, request.name(), purposes,
                request.maxAccuracyMOrDefault(), request.ratePerSecondOrDefault(),
                request.ratePerDayOrDefault(), allowlist, rawKey,
                "store this key now; it is not retrievable later"));
    }


    public static class PurposeNotAllowedException extends RuntimeException {

        private final List<String> purposes;

        public PurposeNotAllowedException(List<String> purposes) {
            super("unsupported purpose");
            this.purposes = purposes;
        }

        public List<String> purposes() {
            return purposes;
        }
    }

    public static class InvalidAllowlistRuleException extends RuntimeException {

        private final String rule;

        public InvalidAllowlistRuleException(String rule) {
            super("unsupported address rule");
            this.rule = rule;
        }

        public String rule() {
            return rule;
        }
    }
}
