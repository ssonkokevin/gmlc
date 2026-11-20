package com.hamiltel.gmlc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural rules for a tenant capability profile, mirroring
 * docs/location/capability-profile.schema.json in the core repository.
 *
 * <p>The rules exist so the API cannot promise an accuracy the network cannot
 * deliver: every declared method needs a build status, and geometry-based
 * methods need at least two transmission points to be meaningful.
 */
@Component
public class CapabilityProfileValidator {

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern MCC = Pattern.compile("\\d{3}");
    private static final Pattern MNC = Pattern.compile("\\d{2,3}");

    private static final Set<String> RATS = Set.of("GSM", "UMTS", "LTE", "NR");
    private static final Set<String> SERVING_NODES =
            Set.of("AMF", "MME", "MSC/SGSN", "MSC", "SGSN");
    private static final Set<String> ENGINES = Set.of("LMF", "E_SMLC", "SMLC");
    private static final Set<String> METHODS =
            Set.of("CELL_ID", "E_CID", "A_GNSS", "OTDOA", "RTT", "DL_TDOA", "UL_TDOA");
    private static final Set<String> GEOMETRY = Set.of("OTDOA", "RTT", "DL_TDOA", "UL_TDOA");
    private static final Set<String> STATUSES = Set.of("BUILT", "PLANNED", "NOT_AVAILABLE");
    private static final Set<String> RAT_KEYS = Set.of(
            "serving_node", "engine", "methods", "method_status",
            "lpp", "lppa", "nrppa", "prs_configured", "trp_count", "notes");

    public List<String> validate(JsonNode profile) {
        List<String> errors = new ArrayList<>();
        if (profile == null || !profile.isObject()) {
            errors.add("profile must be a JSON object");
            return errors;
        }

        JsonNode tenantId = profile.get("tenant_id");
        if (tenantId == null || !tenantId.isIntegralNumber() || tenantId.asLong() < 1) {
            errors.add("tenant_id must be an integer >= 1");
        }
        JsonNode version = profile.get("version");
        if (version == null || !version.isTextual() || !SEMVER.matcher(version.asText()).matches()) {
            errors.add("version must be semver (x.y.z)");
        }

        requireBoolean(profile, "ati_allowed", errors);
        requireBoolean(profile, "psi_allowed", errors);
        requireBoolean(profile, "active_location_paging", errors);
        if (profile.has("emergency_override")) {
            requireBoolean(profile, "emergency_override", errors);
        }

        JsonNode accuracy = profile.get("max_accuracy_granted_m");
        if (accuracy != null && (!accuracy.isIntegralNumber() || accuracy.asLong() < 1)) {
            errors.add("max_accuracy_granted_m must be a positive integer");
        }

        JsonNode plmns = profile.get("plmns");
        if (plmns == null || !plmns.isArray() || plmns.isEmpty()) {
            errors.add("plmns must be a non-empty array");
        } else {
            for (JsonNode plmn : plmns) {
                if (!plmn.isObject()) {
                    errors.add("each plmn must be an object");
                    continue;
                }
                JsonNode mcc = plmn.get("mcc");
                JsonNode mnc = plmn.get("mnc");
                if (mcc == null || !mcc.isTextual() || !MCC.matcher(mcc.asText()).matches()) {
                    errors.add("plmn mcc must be three digits");
                }
                if (mnc == null || !mnc.isTextual()
                        || !MNC.matcher(mnc.asText()).matches()) {
                    errors.add("plmn mnc must be two or three digits");
                }
            }
        }

        JsonNode rat = profile.get("rat");
        if (rat == null || !rat.isObject()) {
            errors.add("rat must be an object");
            return errors;
        }
        for (String name : RATS) {
            JsonNode entry = rat.get(name);
            if (entry == null) {
                errors.add("rat." + name + " is missing; every RAT must be stated");
            } else {
                validateRat(name, entry, errors);
            }
        }
        for (Iterator<String> it = rat.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!RATS.contains(key)) {
                errors.add("rat." + key + " is not a known RAT");
            }
        }
        return errors;
    }

    private void validateRat(String name, JsonNode entry, List<String> errors) {
        String p = "rat." + name + ".";
        if (!entry.isObject()) {
            errors.add(p + "must be an object");
            return;
        }
        for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!RAT_KEYS.contains(key)) {
                errors.add(p + key + " is not allowed");
            }
        }

        JsonNode serving = entry.get("serving_node");
        if (serving == null || !serving.isTextual()
                || !SERVING_NODES.contains(serving.asText())) {
            errors.add(p + "serving_node is invalid");
        }

        JsonNode engine = entry.get("engine");
        boolean hasEngine = engine != null && !engine.isNull();
        if (hasEngine && !(engine.isTextual() && ENGINES.contains(engine.asText()))) {
            errors.add(p + "engine must be null or one of " + ENGINES);
        }

        Set<String> methods = new LinkedHashSet<>();
        JsonNode methodsNode = entry.get("methods");
        if (methodsNode == null || !methodsNode.isArray() || methodsNode.isEmpty()) {
            errors.add(p + "methods must be a non-empty array");
        } else {
            for (JsonNode method : methodsNode) {
                if (!method.isTextual() || !METHODS.contains(method.asText())) {
                    errors.add(p + "unknown method: " + method);
                } else if (!methods.add(method.asText())) {
                    errors.add(p + "duplicate method: " + method.asText());
                }
            }
        }

        JsonNode statuses = entry.get("method_status");
        if (statuses == null || !statuses.isObject()) {
            errors.add(p + "method_status is required");
        } else {
            Set<String> declared = new HashSet<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = statuses.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> status = it.next();
                declared.add(status.getKey());
                if (!status.getValue().isTextual()
                        || !STATUSES.contains(status.getValue().asText())) {
                    errors.add(p + "method_status." + status.getKey()
                            + " must be one of " + STATUSES);
                }
                if (!methods.contains(status.getKey())) {
                    errors.add(p + "method_status names an undeclared method: "
                            + status.getKey());
                }
            }
            if (!declared.containsAll(methods)) {
                errors.add(p + "every declared method needs a build status");
            }
        }

        if (hasEngine && !methods.contains("CELL_ID")) {
            errors.add(p + "a RAT with an engine must still list CELL_ID as the fallback");
        }

        for (String method : methods) {
            if (!GEOMETRY.contains(method)) {
                continue;
            }
            JsonNode trp = entry.get("trp_count");
            if (trp == null || !trp.isIntegralNumber() || trp.asInt() < 2) {
                errors.add(p + method + " needs trp_count >= 2; one point cannot fix a position");
            }
        }

        for (String flag : List.of("lpp", "lppa", "nrppa", "prs_configured")) {
            if (entry.has(flag)) {
                requireBoolean(entry, flag, errors, p);
            }
        }
        if (entry.has("trp_count")) {
            JsonNode trp = entry.get("trp_count");
            if (!trp.isIntegralNumber() || trp.asInt() < 0) {
                errors.add(p + "trp_count must be a non-negative integer");
            }
        }
        if (entry.has("notes")) {
            JsonNode notes = entry.get("notes");
            if (!notes.isTextual() || notes.asText().length() > 1000) {
                errors.add(p + "notes must be text of at most 1000 characters");
            }
        }
    }

    private boolean requireBoolean(JsonNode node, String field, List<String> errors) {
        return requireBoolean(node, field, errors, "");
    }

    private boolean requireBoolean(JsonNode node, String field, List<String> errors,
                                   String prefix) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            errors.add(prefix + field + " must be a boolean");
            return false;
        }
        return value.asBoolean();
    }
}
