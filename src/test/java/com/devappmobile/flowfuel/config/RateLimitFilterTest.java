package com.devappmobile.flowfuel.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private static final Map<String, BucketConfiguration> LIMITS = Map.of(
            LOGIN_PATH,
            BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder().capacity(5)
                            .refillGreedy(5, Duration.ofMinutes(1)).build())
                    .build()
    );

    @SuppressWarnings("unchecked")
    @Test
    void doFilter_proxyManagerThrows_failsOpenAndContinuesChain() throws Exception {
        ProxyManager<String> broken = mock(ProxyManager.class);
        when(broken.builder()).thenThrow(new RuntimeException("Redis connection refused"));

        RateLimitFilter filter = new RateLimitFilter(LIMITS, broken, 0);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN_PATH);
        request.addHeader("X-Forwarded-For", "10.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // fail-open: request must pass through (chain invoked, not 429)
        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("filter chain must have been called (request passed through)")
                .isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldNotFilter_nonPostRequest_returnsTrue() {
        ProxyManager<String> pm = mock(ProxyManager.class);
        RateLimitFilter filter = new RateLimitFilter(LIMITS, pm, 0);

        MockHttpServletRequest get = new MockHttpServletRequest("GET", LOGIN_PATH);

        assertThat(filter.shouldNotFilter(get)).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldNotFilter_unknownPath_returnsTrue() {
        ProxyManager<String> pm = mock(ProxyManager.class);
        RateLimitFilter filter = new RateLimitFilter(LIMITS, pm, 0);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/other");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}
