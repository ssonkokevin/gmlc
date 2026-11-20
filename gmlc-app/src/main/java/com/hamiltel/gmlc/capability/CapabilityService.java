package com.hamiltel.gmlc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.config.GmlcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Serves what a tenant's network can really do.
 *
 * <p>A database row wins; when there is none, the profile bundled with the
 * release covers the default tenant. Either way the profile is validated
 * before it is served, and an invalid profile is an outage, not a warning:
 * a client must never be told a method is available when it is not.
 */
@Service
public class CapabilityService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityProfileValidator validator;
    private final GmlcProperties properties;
    private final JsonNode bundled;

    public CapabilityService(JdbcTemplate jdbc, ObjectMapper mapper,
                             CapabilityProfileValidator validator, GmlcProperties properties,
                             ResourceLoader resources) throws IOException {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.validator = validator;
        this.properties = properties;
        this.bundled = loadBundled(resources, properties.bundledCapabilityProfile());
        List<String> errors = validator.validate(bundled);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "bundled capability profile is invalid: " + String.join("; ", errors));
        }
        log.info("bundled capability profile tenant={} version={}",
                bundled.path("tenant_id").asLong(), bundled.path("version").asText());
    }

    public Snapshot current(long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select profile::text as profile from capability_profile where tenant_id = ?",
                tenantId);
        if (!rows.isEmpty()) {
            JsonNode profile;
            try {
                profile = mapper.readTree((String) rows.get(0).get("profile"));
            } catch (IOException e) {
                throw new CapabilityProfileUnavailableException(
                        "stored capability profile is not valid JSON", e);
            }
            List<String> errors = validator.validate(profile);
            if (!errors.isEmpty()) {
                log.error("tenant {} capability profile rejected: {}", tenantId, errors);
                throw new CapabilityProfileUnavailableException(
                        "stored capability profile is invalid: " + String.join("; ", errors));
            }
            return new Snapshot(profile, "database");
        }
        if (tenantId != properties.defaultTenantId()) {
            throw new UnknownTenantException(tenantId);
        }
        return new Snapshot(bundled, "bundled");
    }

    private JsonNode loadBundled(ResourceLoader resources, String location) throws IOException {
        Resource resource = resources.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    /**
     * @param profile the validated profile, exactly as the core repository's
     *                schema defines it
     * @param source  {@code database} or {@code bundled}, for operators
     */
    public record Snapshot(JsonNode profile, String source) {
    }
}
