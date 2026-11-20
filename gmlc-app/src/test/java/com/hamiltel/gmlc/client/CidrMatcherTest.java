package com.hamiltel.gmlc.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void anIpv4PrefixMatchesInsideAndRefusesOutside() {
        assertThat(CidrMatcher.matches("10.0.0.0/8", "10.1.2.3")).isTrue();
        assertThat(CidrMatcher.matches("10.0.0.0/8", "11.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches("196.43.0.0/16", "196.43.10.9")).isTrue();
        assertThat(CidrMatcher.matches("196.43.0.0/16", "196.44.10.9")).isFalse();
    }

    @Test
    void aPartialBytePrefixComparesOnlyTheHighBits() {
        assertThat(CidrMatcher.matches("192.168.1.0/26", "192.168.1.62")).isTrue();
        assertThat(CidrMatcher.matches("192.168.1.0/26", "192.168.1.64")).isFalse();
    }

    @Test
    void aBareLiteralMatchesOnlyItself() {
        assertThat(CidrMatcher.matches("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(CidrMatcher.matches("127.0.0.1", "127.0.0.2")).isFalse();
    }

    @Test
    void aZeroPrefixMatchesEveryAddressOfThatFamily() {
        assertThat(CidrMatcher.matches("0.0.0.0/0", "203.0.113.9")).isTrue();
    }

    @Test
    void ipv6IsMatchedAsALiteral() {
        assertThat(CidrMatcher.matches("::1", "::1")).isTrue();
        assertThat(CidrMatcher.matches("::1", "::2")).isFalse();
        assertThat(CidrMatcher.matches("2001:db8::1", "2001:db8::1")).isTrue();
    }

    @Test
    void hostnamesAndMalformedRulesNeverMatch() {
        assertThat(CidrMatcher.matches("example.com", "127.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.0/8", "example.com")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.0/33", "10.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.0/x", "10.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches(null, "10.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.0/8", null)).isFalse();
    }

    @Test
    void registrationAcceptsOnlyLiteralRules() {
        assertThat(CidrMatcher.isSupportedRule("10.0.0.0/8")).isTrue();
        assertThat(CidrMatcher.isSupportedRule("127.0.0.1")).isTrue();
        assertThat(CidrMatcher.isSupportedRule("2001:db8::/32")).isTrue();
        assertThat(CidrMatcher.isSupportedRule("example.com")).isFalse();
        assertThat(CidrMatcher.isSupportedRule("10.0.0.0/32")).isTrue();
        assertThat(CidrMatcher.isSupportedRule("10.0.0.0/33")).isFalse();
        assertThat(CidrMatcher.isSupportedRule("2001:db8::/128")).isTrue();
        assertThat(CidrMatcher.isSupportedRule("2001:db8::/129")).isFalse();
        assertThat(CidrMatcher.isSupportedRule("10.0.0.256")).isFalse();
        assertThat(CidrMatcher.isSupportedRule("")).isFalse();
    }
}
