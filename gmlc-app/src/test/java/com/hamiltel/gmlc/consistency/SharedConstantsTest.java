package com.hamiltel.gmlc.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.diameter.Avp;
import com.hamiltel.gmlc.diameter.DiameterDictionary;
import com.hamiltel.gmlc.map.MapLcs;
import com.hamiltel.gmlc.ss7.Sccp;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol numbers are shared between the Java gateway and the Python core. A
 * wrong AVP or opcode is silently ignored or answered with the wrong result, so
 * both sides are checked against {@code fixtures/constants.json}, which names
 * the specification clause for every value.
 *
 * <p>The check runs in both directions: a fixture entry that disagrees with the
 * Java constant fails, and a new public numeric constant that has no fixture
 * entry fails too. Known Python-side divergences are recorded in the fixture's
 * {@code knownDivergences} and are enforced once the owning boundary is fixed.
 */
class SharedConstantsTest {

    private static final Map<String, Class<?>> OWNERS = Map.of(
            "DiameterDictionary", DiameterDictionary.class,
            "Avp", Avp.class,
            "MapLcs", MapLcs.class,
            "Sccp", Sccp.class);

    private static final List<String> SECTIONS = List.of(
            "diameter/vendorId",
            "diameter/applicationIds",
            "diameter/commands",
            "diameter/avps",
            "diameter/enumerations",
            "diameter/resultCodes",
            "map/opcodes",
            "map/ssn",
            "map/sccpMessageTypes");

    @Test
    void everyJavaConstantMatchesTheFixture() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<JsonNode, String> entry : entries()) {
            JsonNode node = entry.getKey();
            if (!node.hasNonNull("java")) {
                continue;
            }
            String field = node.get("java").asText();
            long expected = node.get("value").asLong();
            long actual;
            try {
                actual = ((Number) resolve(field)).longValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                problems.add(entry.getValue() + ": cannot resolve " + field + " (" + e + ")");
                continue;
            }
            if (actual != expected) {
                problems.add(entry.getValue() + ": " + field + " is " + actual
                        + " but fixtures/constants.json says " + expected
                        + " per " + node.path("spec").asText());
            }
        }
        assertThat(problems).as("fixture and Java constants disagree").isEmpty();
    }

    @Test
    void everyPublicNumericConstantIsInTheFixture() throws Exception {
        Set<String> listed = new TreeSet<>();
        for (Map.Entry<JsonNode, String> entry : entries()) {
            if (entry.getKey().hasNonNull("java")) {
                listed.add(entry.getKey().get("java").asText());
            }
        }

        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, Class<?>> owner : OWNERS.entrySet()) {
            for (Field field : owner.getValue().getFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !Modifier.isFinal(field.getModifiers())
                        || !isNumeric(field.getType())) {
                    continue;
                }
                String path = owner.getKey() + "." + field.getName();
                if (!listed.contains(path)) {
                    missing.add(path);
                }
            }
        }

        assertThat(missing)
                .as("public numeric constants with no fixtures/constants.json entry")
                .isEmpty();
    }

    @Test
    void everyEntryNamesItsSpecificationClause() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<JsonNode, String> entry : entries()) {
            String spec = entry.getKey().path("spec").asText("");
            if (spec.isBlank()) {
                problems.add(entry.getValue() + " has no spec clause");
            }
        }
        assertThat(problems).as("constants without provenance").isEmpty();
    }

    @Test
    void knownDivergencesAreDocumentedWithAWhere() throws Exception {
        JsonNode root = fixture();
        JsonNode divergences = root.get("knownDivergences");
        assertThat(divergences).as("knownDivergences must exist").isNotNull();
        List<String> problems = new ArrayList<>();
        for (JsonNode divergence : divergences) {
            for (String required : List.of("boundary", "what", "spec", "note")) {
                if (divergence.path(required).asText("").isBlank()) {
                    problems.add("known divergence is missing " + required + ": " + divergence);
                }
            }
            if (!divergence.has("pythonWhere") && !divergence.has("javaWhere")) {
                problems.add("known divergence names neither pythonWhere nor javaWhere: "
                        + divergence.path("what").asText());
            }
        }
        assertThat(problems).as("known divergences must be actionable").isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private static JsonNode fixture() throws Exception {
        return new ObjectMapper().readTree(Files.readString(RepoFiles.find("fixtures/constants.json")));
    }

    private static List<Map.Entry<JsonNode, String>> entries() throws Exception {
        JsonNode root = fixture();
        Map<JsonNode, String> entries = new LinkedHashMap<>();
        for (String section : SECTIONS) {
            JsonNode array = root.at("/" + section);
            assertThat(array.isArray())
                    .as("fixtures/constants.json section %s must be an array", section)
                    .isTrue();
            for (JsonNode entry : array) {
                entries.put(entry, section + "[" + entry.path("name").asText() + "]");
            }
        }
        return new ArrayList<>(entries.entrySet());
    }

    private static Object resolve(String path) throws ReflectiveOperationException {
        int dot = path.indexOf('.');
        assertThat(dot).as("constant path %s must be Owner.FIELD", path).isPositive();
        String owner = path.substring(0, dot);
        String field = path.substring(dot + 1);
        Class<?> type = OWNERS.get(owner);
        assertThat(type).as("unknown constant owner %s", owner).isNotNull();
        return type.getField(field).get(null);
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class
                || type == short.class || type == byte.class;
    }
}
