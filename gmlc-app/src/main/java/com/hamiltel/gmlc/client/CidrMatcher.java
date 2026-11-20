package com.hamiltel.gmlc.client;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * IPv4 CIDR and literal-address matching for the client allowlist. IPv6 is
 * matched as a literal address only: we do not take IPv6 prefixes until a peer
 * actually needs them.
 *
 * <p>Both sides must be literals. A hostname in the allowlist is treated as a
 * non-match rather than resolved, because a DNS lookup inside an authentication
 * check is both slow and a way to let an attacker choose the answer.
 */
public final class CidrMatcher {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private CidrMatcher() {
    }

    public static boolean matches(String rule, String address) {
        if (rule == null || rule.isBlank() || address == null || address.isBlank()) {
            return false;
        }
        String host = rule;
        int prefixBits = -1;
        int slash = rule.indexOf('/');
        if (slash >= 0) {
            host = rule.substring(0, slash);
            try {
                prefixBits = Integer.parseInt(rule.substring(slash + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (!isLiteral(host) || !isLiteral(address)) {
            return false;
        }
        try {
            byte[] network = java.net.InetAddress.getByName(host).getAddress();
            byte[] candidate = java.net.InetAddress.getByName(address).getAddress();
            if (network.length != candidate.length) {
                return false;
            }
            if (prefixBits < 0) {
                return Arrays.equals(network, candidate);
            }
            if (prefixBits > network.length * 8) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != candidate[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /**
     * Validates an allowlist entry at registration time. The prefix length is
     * checked against the address family: an IPv4 /33 would be stored happily
     * and then never match, locking the client out of its own gateway.
     */
    public static boolean isSupportedRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String host = rule;
        int bits = -1;
        int slash = rule.indexOf('/');
        if (slash >= 0) {
            host = rule.substring(0, slash);
            try {
                bits = Integer.parseInt(rule.substring(slash + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (!isLiteral(host)) {
            return false;
        }
        int maxBits = host.indexOf(':') >= 0 ? 128 : 32;
        return bits < 0 || (bits <= maxBits);
    }

    private static boolean isLiteral(String value) {
        if (IPV4.matcher(value).matches()) {
            String[] parts = value.split("\\.");
            for (String part : parts) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches();
    }
}
