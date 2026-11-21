package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.config.GmlcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The subscriber reference stored in consent, request, CDR and audit rows.
 * MSISDN and IMSI never appear outside the request that carried them.
 *
 * <p>With {@code gmlc.subject-pepper} set the reference is an HMAC-SHA256, so a
 * copy of the database cannot be matched against a list of known MSISDNs.
 * Without it the hash is unkeyed; that is allowed for development and logged
 * as a warning on every start.
 */
@Component
public class SubjectRefHasher {

    private static final Logger log = LoggerFactory.getLogger(SubjectRefHasher.class);

    private final byte[] pepper;

    public SubjectRefHasher(GmlcProperties properties) {
        String configured = properties.subjectPepper();
        if (configured == null || configured.isBlank()) {
            this.pepper = null;
            log.warn("gmlc.subject-pepper is not set: subscriber references are unkeyed SHA-256");
        } else {
            this.pepper = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** IMSI is the stable identifier when both are supplied. */
    public String ref(String msisdn, String imsi) {
        String canonical = imsi != null && !imsi.isBlank()
                ? "imsi:" + imsi
                : "msisdn:" + msisdn;
        return hex(digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] digest(byte[] input) {
        try {
            if (pepper == null) {
                return MessageDigest.getInstance("SHA-256").digest(input);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256/HMAC-SHA256 are required by the Java platform", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
