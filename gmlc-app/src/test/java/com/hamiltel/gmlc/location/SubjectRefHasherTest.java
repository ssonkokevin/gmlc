package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.config.GmlcProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pseudonym is the only way a subscriber appears outside the request row.
 * These vectors are fixed so a refactor cannot silently change how existing
 * consent rows are matched.
 */
class SubjectRefHasherTest {

    private static final String IMSI = "001010000000001";

    private static GmlcProperties properties(String pepper) {
        return new GmlcProperties(1, "classpath:profile.json", "0", null, pepper, false,
                "hamiltel-gmlc");
    }

    @Test
    void withoutAPepperTheRefIsUnkeyedSha256() {
        SubjectRefHasher hasher = new SubjectRefHasher(properties(null));

        assertThat(hasher.ref("256700000001", IMSI))
                .isEqualTo("e0aeeed93b6c6b10a787ee6c105b892cb45280c42b4122386980536db1fd53aa");
    }

    @Test
    void withAPepperTheRefIsHmacSha256() {
        SubjectRefHasher hasher = new SubjectRefHasher(properties("test-pepper-0123456789"));

        assertThat(hasher.ref("256700000001", IMSI))
                .isEqualTo("75aac16cf25b1b31303c294f4aeb273d320547b92b38331c5bfb6670ed8a2f3b");
    }

    @Test
    void theSameInputAlwaysProducesTheSameRef() {
        SubjectRefHasher hasher = new SubjectRefHasher(properties("pepper"));

        assertThat(hasher.ref("256700000001", null))
                .isEqualTo(hasher.ref("256700000001", null))
                .isNotEqualTo(hasher.ref("256700000002", null));
    }

    @Test
    void anImsiIsPreferredOverAnMsisdn() {
        SubjectRefHasher hasher = new SubjectRefHasher(properties("pepper"));

        assertThat(hasher.ref("256700000001", IMSI))
                .isEqualTo(hasher.ref(null, IMSI))
                .isNotEqualTo(hasher.ref("256700000001", null));
    }

    @Test
    void aBlankPepperFallsBackToUnkeyedHashing() {
        SubjectRefHasher hasher = new SubjectRefHasher(properties("   "));

        assertThat(hasher.ref(null, IMSI))
                .isEqualTo("e0aeeed93b6c6b10a787ee6c105b892cb45280c42b4122386980536db1fd53aa");
    }
}
