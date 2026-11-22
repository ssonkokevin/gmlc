package com.hamiltel.gmlc.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProfileValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapabilityProfileValidator validator = new CapabilityProfileValidator();
    private JsonNode profile;

    @BeforeEach
    void loadBundledProfile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(
                "/location/capability-profile.tenant-1.json")) {
            assertThat(in).as("bundled profile is on the classpath").isNotNull();
            profile = mapper.readTree(in);
        }
    }

    @Test
    void bundledProfileIsValid() {
        assertThat(validator.validate(profile)).isEmpty();
    }

    @Test
    void aDeclaredMethodWithoutABuildStatusIsRejected() {
        ((ObjectNode) profile.at("/rat/LTE/method_status")).remove("A_GNSS");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("every declared method needs a build status"));
    }

    @Test
    void anUndeclaredStatusIsRejected() {
        ((ObjectNode) profile.at("/rat/LTE/method_status")).put("OTDOA", "BUILT");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("undeclared method"));
    }

    @Test
    void aRatWithAnEngineMustKeepCellIdAsTheFallback() {
        ((ObjectNode) profile.at("/rat/LTE")).putNull("engine");
        ((ArrayNode) profile.at("/rat/LTE/methods")).removeAll();
        ((ArrayNode) profile.at("/rat/LTE/methods")).add("A_GNSS");
        ((ObjectNode) profile.at("/rat/LTE/method_status")).removeAll();
        ((ObjectNode) profile.at("/rat/LTE/method_status")).put("A_GNSS", "PLANNED");

        assertThat(validator.validate(profile)).isEmpty();

        ((ObjectNode) profile.at("/rat/LTE")).put("engine", "E_SMLC");
        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("CELL_ID as the fallback"));
    }

    @Test
    void geometryNeedsAtLeastTwoTransmissionPoints() {
        ((ArrayNode) profile.at("/rat/LTE/methods")).add("OTDOA");
        ((ObjectNode) profile.at("/rat/LTE/method_status")).put("OTDOA", "PLANNED");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("trp_count >= 2"));
    }

    @Test
    void anUnknownMethodIsRejected() {
        ((ArrayNode) profile.at("/rat/LTE/methods")).add("WIFI_RTT");
        ((ObjectNode) profile.at("/rat/LTE/method_status")).put("WIFI_RTT", "PLANNED");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("unknown method"));
    }

    @Test
    void everyRatMustBeStated() {
        ((ObjectNode) profile.get("rat")).remove("GSM");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("rat.GSM is missing"));
    }

    @Test
    void plmnFormatIsEnforced() {
        ((ObjectNode) profile.get("plmns").get(0)).put("mcc", "01");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("mcc must be three digits"));
    }

    @Test
    void theBundledProfileSaysSuplIsNotImplemented() {
        assertThat(profile.at("/supl/implemented").asBoolean()).isFalse();
    }

    @Test
    void aProfileWithoutASuplStatementIsRejected() {
        ((ObjectNode) profile).remove("supl");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("supl must be an object"));
    }

    @Test
    void aNonBooleanSuplFlagIsRejected() {
        ((ObjectNode) profile.get("supl")).put("implemented", "no");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("supl.implemented must be a boolean"));
    }

    @Test
    void anUnknownSuplKeyIsRejected() {
        ((ObjectNode) profile.get("supl")).put("server", "slp.example");

        assertThat(validator.validate(profile))
                .anyMatch(error -> error.contains("supl.server is not allowed"));
    }

    @Test
    void aNonObjectIsRejectedWithoutThrowing() {
        assertThat(validator.validate(null)).contains("profile must be a JSON object");
    }
}
