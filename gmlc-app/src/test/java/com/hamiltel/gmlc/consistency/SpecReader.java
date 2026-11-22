package com.hamiltel.gmlc.consistency;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads {@code api/openapi.yaml} for the consistency tests. */
final class SpecReader {

    private static final Set<String> VERBS = Set.of("get", "put", "post", "delete",
            "options", "head", "patch", "trace");

    private SpecReader() {
    }

    static Path path() {
        return RepoFiles.find("api/openapi.yaml");
    }

    static Map<String, Object> load() throws Exception {
        try (InputStream in = Files.newInputStream(path())) {
            Map<String, Object> spec = new Yaml().load(in);
            if (spec == null) {
                throw new IllegalStateException("api/openapi.yaml is empty");
            }
            return spec;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(Map<String, Object> spec, String name) {
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        if (schema == null) {
            throw new IllegalStateException("no schema " + name + " in api/openapi.yaml");
        }
        return schema;
    }

    /** Keys are {@code VERB /path}; each value is one operation object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> operationsByRoute(Map<String, Object> spec) {
        Map<String, Object> operations = new LinkedHashMap<>();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
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
    static Map<String, Object> operationsById(Map<String, Object> spec) {
        Map<String, Object> byId = new LinkedHashMap<>();
        for (Object operation : operationsByRoute(spec).values()) {
            Map<String, Object> op = (Map<String, Object>) operation;
            Object id = op.get("operationId");
            if (id != null) {
                byId.put(id.toString(), op);
            }
        }
        return byId;
    }

    /** The JSON schema attached to a status code, or null when the response has no body. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> responseSchema(Map<String, Object> operation, String status) {
        Object responses = operation.get("responses");
        if (!(responses instanceof Map)) {
            return null;
        }
        Object response = ((Map<String, Object>) responses).get(status);
        if (!(response instanceof Map)) {
            return null;
        }
        Object content = ((Map<String, Object>) response).get("content");
        if (!(content instanceof Map)) {
            return null;
        }
        Object json = ((Map<String, Object>) content).get("application/json");
        if (!(json instanceof Map)) {
            return null;
        }
        Object schema = ((Map<String, Object>) json).get("schema");
        return schema instanceof Map ? (Map<String, Object>) schema : null;
    }

    /**
     * Property names of a schema, following {@code $ref}, {@code allOf} and
     * array items. Only the top-level object is flattened; nested records have
     * their own named schema and their own test coverage.
     */
    @SuppressWarnings("unchecked")
    static Set<String> properties(Map<String, Object> spec, Map<String, Object> schema) {
        Set<String> names = new LinkedHashSet<>();
        collect(spec, schema, names, new LinkedHashSet<>());
        return names;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> spec, Map<String, Object> schema,
                                Set<String> names, Set<String> visited) {
        Object ref = schema.get("$ref");
        if (ref != null) {
            String name = ref.toString().substring(ref.toString().lastIndexOf('/') + 1);
            if (visited.add(name)) {
                collect(spec, schema(spec, name), names, visited);
            }
            return;
        }
        Object allOf = schema.get("allOf");
        if (allOf instanceof List) {
            for (Object part : (List<Object>) allOf) {
                collect(spec, (Map<String, Object>) part, names, visited);
            }
        }
        Object items = schema.get("items");
        if (items instanceof Map) {
            collect(spec, (Map<String, Object>) items, names, visited);
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            names.addAll(((Map<String, Object>) properties).keySet());
        }
    }

    static String normalise(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
