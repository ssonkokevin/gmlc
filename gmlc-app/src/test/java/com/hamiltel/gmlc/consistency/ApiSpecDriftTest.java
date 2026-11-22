package com.hamiltel.gmlc.consistency;

import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Parameter;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed contract in {@code api/openapi.yaml} must describe exactly the
 * routes Spring serves. Both directions are checked, so a new controller method
 * without a spec entry fails, and a spec path with no controller fails too.
 *
 * <p>Spring Boot's auto-configured {@code BasicErrorController} is not part of
 * this API and is excluded by bean type. Static resources ({@code /}, {@code /dashboard.js},
 * {@code /console/**}) are served by the resource handler, not by a controller
 * mapping, and are therefore outside this comparison; the audit records them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSpecDriftTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    private static final Set<String> VERBS = Set.of("get", "put", "post", "delete",
            "options", "head", "patch", "trace");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Test
    void everyControllerMappingIsInTheSpecAndViceVersa() throws Exception {
        Map<String, Object> spec = loadSpec();
        Set<String> declared = specOperations(spec).keySet();
        Set<String> served = servedOperations();

        Set<String> missingFromSpec = new TreeSet<>(served);
        missingFromSpec.removeAll(declared);

        Set<String> missingFromCode = new TreeSet<>(declared);
        missingFromCode.removeAll(served);

        assertThat(missingFromSpec)
                .as("routes served by Spring but absent from api/openapi.yaml")
                .isEmpty();
        assertThat(missingFromCode)
                .as("paths in api/openapi.yaml with no controller mapping")
                .isEmpty();
    }

    @Test
    void everyOperationHasAUniqueIdAndATag() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> operations = specOperations(spec);
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : operations.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) entry.getValue();
            Object id = op.get("operationId");
            assertThat(id).as("%s must declare an operationId", entry.getKey()).isNotNull();
            assertThat(ids.add(id.toString()))
                    .as("operationId %s is not unique", id)
                    .isTrue();
            Object tags = op.get("tags");
            assertThat(tags).as("%s must declare a tag", entry.getKey()).isInstanceOf(List.class);
            assertThat((List<?>) tags).as("%s must declare a tag", entry.getKey()).isNotEmpty();
        }
    }

    @Test
    void queryParametersMatchTheSpecExactly() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> operations = specOperations(spec);
        List<String> problems = new ArrayList<>();

        for (Mapping mapping : servedMappings()) {
            for (String path : mapping.paths()) {
                Set<String> inCode = requestParameters(mapping.handler().getMethod().getParameters());
                for (String verb : mapping.verbs()) {
                    Map<String, Object> op = operation(operations, verb, path);
                    if (op == null) {
                        problems.add(verb + " " + path + " has no spec operation");
                        continue;
                    }
                    Set<String> inSpec = parameterNames(spec, op, "query");
                    if (!inCode.equals(inSpec)) {
                        problems.add(verb + " " + path + " code=" + inCode + " spec=" + inSpec);
                    }
                }
            }
        }

        assertThat(problems).as("query parameter drift").isEmpty();
    }

    @Test
    void headerParametersUsedByCodeAreDeclared() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> operations = specOperations(spec);
        List<String> problems = new ArrayList<>();

        for (Mapping mapping : servedMappings()) {
            Set<String> inCode = headerParameters(mapping.handler().getMethod().getParameters());
            for (String path : mapping.paths()) {
                for (String verb : mapping.verbs()) {
                    Map<String, Object> op = operation(operations, verb, path);
                    if (op == null) {
                        continue;
                    }
                    Set<String> inSpec = parameterNames(spec, op, "header");
                    for (String name : inCode) {
                        if (!inSpec.contains(name)) {
                            problems.add(verb + " " + path + " header " + name
                                    + " is used by code but not declared (declared=" + inSpec + ")");
                        }
                    }
                }
            }
        }

        assertThat(problems).as("header parameter drift").isEmpty();
    }

    @Test
    void pathVariablesMatchTheTemplate() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> operations = specOperations(spec);
        List<String> problems = new ArrayList<>();

        for (Mapping mapping : servedMappings()) {
            Set<String> inCode = pathVariables(mapping.handler().getMethod().getParameters());
            for (String path : mapping.paths()) {
                for (String verb : mapping.verbs()) {
                    if (operation(operations, verb, path) == null) {
                        continue;
                    }
                    Set<String> inTemplate = templateVariables(path);
                    if (!inCode.equals(inTemplate)) {
                        problems.add(verb + " " + path + " code=" + inCode
                                + " template=" + inTemplate);
                    }
                }
            }
        }

        assertThat(problems).as("path variable drift").isEmpty();
    }

    // --- served side -------------------------------------------------------

    private record Mapping(HandlerMethod handler, Set<String> paths, Set<String> verbs) {
    }

    /** Spring Boot's {@code BasicErrorController} lives in the auto-configuration package. */
    private static boolean isFrameworkHandler(Class<?> beanType) {
        String name = beanType.getName();
        return name.startsWith("org.springframework.boot.web.servlet.error.")
                || name.startsWith("org.springframework.boot.autoconfigure.web.servlet.error.");
    }

    private List<Mapping> servedMappings() {
        List<Mapping> served = new ArrayList<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (isFrameworkHandler(handler.getBeanType())) {
                continue;
            }
            Set<String> verbs = new TreeSet<>();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                verbs.add(method.name());
            }
            assertThat(verbs)
                    .as("%s has no HTTP method condition; the contract cannot describe it",
                            handler.getMethod())
                    .isNotEmpty();
            served.add(new Mapping(handler, paths(info), verbs));
        }
        return served;
    }

    private Set<String> servedOperations() {
        Set<String> operations = new TreeSet<>();
        for (Mapping mapping : servedMappings()) {
            for (String path : mapping.paths()) {
                for (String verb : mapping.verbs()) {
                    operations.add(verb + " " + path);
                }
            }
        }
        return operations;
    }

    private static Set<String> paths(RequestMappingInfo info) {
        Set<String> paths = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            paths.addAll(info.getPathPatternsCondition().getPatternValues());
        } else if (info.getPatternsCondition() != null) {
            paths.addAll(info.getPatternsCondition().getPatterns());
        }
        Set<String> normalised = new TreeSet<>();
        for (String path : paths) {
            normalised.add(normalise(path));
        }
        return normalised;
    }

    // --- spec side ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSpec() throws Exception {
        Path path = RepoFiles.find("api/openapi.yaml");
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> spec = new Yaml().load(in);
            assertThat(spec).as("api/openapi.yaml must be an object").isNotNull();
            return spec;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOperations(Map<String, Object> spec) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> operations = new LinkedHashMap<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> opEntry : item.entrySet()) {
                if (VERBS.contains(opEntry.getKey().toLowerCase())) {
                    operations.put(opEntry.getKey().toUpperCase() + " "
                            + normalise(pathEntry.getKey()), opEntry.getValue());
                }
            }
        }
        return operations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> operations,
                                                 String verb, String path) {
        Object op = operations.get(verb + " " + path);
        return op == null ? null : (Map<String, Object>) op;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> parameterNames(Map<String, Object> spec,
                                              Map<String, Object> operation, String in) {
        Set<String> names = new TreeSet<>();
        Object parameters = operation.get("parameters");
        if (parameters == null) {
            return names;
        }
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> declared = (Map<String, Object>) components.get("parameters");
        for (Object raw : (List<Object>) parameters) {
            Map<String, Object> parameter = (Map<String, Object>) raw;
            Object ref = parameter.get("$ref");
            if (ref != null) {
                String name = ref.toString().substring(ref.toString().lastIndexOf('/') + 1);
                parameter = (Map<String, Object>) declared.get(name);
                assertThat(parameter).as("unresolved parameter reference %s", ref).isNotNull();
            }
            if (in.equals(parameter.get("in"))) {
                names.add(String.valueOf(parameter.get("name")));
            }
        }
        return names;
    }

    // --- code side ---------------------------------------------------------

    private static Set<String> requestParameters(Parameter[] parameters) {
        Set<String> names = new TreeSet<>();
        for (Parameter parameter : parameters) {
            RequestParam annotation = parameter.getAnnotation(RequestParam.class);
            if (annotation != null) {
                names.add(name(annotation.name(), annotation.value(), parameter));
            }
        }
        return names;
    }

    private static Set<String> headerParameters(Parameter[] parameters) {
        Set<String> names = new TreeSet<>();
        for (Parameter parameter : parameters) {
            RequestHeader annotation = parameter.getAnnotation(RequestHeader.class);
            if (annotation != null) {
                names.add(name(annotation.name(), annotation.value(), parameter));
            }
        }
        return names;
    }

    private static Set<String> pathVariables(Parameter[] parameters) {
        Set<String> names = new TreeSet<>();
        for (Parameter parameter : parameters) {
            PathVariable annotation = parameter.getAnnotation(PathVariable.class);
            if (annotation != null) {
                names.add(name(annotation.name(), annotation.value(), parameter));
            }
        }
        return names;
    }

    private static String name(String name, String value, Parameter parameter) {
        if (!name.isBlank()) {
            return name;
        }
        if (!value.isBlank()) {
            return value;
        }
        return parameter.getName();
    }

    private static Set<String> templateVariables(String path) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PATH_VARIABLE.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String normalise(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
