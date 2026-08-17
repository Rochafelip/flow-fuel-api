package com.devappmobile.flowfuel.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenGeneratorTest {

    @Test
    void generatePlaintext_retorna43Chars() {
        String token = OpaqueTokenGenerator.generatePlaintext();
        assertThat(token).hasSize(43);
    }

    @Test
    void generatePlaintext_ehBase64UrlSafeSemPadding() {
        String token = OpaqueTokenGenerator.generatePlaintext();
        // Base64 URL-safe sem padding: apenas A-Z, a-z, 0-9, -, _
        assertThat(token).matches("[A-Za-z0-9\\-_]+");
        // Decodifica corretamente para 32 bytes
        byte[] decoded = Base64.getUrlDecoder().decode(token + "="); // padding manual p/ decode
        assertThat(decoded).hasSize(32);
    }

    @Test
    void generatePlaintext_producesUniqueValues() {
        String t1 = OpaqueTokenGenerator.generatePlaintext();
        String t2 = OpaqueTokenGenerator.generatePlaintext();
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void sha256_ehDeterministico() {
        String hash1 = OpaqueTokenGenerator.sha256("entrada");
        String hash2 = OpaqueTokenGenerator.sha256("entrada");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void sha256_diferenciaEntradas() {
        String h1 = OpaqueTokenGenerator.sha256("abc");
        String h2 = OpaqueTokenGenerator.sha256("xyz");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void sha256_formatoHex64Chars() {
        String hash = OpaqueTokenGenerator.sha256("qualquer");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void generateNumericCode_retorna6Digitos() {
        for (int i = 0; i < 200; i++) {
            assertThat(OpaqueTokenGenerator.generateNumericCode()).matches("\\d{6}");
        }
    }

    @Test
    void generateNumericCode_preservaZerosAEsquerda() {
        // Sobre muitas amostras, algum valor < 10000 deve aparecer e manter 5 chars.
        boolean sawLeadingZero = false;
        for (int i = 0; i < 2000 && !sawLeadingZero; i++) {
            String code = OpaqueTokenGenerator.generateNumericCode();
            if (code.charAt(0) == '0') {
                sawLeadingZero = true;
            }
        }
        assertThat(sawLeadingZero).isTrue();
    }

    @Test
    void generateNumericCode_producesDifferentValuesAcrossCalls() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            codes.add(OpaqueTokenGenerator.generateNumericCode());
        }
        assertThat(codes.size()).isGreaterThan(1);
    }
}
