package com.devappmobile.flowfuel.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentryConfigTest {

    private SentryConfig configWithDsn(String dsn) {
        SentryConfig config = new SentryConfig();
        ReflectionTestUtils.setField(config, "dsn", dsn);
        ReflectionTestUtils.setField(config, "environment", "staging");
        return config;
    }

    @Test
    void semDsn_naoLancaEFicaDesativado() {
        assertThatCode(() -> configWithDsn("").validateDsn()).doesNotThrowAnyException();
        assertThatCode(() -> configWithDsn("   ").validateDsn()).doesNotThrowAnyException();
        assertThatCode(() -> configWithDsn(null).validateDsn()).doesNotThrowAnyException();
    }

    @Test
    void dsnValido_naoLanca() {
        assertThatCode(() -> configWithDsn("https://abc123@o123.ingest.sentry.io/456").validateDsn())
                .doesNotThrowAnyException();
    }

    @Test
    void dsnSemChavePublica_lancaFailFast() {
        assertThatThrownBy(() -> configWithDsn("https://o123.ingest.sentry.io/456").validateDsn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chave publica");
    }

    @Test
    void dsnSemProjectId_lancaFailFast() {
        assertThatThrownBy(() -> configWithDsn("https://abc123@o123.ingest.sentry.io/").validateDsn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Project Id");
    }

    @Test
    void dsnComProtocoloInvalido_lancaFailFast() {
        assertThatThrownBy(() -> configWithDsn("ftp://abc123@o123.ingest.sentry.io/456").validateDsn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
    }

    @Test
    void dsnNaoUri_lancaFailFast() {
        assertThatThrownBy(() -> configWithDsn("not a valid dsn at all").validateDsn())
                .isInstanceOf(IllegalStateException.class);
    }
}
