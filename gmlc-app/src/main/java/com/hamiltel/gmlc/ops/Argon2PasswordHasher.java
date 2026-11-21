package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.config.OpsProperties;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Argon2id in the standard PHC string form. The cost parameters travel with
 * every hash, so raising the cost later does not invalidate existing accounts:
 * verification always uses the parameters recorded in the stored string.
 */
@Component
public class Argon2PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_PASSWORD_CHARS = 1024;
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final SecureRandom random = new SecureRandom();
    private final int memoryKib;
    private final int iterations;
    private final int parallelism;

    public Argon2PasswordHasher(OpsProperties properties) {
        this.memoryKib = properties.argon2MemoryKib();
        this.iterations = properties.argon2Iterations();
        this.parallelism = properties.argon2Parallelism();
    }

    public String hash(char[] password) {
        requireLength(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, memoryKib, iterations, parallelism);
        return "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism
                + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
    }

    /**
     * @return false for a malformed or non-argon2id string, never an exception;
     *         a corrupt row locks one account, it does not fail the login path
     */
    public boolean verify(char[] password, String encoded) {
        if (encoded == null || password == null || password.length > MAX_PASSWORD_CHARS) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
                return false;
            }
            int memory = 0;
            int time = 0;
            int lanes = 0;
            for (String pair : parts[3].split(",")) {
                String[] kv = pair.split("=");
                if (kv.length != 2) {
                    return false;
                }
                switch (kv[0]) {
                    case "m" -> memory = Integer.parseInt(kv[1]);
                    case "t" -> time = Integer.parseInt(kv[1]);
                    case "p" -> lanes = Integer.parseInt(kv[1]);
                    default -> {
                        return false;
                    }
                }
            }
            if (memory <= 0 || time <= 0 || lanes <= 0) {
                return false;
            }
            byte[] salt = DECODER.decode(parts[4]);
            byte[] expected = DECODER.decode(parts[5]);
            byte[] actual = derive(password, salt, memory, time, lanes);
            boolean matches = MessageDigest.isEqual(expected, actual);
            Arrays.fill(actual, (byte) 0);
            return matches;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Burns the same cost as a real verification, for an unknown username. */
    public void burn(char[] password, String encoded) {
        verify(password, encoded);
    }

    private static byte[] derive(char[] password, byte[] salt, int memory, int time, int lanes) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(time)
                .withMemoryAsKB(memory)
                .withParallelism(lanes)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] output = new byte[HASH_BYTES];
        generator.generateBytes(password, output);
        parameters.clear();
        return output;
    }

    private static void requireLength(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password is empty");
        }
        if (password.length > MAX_PASSWORD_CHARS) {
            throw new IllegalArgumentException("password is too long");
        }
    }

    /** A fixed hash used only to keep unknown-user logins from being faster. */
    public static String timingAnchor() {
        return "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2E$"
                + ENCODER.encodeToString(new byte[HASH_BYTES]);
    }
}
