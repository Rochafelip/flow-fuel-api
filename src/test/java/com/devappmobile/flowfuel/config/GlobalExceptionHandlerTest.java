package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void handleDataIntegrityViolation_retorna409ComCodeConflict() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\"");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/register");

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(ex, req);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).isNotNull();
        assertThat(body.getProperties().get("code")).isEqualTo("CONFLICT");
        assertThat(body.getProperties().get("requestId")).isEqualTo("req-123");
        assertThat(body.getInstance()).hasToString("/api/v1/auth/register");
    }
}
