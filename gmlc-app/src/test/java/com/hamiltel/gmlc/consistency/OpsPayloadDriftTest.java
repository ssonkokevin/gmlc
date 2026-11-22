package com.hamiltel.gmlc.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response schema is only useful if it describes the record Spring actually
 * serialises. For every controller method whose return type is a record, the
 * property names in the spec must equal the record components - no field left
 * untyped and no schema field that the server never sends {@code null} for.
 *
 * <p>Methods returning {@code JsonNode} are outside this check (the Le API
 * result body is built per shape); {@link ApiSpecDriftTest} still covers their
 * routes and parameters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsPayloadDriftTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Test
    void everyRecordResponseIsDescribedFieldForField() throws Exception {
        Map<String, Object> spec = SpecReader.load();
        Map<String, Object> operations = SpecReader.operationsByRoute(spec);
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (isFrameworkHandler(handler.getBeanType())) {
                continue;
            }
            Method method = handler.getMethod();
            Class<?> body = responseRecord(method);
            if (body == null) {
                continue;
            }

            for (String verb : verbs(entry.getKey())) {
                for (String path : paths(entry.getKey())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> operation = (Map<String, Object>) operations.get(verb + " " + path);
                    if (operation == null) {
                        continue;
                    }
                    Map<String, Object> schema = successSchema(operation);
                    if (schema == null) {
                        problems.add(verb + " " + path + " returns " + body.getSimpleName()
                                + " but declares no success body");
                        continue;
                    }
                    Set<String> inSpec = SpecReader.properties(spec, schema);
                    Set<String> inRecord = new TreeSet<>();
                    for (RecordComponent component : body.getRecordComponents()) {
                        inRecord.add(component.getName());
                    }
                    if (!inSpec.equals(inRecord)) {
                        Set<String> specOnly = new TreeSet<>(inSpec);
                        specOnly.removeAll(inRecord);
                        Set<String> recordOnly = new TreeSet<>(inRecord);
                        recordOnly.removeAll(inSpec);
                        problems.add(verb + " " + path + " -> " + body.getName()
                                + " specOnly=" + specOnly + " recordOnly=" + recordOnly);
                    }
                    checked++;
                }
            }
        }

        assertThat(problems).as("response schema drift").isEmpty();
        assertThat(checked).as("records compared; the reflection scan must not silently match nothing")
                .isGreaterThan(40);
    }

    private static boolean isFrameworkHandler(Class<?> beanType) {
        String name = beanType.getName();
        return name.startsWith("org.springframework.boot.web.servlet.error.")
                || name.startsWith("org.springframework.boot.autoconfigure.web.servlet.error.");
    }

    private static Map<String, Object> successSchema(Map<String, Object> operation) {
        for (String status : List.of("200", "201", "202")) {
            Map<String, Object> schema = SpecReader.responseSchema(operation, status);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private static Set<String> verbs(RequestMappingInfo info) {
        Set<String> verbs = new TreeSet<>();
        info.getMethodsCondition().getMethods().forEach(method -> verbs.add(method.name()));
        return verbs;
    }

    private static Set<String> paths(RequestMappingInfo info) {
        Set<String> paths = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatternValues()
                    .forEach(path -> paths.add(SpecReader.normalise(path)));
        } else if (info.getPatternsCondition() != null) {
            info.getPatternsCondition().getPatterns()
                    .forEach(path -> paths.add(SpecReader.normalise(path)));
        }
        return paths;
    }

    /** The record class in the response body, or null when there is no typed body. */
    private static Class<?> responseRecord(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == ResponseEntity.class) {
            type = parameterized.getActualTypeArguments()[0];
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (raw == List.class) {
                type = parameterized.getActualTypeArguments()[0];
            } else if (raw == Optional.class) {
                type = parameterized.getActualTypeArguments()[0];
            }
        }
        if (!(type instanceof Class<?> clazz) || JsonNode.class.isAssignableFrom(clazz)) {
            return null;
        }
        if (clazz == Void.class || clazz == Object.class || clazz == String.class
                || clazz.isPrimitive()) {
            return null;
        }
        return clazz.isRecord() ? clazz : null;
    }
}
