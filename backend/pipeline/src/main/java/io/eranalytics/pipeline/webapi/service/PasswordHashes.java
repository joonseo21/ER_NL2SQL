package io.eranalytics.pipeline.webapi.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** JDK-only password hashing shared by login and the offline console tool. */
public final class PasswordHashes {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashes() {}

    public static String create(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, ITERATIONS, salt);
        return "pbkdf2_sha256$"
                + ITERATIONS
                + "$"
                + Base64.getEncoder().encodeToString(salt)
                + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    public static void validate(String encoded) {
        parse(encoded);
    }

    public static boolean matches(char[] password, String encoded) {
        Parts parts = parse(encoded);
        byte[] actual = derive(password, parts.iterations(), parts.salt());
        try {
            return MessageDigest.isEqual(parts.hash(), actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static Parts parse(String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < ITERATIONS || salt.length < 16 || hash.length != 32) {
                throw new IllegalArgumentException();
            }
            return new Parts(iterations, salt, hash);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid password hash format");
        }
    }

    private static byte[] derive(char[] password, int iterations, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing unavailable");
        } finally {
            spec.clearPassword();
        }
    }

    private record Parts(int iterations, byte[] salt, byte[] hash) {}
}
