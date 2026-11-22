package com.hamiltel.gmlc.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.ops.OpsBadRequestException;
import com.hamiltel.gmlc.ops.OpsConflictException;
import com.hamiltel.gmlc.ops.OpsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned configuration with a reason and, for a signalling change in a
 * production deployment, a second approver.
 *
 * <p>A request always writes a row first. In a lab it then applies in the same
 * transaction; in production a signalling item stays PENDING until another
 * operator with MANAGE_CONFIG approves it. Applying checks the base version,
 * so a change raised against a value that has since moved fails instead of
 * overwriting the newer value. The trail is append-only: decisions update the
 * row they decide, never delete one.
 */
@Service
public class ConfigService {

    public record ChangeView(UUID changeId, String itemKey, int baseVersion, JsonNode previousValue,
                             JsonNode requestedValue, String reason, String status,
                             boolean requiresApproval, UUID requestedBy, String requestedByName,
                             Instant requestedAt, UUID decidedBy, String decidedByName,
                             Instant decidedAt, String decisionNote, Integer appliedVersion,
                             Instant appliedAt) {
    }

    public record ItemView(String key, String label, String description, String type,
                           boolean signalling, Object value, Object defaultValue, String source,
                           int version, UUID updatedBy, String updatedByName, Instant updatedAt,
                           Double min, Double max, Integer maxLength, ChangeView pending) {
    }

    public record Snapshot(String environment, boolean secondApproverRequired, List<ItemView> items) {
    }

    private final ConfigRepository repository;
    private final ConfigRegistry registry;
    private final OpsProperties ops;
    private final AuditTrail audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public ConfigService(ConfigRepository repository, ConfigRegistry registry, OpsProperties ops,
                         AuditTrail audit, ObjectMapper mapper,
                         PlatformTransactionManager transactions) {
        this.repository = repository;
        this.registry = registry;
        this.ops = ops;
        this.audit = audit;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
    }

    public Snapshot snapshot(long tenantId) {
        Map<String, ConfigRepository.ItemRow> stored = new LinkedHashMap<>();
        repository.items(tenantId).forEach(row -> stored.put(row.itemKey(), row));
        List<ItemView> items = registry.specs().stream()
                .map(spec -> view(tenantId, spec, stored.get(spec.key())))
                .toList();
        return new Snapshot(ops.normalisedEnvironment(), secondApproverRequired(), items);
    }

    public Object value(long tenantId, String key) {
        ConfigRegistry.ItemSpec spec = spec(key);
        return repository.item(tenantId, key)
                .map(row -> parse(row.valueJson(), spec))
                .orElseGet(spec::defaultValue);
    }

    public int intValue(long tenantId, String key) {
        Object value = value(tenantId, key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    public double doubleValue(long tenantId, String key) {
        Object value = value(tenantId, key);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
    }

    public String textValue(long tenantId, String key) {
        Object value = value(tenantId, key);
        return value == null ? null : value.toString();
    }

    public ChangeView request(long tenantId, UUID requestedBy, String key, JsonNode proposed,
                              String reason) {
        ConfigRegistry.ItemSpec spec = spec(key);
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.length() < 10) {
            throw new OpsBadRequestException("REASON_TOO_SHORT",
                    "a configuration change needs a reason of at least 10 characters");
        }
        if (proposed == null || proposed.isNull()) {
            throw new OpsBadRequestException("CONFIG_VALUE_MISSING",
                    "the request must carry a value for " + spec.key());
        }
        Object normalised = normalise(spec, proposed);
        String requestedJson = json(normalised);

        if (repository.pending(tenantId, spec.key()).isPresent()) {
            throw new OpsConflictException("CONFIG_CHANGE_PENDING",
                    "another change to " + spec.key() + " is already waiting for a decision");
        }
        Optional<ConfigRepository.ItemRow> current = repository.item(tenantId, spec.key());
        int baseVersion = current.map(ConfigRepository.ItemRow::version).orElse(0);
        String previousJson = current.map(ConfigRepository.ItemRow::valueJson).orElse(null);
        boolean requiresApproval = requiresApproval(spec);

        UUID changeId = UUID.randomUUID();
        ConfigRepository.ChangeRow written = transaction.execute(status -> {
            repository.insertChange(changeId, tenantId, spec.key(), baseVersion, previousJson,
                    requestedJson, cleanReason, requiresApproval, requestedBy, Instant.now());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("changeId", changeId.toString());
            detail.put("itemKey", spec.key());
            detail.put("baseVersion", baseVersion);
            detail.put("requiresApproval", requiresApproval);
            detail.put("reason", cleanReason);
            audit.recordOperator(tenantId, requestedBy, "CONFIG_CHANGE_REQUESTED",
                    AuditTrail.Outcome.ALLOWED, detail);

            ConfigRepository.ChangeRow change = change(tenantId, changeId);
            if (requiresApproval) {
                return change;
            }
            return apply(tenantId, change, requestedBy,
                    "applied immediately: " + ops.normalisedEnvironment()
                            + " does not require a second approver");
        });
        return view(written);
    }

    public ChangeView approve(long tenantId, UUID approverId, UUID changeId, String note) {
        ConfigRepository.ChangeRow change = change(tenantId, changeId);
        if (!"PENDING".equals(change.status())) {
            throw new OpsConflictException("CONFIG_CHANGE_CLOSED",
                    "this change has already been " + change.status().toLowerCase(Locale.ROOT));
        }
        if (!change.requiresApproval()) {
            throw new OpsConflictException("CONFIG_APPROVAL_NOT_REQUIRED",
                    "this change did not require a second approver");
        }
        if (change.requestedBy().equals(approverId)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("changeId", changeId.toString());
            detail.put("itemKey", change.itemKey());
            audit.recordOperator(tenantId, approverId, "CONFIG_SELF_APPROVAL_REFUSED",
                    AuditTrail.Outcome.DENIED, detail);
            throw new OpsConflictException("CONFIG_SELF_APPROVAL",
                    "the operator who raised a change cannot approve it");
        }
        ConfigRepository.ChangeRow decided = transaction.execute(status ->
                apply(tenantId, change, approverId, cleanNote(note)));
        return view(decided);
    }

    public ChangeView reject(long tenantId, UUID decidedBy, UUID changeId, String note) {
        ConfigRepository.ChangeRow change = change(tenantId, changeId);
        if (!"PENDING".equals(change.status())) {
            throw new OpsConflictException("CONFIG_CHANGE_CLOSED",
                    "this change has already been " + change.status().toLowerCase(Locale.ROOT));
        }
        String cleanNote = cleanNote(note);
        if (cleanNote == null || cleanNote.length() < 3) {
            throw new OpsBadRequestException("DECISION_NOTE_REQUIRED",
                    "a rejection needs a note explaining why");
        }
        ConfigRepository.ChangeRow rejected = transaction.execute(status -> {
            if (!repository.decide(changeId, tenantId, "REJECTED", decidedBy, cleanNote, null,
                    Instant.now())) {
                throw new OpsConflictException("CONFIG_CHANGE_CLOSED",
                        "this change has already been decided");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("changeId", changeId.toString());
            detail.put("itemKey", change.itemKey());
            detail.put("note", cleanNote);
            audit.recordOperator(tenantId, decidedBy, "CONFIG_CHANGE_REJECTED",
                    AuditTrail.Outcome.DENIED, detail);
            return change(tenantId, changeId);
        });
        return view(rejected);
    }

    public List<ChangeView> changes(long tenantId, String status, int limit) {
        String filter = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : status.trim().toUpperCase(Locale.ROOT);
        if (filter != null && !List.of("PENDING", "APPLIED", "REJECTED", "CANCELLED").contains(filter)) {
            throw new OpsBadRequestException("CONFIG_STATUS_NOT_SUPPORTED",
                    "status must be one of PENDING, APPLIED, REJECTED, CANCELLED, ALL");
        }
        return repository.changes(tenantId, filter, limit).stream().map(this::view).toList();
    }

    private ConfigRepository.ChangeRow apply(long tenantId, ConfigRepository.ChangeRow change,
                                             UUID actor, String note) {
        int currentVersion = repository.version(tenantId, change.itemKey());
        if (currentVersion != change.baseVersion()) {
            throw new OpsConflictException("CONFIG_VERSION_CONFLICT",
                    "the item changed after this request was raised; raise a new request");
        }
        int nextVersion = currentVersion + 1;
        if (!repository.upsertItem(tenantId, change.itemKey(), change.requestedJson(),
                currentVersion, nextVersion, actor)) {
            throw new OpsConflictException("CONFIG_VERSION_CONFLICT",
                    "the item changed after this request was raised; raise a new request");
        }
        if (!repository.decide(change.changeId(), tenantId, "APPLIED", actor, note,
                nextVersion, Instant.now())) {
            throw new OpsConflictException("CONFIG_CHANGE_CLOSED",
                    "this change has already been decided");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeId", change.changeId().toString());
        detail.put("itemKey", change.itemKey());
        detail.put("fromVersion", currentVersion);
        detail.put("toVersion", nextVersion);
        detail.put("previousValue", jsonOrNull(change.previousJson()));
        detail.put("newValue", jsonOrNull(change.requestedJson()));
        detail.put("reason", change.reason());
        detail.put("approver", actor.toString());
        audit.recordOperator(tenantId, actor, "CONFIG_CHANGE_APPLIED",
                AuditTrail.Outcome.ALLOWED, detail);
        return change(tenantId, change.changeId());
    }

    private ItemView view(long tenantId, ConfigRegistry.ItemSpec spec,
                          ConfigRepository.ItemRow stored) {
        Object value = stored == null ? spec.defaultValue() : parse(stored.valueJson(), spec);
        ChangeView pending = repository.pending(tenantId, spec.key()).map(this::view).orElse(null);
        return new ItemView(spec.key(), spec.label(), spec.description(), spec.type().name(),
                spec.signalling(), value, spec.defaultValue(),
                stored == null ? "DEFAULT" : "DATABASE",
                stored == null ? 0 : stored.version(),
                stored == null ? null : stored.updatedBy(),
                stored == null ? null : stored.updatedByName(),
                stored == null ? null : stored.updatedAt(),
                spec.type() == ConfigRegistry.ValueType.TEXT ? null : spec.min(),
                spec.type() == ConfigRegistry.ValueType.TEXT ? null : spec.max(),
                spec.type() == ConfigRegistry.ValueType.TEXT ? spec.maxLength() : null,
                pending);
    }

    private ChangeView view(ConfigRepository.ChangeRow row) {
        return new ChangeView(row.changeId(), row.itemKey(), row.baseVersion(),
                jsonOrNull(row.previousJson()), jsonOrNull(row.requestedJson()), row.reason(),
                row.status(), row.requiresApproval(), row.requestedBy(), row.requestedByName(),
                row.requestedAt(), row.decidedBy(), row.decidedByName(), row.decidedAt(),
                row.decisionNote(), row.appliedVersion(), row.appliedAt());
    }

    private ConfigRepository.ChangeRow change(long tenantId, UUID changeId) {
        return repository.change(tenantId, changeId)
                .orElseThrow(() -> new OpsNotFoundException("configuration change"));
    }

    private ConfigRegistry.ItemSpec spec(String key) {
        return registry.find(key)
                .orElseThrow(() -> new OpsNotFoundException("configuration item"));
    }

    private boolean secondApproverRequired() {
        String environment = ops.normalisedEnvironment();
        return "PRODUCTION".equals(environment) || "PROD".equals(environment);
    }

    private boolean requiresApproval(ConfigRegistry.ItemSpec spec) {
        return spec.signalling() && secondApproverRequired();
    }

    private Object normalise(ConfigRegistry.ItemSpec spec, JsonNode node) {
        switch (spec.type()) {
            case INTEGER -> {
                if (!node.isIntegralNumber()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_TYPE",
                            spec.key() + " is a whole number");
                }
                long value = node.asLong();
                if (value < (long) spec.min() || value > (long) spec.max()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_RANGE",
                            spec.key() + " must be between " + (long) spec.min() + " and "
                                    + (long) spec.max());
                }
                return value;
            }
            case DECIMAL -> {
                if (!node.isNumber()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_TYPE",
                            spec.key() + " is a decimal number");
                }
                double value = node.asDouble();
                if (value < spec.min() || value > spec.max()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_RANGE",
                            spec.key() + " must be between " + spec.min() + " and " + spec.max());
                }
                return value;
            }
            case TEXT -> {
                if (!node.isTextual()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_TYPE",
                            spec.key() + " is text");
                }
                String value = node.asText().trim();
                if (value.isEmpty() || value.length() > spec.maxLength()) {
                    throw new OpsBadRequestException("CONFIG_VALUE_RANGE",
                            spec.key() + " must be 1 to " + spec.maxLength() + " characters");
                }
                return value;
            }
            default -> throw new IllegalStateException("unhandled value type " + spec.type());
        }
    }

    private Object parse(String json, ConfigRegistry.ItemSpec spec) {
        try {
            JsonNode node = mapper.readTree(json);
            return switch (spec.type()) {
                case INTEGER -> node.asLong();
                case DECIMAL -> node.asDouble();
                case TEXT -> node.asText();
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored configuration for " + spec.key()
                    + " is not valid JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("configuration value is not serialisable", e);
        }
    }

    private JsonNode jsonOrNull(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored configuration is not valid JSON", e);
        }
    }

    private static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
