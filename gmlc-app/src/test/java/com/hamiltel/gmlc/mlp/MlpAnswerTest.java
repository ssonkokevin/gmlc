package com.hamiltel.gmlc.mlp;

import com.hamiltel.gmlc.location.LocationResultRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MLP answer carries the same honesty envelope as Le: a bounded circle,
 * the confidence in it and whether the requested accuracy was met. Coordinates
 * are written in the degrees-minutes-seconds form the standard requires.
 */
class MlpAnswerTest {

    private static final Instant NOW = Instant.parse("2026-11-22T09:00:00Z");

    @Test
    void coordinatesAreDegreesMinutesSecondsWithHemisphere() {
        assertThat(MlpAnswer.coordinate(new BigDecimal("0.347600"), 'N', 'S'))
                .isEqualTo("0 20 51.360N");
        assertThat(MlpAnswer.coordinate(new BigDecimal("32.582500"), 'E', 'W'))
                .isEqualTo("32 34 57.000E");
        assertThat(MlpAnswer.coordinate(new BigDecimal("-0.000100"), 'N', 'S'))
                .isEqualTo("0 00 00.360S");
        assertThat(MlpAnswer.coordinate(new BigDecimal("-1.999999999"), 'N', 'S'))
                .isEqualTo("2 00 00.000S");
    }

    @Test
    void aPositionCarriesItsRadiusConfidenceAndQualityFlag() {
        MlpRequest.MlpSubject subject = new MlpRequest.MlpSubject("MSISDN", "256700000001");

        String met = MlpAnswer.positions("slia", "3.0.0",
                List.of(MlpAnswer.Position.of(subject, result(350, 70, 1000))), NOW);
        assertThat(met).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .contains("\"MLP_SVC_RESULT_320.DTD\"")
                .contains("<slia ver=\"3.0.0\">")
                .contains("<msid type=\"MSISDN\" enc=\"ASC\">256700000001</msid>")
                .contains("<time utc_off=\"+0000\">20261122090000</time>")
                .contains("<X>0 20 51.360N</X>")
                .contains("<Y>32 34 57.000E</Y>")
                .contains("<radius>350</radius>")
                .contains("<lev_conf>70</lev_conf>")
                .doesNotContain("qos_not_met")
                .doesNotContain("<poserr>");

        String notMet = MlpAnswer.positions("slia", "3.0.0",
                List.of(MlpAnswer.Position.of(subject, result(350, 70, 100))), NOW);
        assertThat(notMet).contains("<qos_not_met/>");
    }

    @Test
    void aRefusalUsesTheStandardResultAndEscapesItsReason() {
        String xml = MlpAnswer.refusal("tlra", "3.2.0", 108,
                "the tlrr service is not implemented <here> & now", NOW);

        assertThat(xml).contains("<tlra ver=\"3.2.0\">")
                .contains("<result resid=\"108\">SERVICE NOT SUPPORTED</result>")
                .contains("<add_info>the tlrr service is not implemented &lt;here&gt; &amp; now</add_info>")
                .endsWith("</tlra></svc_result>");
    }

    private static LocationResultRepository.StoredResult result(int uncertainty, int confidence,
                                                               int requested) {
        return new LocationResultRepository.StoredResult(
                UUID.randomUUID(), UUID.randomUUID(), "CELL_ID", List.of("MAP_ATI"), "CIRCLE",
                new BigDecimal("0.347600"), new BigDecimal("32.582500"), uncertainty, confidence,
                0, requested, requested >= uncertainty, "001010000000001", "SMLC", "MME", NOW);
    }
}
