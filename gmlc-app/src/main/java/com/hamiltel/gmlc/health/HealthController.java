package com.hamiltel.gmlc.health;

import com.hamiltel.gmlc.capability.CapabilityProfileUnavailableException;
import com.hamiltel.gmlc.capability.CapabilityService;
import com.hamiltel.gmlc.config.GmlcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;
    private final CapabilityService capabilities;
    private final GmlcProperties properties;

    public HealthController(JdbcTemplate jdbc, CapabilityService capabilities,
                            GmlcProperties properties) {
        this.jdbc = jdbc;
        this.capabilities = capabilities;
        this.properties = properties;
    }

    /**
     * Liveness plus the two things that make the gateway useless: no database,
     * or a capability profile the API refuses to serve. The body carries no
     * connection strings or SQL.
     */
    @GetMapping("/v1/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "gmlc");
        body.put("version", properties.version());
        body.put("checkedAt", Instant.now().toString());

        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            body.put("database", one != null && one == 1 ? "UP" : "DOWN");
        } catch (DataAccessException e) {
            log.warn("health check: database unreachable: {}", e.getClass().getSimpleName());
            body.put("database", "DOWN");
            body.put("status", "DOWN");
            body.put("reason", "database unreachable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        try {
            CapabilityService.Snapshot snapshot = capabilities.current(properties.defaultTenantId());
            body.put("capabilities", "UP");
            body.put("capabilityProfileVersion", snapshot.profile().path("version").asText());
            body.put("capabilitySource", snapshot.source());
        } catch (CapabilityProfileUnavailableException e) {
            body.put("capabilities", "INVALID");
            body.put("status", "DOWN");
            body.put("reason", "capability profile invalid");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        body.put("status", "UP");
        return ResponseEntity.ok(body);
    }
}
