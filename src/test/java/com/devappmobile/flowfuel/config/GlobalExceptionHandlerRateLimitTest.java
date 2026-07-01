package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerRateLimitTest {

    @Test
    void handleRateLimitExceeded_retorna429ComRetryAfterHeader() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/stations/nearby");

        ResponseEntity<ProblemDetail> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(30), req);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}
