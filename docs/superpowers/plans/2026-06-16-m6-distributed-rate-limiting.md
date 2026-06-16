# M6 — Distributed Rate Limiting (Redis) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `RateLimitFilter` from a local `ConcurrentHashMap` to a Redis-backed `LettuceBasedProxyManager` (bucket4j-redis), with fail-open fallback when Redis is unavailable at runtime.

**Architecture:** `RateLimitingConfig` creates a `RedisClient` (Lettuce) and `LettuceBasedProxyManager<String>` bean, both conditional on `flowfuel.rate-limit.enabled=true`. `RateLimitFilter` replaces its local `Map<String, Bucket>` with the `ProxyManager<String>`, wrapping every Redis access in a try-catch that lets requests through when Redis is unavailable. Startup requires Redis to be reachable; runtime failures are fail-open.

**Tech Stack:** bucket4j-core 8.10.1 (existing), bucket4j-redis 8.10.1 (new), Lettuce Core 6.x (transitive via bucket4j-redis), Testcontainers (new, test scope only), JUnit 5 + Mockito (existing).

---

## File Map

| Action   | Path |
|----------|------|
| Modify   | `pom.xml` |
| Modify   | `src/main/resources/application.properties` |
| Modify   | `src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java` |
| Modify   | `src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java` |
| Modify   | `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java` |
| Create   | `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterTest.java` |

---

## Task 1: Add dependencies to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add bucket4j-redis and Testcontainers to pom.xml**

Open `pom.xml` and find the `<dependencies>` block. Add immediately after the existing `bucket4j-core` dependency:

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>
</dependency>
```

Then add in the test-scoped dependencies section (near h2, etc.):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify Maven resolves the new dependencies**

```bash
./mvnw dependency:resolve -q 2>&1 | tail -5
```

Expected: no errors. If `bucket4j-redis:8.10.1` is not found, verify the version matches `bucket4j-core` in the same `pom.xml`.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add bucket4j-redis and testcontainers dependencies for M6"
```

---

## Task 2: Configure Redis URL property

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the Redis URL property**

Open `src/main/resources/application.properties`. Find the `flowfuel.rate-limit.enabled` line and add the new property immediately below it:

```properties
flowfuel.rate-limit.redis-url=${REDIS_URL:redis://localhost:6379}
```

The `${REDIS_URL:...}` follows the 12-factor pattern already used in the project. In production (Render), set `REDIS_URL` to the provisioned Redis instance URL.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "config: add flowfuel.rate-limit.redis-url property for M6 Redis backend"
```

---

## Task 3: Refactor RateLimitFilter to use ProxyManager

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java`

The filter currently holds a `Map<String, Bandwidth>` and creates `Bucket` instances locally via `ConcurrentHashMap`. It needs to change to `Map<String, BucketConfiguration>` + `ProxyManager<String>`.

- [ ] **Step 1: Write the failing unit test first (in Task 6 file)**

Skip — the unit test `RateLimitFilterTest.java` is written in Task 6 and covers the new constructor and fail-open. Proceed here.

- [ ] **Step 2: Replace RateLimitFilter.java completely**

Replace the full contents of `src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java` with:

```java
package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.error.ProblemDetailWriter;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Rate limiting por IP nos endpoints de autenticacao (FLOW-009).
 *
 * <p>Protege contra credential stuffing / forca bruta e abuso do fluxo de reset
 * de senha. Cada par (path, IP) recebe um bucket de tokens independente; ao
 * estourar o limite responde 429 (Too Many Requests) com header {@code Retry-After}
 * e corpo ProblemDetail (RFC 7807), consistente com o resto da API.
 *
 * <p>Os buckets sao mantidos no Redis via {@link ProxyManager} (bucket4j-redis +
 * Lettuce), garantindo rate limiting efetivo em deploy horizontal. Se o Redis
 * ficar indisponivel em runtime, o filtro aplica fail-open: loga aviso e deixa
 * a requisicao passar para nao bloquear todos os usuarios.
 */
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Map<String, BucketConfiguration> limitsByPath;
    private final ProxyManager<String> proxyManager;
    private final int order;

    public RateLimitFilter(Map<String, BucketConfiguration> limitsByPath,
                           ProxyManager<String> proxyManager,
                           int order) {
        this.limitsByPath = Map.copyOf(limitsByPath);
        this.proxyManager = proxyManager;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !limitsByPath.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        BucketConfiguration config = limitsByPath.get(path);
        String clientIp = clientIp(request);
        String bucketKey = "rl:" + path + "|" + clientIp;

        ConsumptionProbe probe;
        try {
            BucketProxy bucket = proxyManager.builder().build(bucketKey, () -> config);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limit Redis indisponivel, fail-open. key={} error={}",
                    bucketKey, e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", Long.toString(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1L,
                Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        log.warn("Rate limit excedido code={} method=POST path={} ip={} retryAfter={}s",
                ErrorCode.RATE_LIMIT_EXCEEDED.code(), path, clientIp, retryAfterSeconds);
        ProblemDetailWriter.write(response, path, ErrorCode.RATE_LIMIT_EXCEEDED,
                "Muitas tentativas. Tente novamente em " + retryAfterSeconds + " segundos.");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 3: Verify the file compiles in isolation**

```bash
./mvnw compile -pl . -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS. If `BucketConfiguration` or `ProxyManager` is not found, the `bucket4j-redis` dependency from Task 1 may not have resolved — re-run `./mvnw dependency:resolve`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/RateLimitFilter.java
git commit -m "feat(rate-limit): migrate RateLimitFilter to ProxyManager with fail-open (M6)"
```

---

## Task 4: Refactor RateLimitingConfig to wire Redis beans

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java`

`RateLimitingConfig` currently builds a `Map<String, Bandwidth>` and passes it directly to the filter. It now needs to create a `RedisClient`, a `LettuceBasedProxyManager<String>`, and pass `Map<String, BucketConfiguration>` to the filter.

- [ ] **Step 1: Replace RateLimitingConfig.java completely**

Replace the full contents of `src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java` with:

```java
package com.devappmobile.flowfuel.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.Map;

/**
 * Configuracao do rate limiting dos endpoints de autenticacao (FLOW-009 / M6).
 *
 * <p>Limites (token bucket, por IP):
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — 5 tentativas/minuto</li>
 *   <li>{@code POST /api/v1/auth/forgot-password} — 3 tentativas/hora</li>
 *   <li>{@code POST /api/v1/auth/register} — 10 tentativas/hora</li>
 *   <li>{@code POST /api/v1/auth/resend-activation} — 3 tentativas/hora</li>
 * </ul>
 *
 * <p>Habilitado por padrao. Desligue com {@code flowfuel.rate-limit.enabled=false}
 * (usado nos testes de integracao, que compartilham 127.0.0.1 e excederiam os limites).
 *
 * <p>Backend: Redis via bucket4j-redis + Lettuce. URL configurada por
 * {@code flowfuel.rate-limit.redis-url} (env var {@code REDIS_URL}).
 * Se o Redis ficar indisponivel em runtime, o filtro aplica fail-open.
 */
@Configuration
@ConditionalOnProperty(name = "flowfuel.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingConfig {

    private static final String AUTH_BASE = "/api/v1/auth";

    // Apos o RequestIdFilter (HIGHEST_PRECEDENCE — requestId ja no MDC para o
    // ProblemDetail) e antes do JwtAuthenticationFilter / Spring Security.
    private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Value("${flowfuel.rate-limit.redis-url:redis://localhost:6379}")
    private String redisUrl;

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient() {
        return RedisClient.create(RedisURI.create(redisUrl));
    }

    @Bean
    LettuceBasedProxyManager<String> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        var connection = rateLimitRedisClient.connect(
                RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection).build();
    }

    @Bean
    RateLimitFilter rateLimitFilter(LettuceBasedProxyManager<String> rateLimitProxyManager) {
        Map<String, BucketConfiguration> limits = Map.of(
                AUTH_BASE + "/login",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(5)
                                .refillGreedy(5, Duration.ofMinutes(1)).build())
                        .build(),
                AUTH_BASE + "/forgot-password",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(3)
                                .refillGreedy(3, Duration.ofHours(1)).build())
                        .build(),
                AUTH_BASE + "/register",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(10)
                                .refillGreedy(10, Duration.ofHours(1)).build())
                        .build(),
                AUTH_BASE + "/resend-activation",
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.builder().capacity(3)
                                .refillGreedy(3, Duration.ofHours(1)).build())
                        .build());
        return new RateLimitFilter(limits, rateLimitProxyManager, FILTER_ORDER);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw compile -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS. If `LettuceBasedProxyManager` is not found, check that the import path is `io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager` — it's inside `bucket4j-redis`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/config/RateLimitingConfig.java
git commit -m "feat(rate-limit): wire Redis ProxyManager in RateLimitingConfig (M6)"
```

---

## Task 5: Unit test — fail-open behavior

**Files:**
- Create: `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterTest.java`

This test verifies the filter's fail-open logic without starting a Spring context or connecting to Redis. It uses Mockito to make the `ProxyManager` throw.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterTest.java`:

```java
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
```

- [ ] **Step 2: Run the failing tests**

```bash
./mvnw test -pl . -Dtest=RateLimitFilterTest -q 2>&1 | tail -30
```

Expected: Tests should PASS immediately (the fail-open logic was already written in Task 3). If any test fails, the filter implementation in Task 3 likely has a bug — re-read the try-catch block.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterTest.java
git commit -m "test(rate-limit): unit tests for RateLimitFilter fail-open behavior (M6)"
```

---

## Task 6: Integration tests with Testcontainers Redis

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java`

The integration test needs a real Redis. We'll start a Redis container with Testcontainers and inject its URL via `@DynamicPropertySource`. The existing four tests remain; we add two new ones: shared-limit across two proxy managers, and a regression to ensure IP isolation still works with Redis.

- [ ] **Step 1: Write the failing test additions first, then replace the file**

The shared-limit test simulates two app instances: it creates a second `LettuceBasedProxyManager` that connects to the same Redis container. It consumes 4 tokens via the second proxy manager, then expects the 5th request via MockMvc to succeed and the 6th to be blocked — proving the limit is shared across instances in Redis.

Replace `src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java` entirely:

```java
package com.devappmobile.flowfuel.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.devappmobile.flowfuel.user.UserRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre o RateLimitFilter (FLOW-009 / M6). Religa o rate limiting (desligado no
 * application.properties de teste) so para esta classe. Usa Testcontainers para
 * prover um Redis real — os buckets sao armazenados no Redis, validando o
 * comportamento distribuido. Cada teste usa X-Forwarded-For distinto para isolar
 * o bucket por IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "flowfuel.rate-limit.enabled=true")
class RateLimitFilterIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("flowfuel.rate-limit.redis-url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void limparBanco() {
        userRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Testes originais (regressao) — comportamento identico ao backend local
    // -----------------------------------------------------------------------

    @Test
    void login_apos5TentativasNoMesmoMinuto_retorna429ComRetryAfter() throws Exception {
        String ip = "10.0.0.1";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult blocked = mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();

        JsonNode body = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertThat(body.get("properties").get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
    }

    @Test
    void forgotPassword_apos3Tentativas_retorna429() throws Exception {
        String ip = "10.0.0.2";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"naoexiste@test.com"}
                            """))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"naoexiste@test.com"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void register_apos10Tentativas_retorna429() throws Exception {
        String ip = "10.0.0.3";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"user%d@test.com","password":"senha123","name":"U"}
                            """.formatted(i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/auth/register")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"user11@test.com","password":"senha123","name":"U"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void login_ipsDiferentes_naoCompartilhamLimite() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "10.0.0.4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10.0.0.5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"x@test.com","password":"errada"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Novo teste M6: limite compartilhado entre duas "instancias" via Redis
    // -----------------------------------------------------------------------

    /**
     * Simula duas instancias da aplicacao compartilhando o mesmo Redis.
     * A "instancia 2" consome 4 tokens diretamente via ProxyManager; a 5a
     * requisicao via MockMvc (instancia 1) ainda e permitida; a 6a e bloqueada.
     * Sem Redis compartilhado, a 6a teria passado pois a instancia 1 so viu 1 req.
     */
    @Test
    void login_duasInstancias_compartilhamLimiteNoRedis() throws Exception {
        String ip = "10.1.0.1";
        String bucketKey = "rl:/api/v1/auth/login|" + ip;

        BucketConfiguration loginConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(5)
                        .refillGreedy(5, Duration.ofMinutes(1)).build())
                .build();

        // Cria um segundo ProxyManager (simula instancia 2) apontando para o mesmo Redis
        String redisUrl = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        RedisClient client2 = RedisClient.create(RedisURI.create(redisUrl));
        try {
            var conn2 = client2.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            LettuceBasedProxyManager<String> pm2 = LettuceBasedProxyManager.builderFor(conn2).build();
            BucketProxy bucket2 = pm2.builder().build(bucketKey, () -> loginConfig);

            // Instancia 2 consome 4 dos 5 tokens
            for (int i = 0; i < 4; i++) {
                assertThat(bucket2.tryConsume(1)).isTrue();
            }

            // 5a requisicao (instancia 1 via MockMvc): 1 token restante, deve passar
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isUnauthorized()); // 401 = passou, nao bloqueado

            // 6a requisicao (instancia 1 via MockMvc): bucket esgotado no Redis
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"x@test.com","password":"errada"}
                            """))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            conn2.close();
        } finally {
            client2.shutdown();
        }
    }
}
```

- [ ] **Step 2: Run only the integration test (requires Docker for Testcontainers)**

```bash
./mvnw test -pl . -Dtest=RateLimitFilterIntegrationTest -q 2>&1 | tail -40
```

Expected: all 5 tests pass. The Testcontainers `GenericContainer` will pull `redis:7-alpine` on first run (takes ~30 s). Subsequent runs use the cached image.

If `redis:7-alpine` is not available in the CI environment, change the image to `redis:latest` in the `@Container` declaration.

If the shared-limit test fails with `AssertionError` on the 5th request (expecting `isUnauthorized` but getting `isTooManyRequests`): the filter's Redis key prefix `rl:` in `RateLimitFilter` and the test's `bucketKey` must match exactly. The filter uses `"rl:" + path + "|" + ip`, so the test key must be `"rl:/api/v1/auth/login|10.1.0.1"` — verify both are identical.

- [ ] **Step 3: Run the full test suite to check for regressions**

```bash
./mvnw test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS — all existing tests pass. Tests in other `@SpringBootTest` classes are unaffected because `flowfuel.rate-limit.enabled=false` in `src/test/resources/application.properties` prevents `RateLimitingConfig` (and its Redis beans) from loading in those contexts.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/config/RateLimitFilterIntegrationTest.java
git commit -m "test(rate-limit): Testcontainers Redis + shared-limit integration test (M6)"
```

---

## Self-Review

### Spec coverage

| Requirement (spec) | Task |
|--------------------|------|
| Adicionar `bucket4j-redis` ao `pom.xml` | Task 1 |
| Configurar `ProxyManager` Redis-backed em `RateLimitingConfig` | Task 4 |
| `REDIS_URL` via env var, padrão 12-factor | Task 2 |
| Fallback gracioso se Redis indisponível (fail-open) | Task 3 + 5 |
| Teste distribuído (múltiplas instâncias compartilham Redis) | Task 6 |
| Regressão `RateLimitFilterIntegrationTest` adaptada para Redis | Task 6 |
| Endpoints protegidos continuam retornando 429 + `Retry-After` | Task 6 (regressão) |
| Decisão documentada (prosseguir com Redis) | Este plano |

### No placeholders

All code blocks contain complete, runnable code.

### Type consistency

- `RateLimitFilter` constructor: `Map<String, BucketConfiguration>`, `ProxyManager<String>`, `int` — matches usage in `RateLimitingConfig.rateLimitFilter()`.
- Redis key format `"rl:" + path + "|" + ip` in `RateLimitFilter.doFilterInternal` matches `bucketKey` construction in `RateLimitFilterIntegrationTest.login_duasInstancias_compartilhamLimiteNoRedis`.
- `LettuceBasedProxyManager<String>` produced by `RateLimitingConfig.rateLimitProxyManager()` matches the `ProxyManager<String>` field in `RateLimitFilter`.
