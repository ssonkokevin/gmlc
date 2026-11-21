package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment chooses one signalling face; the context holds one locator,
 * not two competing beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "gmlc.location.transport=MAP")
class LocationSelectionTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private LocationLocator locator;

    @Test
    void theConfiguredFaceIsTheOnlyLocator() {
        assertThat(locator).isInstanceOf(MapCellLocator.class);
    }
}
