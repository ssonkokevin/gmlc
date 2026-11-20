package com.hamiltel.gmlc.client;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void aKeyCarriesItsPrefixAnd256BitsOfMaterial() {
        String key = ApiKeyHasher.newKey();

        assertThat(key).startsWith("gk_").hasSize(46);
        assertThat(ApiKeyHasher.looksLikeAKey(key)).isTrue();
        assertThat(key.substring(3)).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void keysDoNotRepeat() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(ApiKeyHasher.newKey());
        }
        assertThat(keys).hasSize(100);
    }

    @Test
    void theHashIsPlainSha256Hex() {
        assertThat(ApiKeyHasher.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .hasSize(64);
    }

    @Test
    void malformedCandidatesAreNotKeys() {
        assertThat(ApiKeyHasher.looksLikeAKey(null)).isFalse();
        assertThat(ApiKeyHasher.looksLikeAKey("")).isFalse();
        assertThat(ApiKeyHasher.looksLikeAKey("gk_short")).isFalse();
        assertThat(ApiKeyHasher.looksLikeAKey("sk_" + "A".repeat(43))).isFalse();
        assertThat(ApiKeyHasher.looksLikeAKey("gk_" + "A".repeat(44))).isFalse();
    }
}
