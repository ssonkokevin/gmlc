package com.hamiltel.gmlc.consistency;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stable problem codes in {@code ProblemCode} must be exactly the codes the
 * gateway can put in an RFC 7807 body. Two directions are checked: a code the
 * spec advertises but never raises is a lie to the console, and a code raised
 * but absent from the spec breaks the generated TypeScript union.
 *
 * <p>Codes are collected from the only paths that reach a problem response:
 * the four code-carrying exception constructors, the handlers that set
 * {@code code} explicitly, and the query-filter helpers that build
 * {@code <NAME>_NOT_SUPPORTED}. Internal signalling codes persisted in a CDR
 * ({@code MAP_*}, {@code SCCP_*}, {@code TCAP_*}, {@code M3UA_*}) are not HTTP
 * problem codes and are deliberately outside this set.
 */
class ProblemCodeDriftTest {

    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "new\\s+(?:OpsBadRequestException|OpsConflictException|RequestRejectedException"
                    + "|GeofenceShapeException)\\s*\\(\\s*(?:\"([A-Z0-9_]+)\"|([A-Za-z0-9_.]+))",
            Pattern.DOTALL);
    private static final Pattern CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern CODE_PROPERTY = Pattern.compile(
            "setProperty\\(\\s*\"code\"\\s*,\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern DYNAMIC_NAME = Pattern.compile(
            "upperOrNull\\(\\s*[\\w.]+\\s*,\\s*\"(\\w+)\"");
    private static final Set<String> PASS_THROUGH = Set.of("e.code", "name.toUpperCase");

    @Test
    void specCodesAreExactlyTheCodesTheGatewayRaises() throws Exception {
        Set<String> spec = specCodes();
        Set<String> code = raisedCodes();

        Set<String> onlyInSpec = new TreeSet<>(spec);
        onlyInSpec.removeAll(code);
        Set<String> onlyInCode = new TreeSet<>(code);
        onlyInCode.removeAll(spec);

        assertThat(onlyInSpec)
                .as("problem codes advertised in the spec but never raised")
                .isEmpty();
        assertThat(onlyInCode)
                .as("problem codes raised but missing from the spec enum")
                .isEmpty();
    }

    @Test
    void everyGeneratedCodeIsInTheSpec() throws Exception {
        Set<String> spec = specCodes();
        List<String> missing = new ArrayList<>();
        for (String name : dynamicNames()) {
            String code = name.toUpperCase(java.util.Locale.ROOT) + "_NOT_SUPPORTED";
            if (!spec.contains(code)) {
                missing.add(code);
            }
        }
        assertThat(missing).as("query filters whose *_NOT_SUPPORTED code is absent").isEmpty();
    }

    @Test
    void codesAreUniqueAndSorted() throws Exception {
        List<?> listed = (List<?>) SpecReader.schema(SpecReader.load(), "ProblemCode").get("enum");
        List<String> codes = listed.stream().map(Object::toString).toList();
        assertThat(new TreeSet<>(codes)).as("duplicate problem codes").hasSize(codes.size());
        assertThat(codes).as("keep the enum generated in sorted order").isSorted();
        assertThat(codes).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> specCodes() throws Exception {
        Map<String, Object> schema = SpecReader.schema(SpecReader.load(), "ProblemCode");
        List<Object> values = (List<Object>) schema.get("enum");
        Set<String> codes = new LinkedHashSet<>();
        for (Object value : values) {
            codes.add(value.toString());
        }
        return codes;
    }

    private static Set<String> raisedCodes() throws Exception {
        Set<String> codes = new TreeSet<>(dynamicCodes());
        Map<String, Set<String>> constants = new LinkedHashMap<>();
        List<Path> sources = sources();
        for (Path source : sources) {
            Matcher matcher = CONSTANT.matcher(Files.readString(source));
            while (matcher.find()) {
                constants.computeIfAbsent(matcher.group(1), key -> new TreeSet<>())
                        .add(matcher.group(2));
            }
        }

        List<String> unresolved = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            Matcher matcher = CONSTRUCTOR.matcher(text);
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    codes.add(matcher.group(1));
                    continue;
                }
                String reference = matcher.group(2);
                if (PASS_THROUGH.contains(reference)) {
                    continue;
                }
                String simple = reference.substring(reference.lastIndexOf('.') + 1);
                Set<String> values = constants.get(simple);
                if (values != null && values.size() == 1) {
                    codes.add(values.iterator().next());
                } else {
                    unresolved.add(reference + " in " + source.getFileName());
                }
            }
            Matcher properties = CODE_PROPERTY.matcher(text);
            while (properties.find()) {
                codes.add(properties.group(1));
            }
        }
        assertThat(unresolved)
                .as("a code is passed through an unresolved expression; add it to a literal "
                        + "or teach this test how to resolve it")
                .isEmpty();
        return codes;
    }

    private static Set<String> dynamicCodes() throws Exception {
        Set<String> codes = new LinkedHashSet<>();
        for (String name : dynamicNames()) {
            codes.add(name.toUpperCase(java.util.Locale.ROOT) + "_NOT_SUPPORTED");
        }
        return codes;
    }

    private static Set<String> dynamicNames() throws Exception {
        Set<String> names = new LinkedHashSet<>();
        for (Path source : sources()) {
            Matcher matcher = DYNAMIC_NAME.matcher(Files.readString(source));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static List<Path> sources() throws Exception {
        Path main = SpecReader.path().getParent().getParent().resolve("gmlc-app/src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
