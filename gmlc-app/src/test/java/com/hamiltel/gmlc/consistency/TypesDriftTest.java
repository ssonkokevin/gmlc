package com.hamiltel.gmlc.consistency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console must not hand-maintain response types. {@code types.ts} is
 * generated from {@code api/openapi.yaml}; this test fails when the two differ.
 * Regenerate with {@code -Dgmlc.write.types=true}.
 */
class TypesDriftTest {

    @Test
    void consoleTypesAreGeneratedFromTheSpec() throws Exception {
        String generated = TypeScriptGenerator.render(SpecReader.load())
                .replace("\r\n", "\n");
        Path types = SpecReader.path().getParent().getParent()
                .resolve("gmlc-app/console/src/types.ts");

        if (Boolean.getBoolean("gmlc.write.types")) {
            Files.writeString(types, generated, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(types, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("console/src/types.ts is stale; regenerate from api/openapi.yaml with "
                        + "-Dgmlc.write.types=true")
                .isEqualTo(generated);
    }
}
