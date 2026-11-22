package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.config.ConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Configuration an operator may change. Reads and writes both need
 * MANAGE_CONFIG: a threshold change alters what raises an alert, and the LCS
 * client name is sent on the signalling. In a production deployment a
 * signalling change waits for a second operator; the same person can never
 * approve it.
 */
@RestController
@RequestMapping("/v1/ops/configuration")
@OpsRequires(OpsPermission.MANAGE_CONFIG)
public class OpsConfigController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final ConfigService config;

    public OpsConfigController(ConfigService config) {
        this.config = config;
    }

    @GetMapping
    public ConfigService.Snapshot snapshot(@OpsCurrentSession OpsContext context) {
        return config.snapshot(context.tenantId());
    }

    @GetMapping("/changes")
    public List<ConfigService.ChangeView> changes(@OpsCurrentSession OpsContext context,
                                                  @RequestParam(defaultValue = "ALL") String status,
                                                  @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
                                                  int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return config.changes(context.tenantId(), status, limit);
    }

    @PostMapping("/changes")
    public ResponseEntity<ConfigService.ChangeView> request(
            @OpsCurrentSession OpsContext context,
            @Valid @RequestBody ChangeRequest body) {
        ConfigService.ChangeView change = config.request(context.tenantId(),
                context.user().userId(), body.key(), body.value(), body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(change);
    }

    @PostMapping("/changes/{changeId}/approve")
    public ConfigService.ChangeView approve(@OpsCurrentSession OpsContext context,
                                            @PathVariable UUID changeId,
                                            @Valid @RequestBody DecisionRequest body) {
        return config.approve(context.tenantId(), context.user().userId(), changeId, body.note());
    }

    @PostMapping("/changes/{changeId}/reject")
    public ConfigService.ChangeView reject(@OpsCurrentSession OpsContext context,
                                           @PathVariable UUID changeId,
                                           @Valid @RequestBody DecisionRequest body) {
        return config.reject(context.tenantId(), context.user().userId(), changeId, body.note());
    }

    public record ChangeRequest(
            @NotBlank @Size(max = 64) String key,
            JsonNode value,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record DecisionRequest(@Size(max = 500) String note) {
    }
}
