package com.devappmobile.flowfuel.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final int NUMERIC_CODE_DIGITS = 6;
    private static final int NUMERIC_CODE_BOUND = (int) Math.pow(10, NUMERIC_CODE_DIGITS);
    private static final SecureRandom RNG = new SecureRandom();

    private OpaqueTokenGenerator() {}

    public static String generatePlaintext() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Codigo numerico de {@value NUMERIC_CODE_DIGITS} digitos (com zeros a esquerda), sorteado a cada chamada. */
    public static String generateNumericCode() {
        int value = RNG.nextInt(NUMERIC_CODE_BOUND);
        return String.format("%0" + NUMERIC_CODE_DIGITS + "d", value);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
