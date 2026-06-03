package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.error.ProblemDetailWriter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting por IP nos endpoints de autenticacao (FLOW-009).
 *
 * <p>Protege contra credential stuffing / forca bruta e abuso do fluxo de reset
 * de senha. Cada par (path, IP) recebe um bucket de tokens independente; ao
 * estourar o limite responde 429 (Too Many Requests) com header {@code Retry-After}
 * e corpo ProblemDetail (RFC 7807), consistente com o resto da API.
 *
 * <p>Os buckets vivem em memoria ({@link ConcurrentHashMap}). Em deploy
 * multi-instancia o limite passa a ser por instancia; para limite global seria
 * necessario um backend distribuido (ex.: Redis via bucket4j-redis).
 */
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Configuracao de limite por path exato (ja com o prefixo /api/v1). Imutavel. */
    private final Map<String, Bandwidth> limitsByPath;
    /** Bucket por chave "path|ip". */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int order;

    public RateLimitFilter(Map<String, Bandwidth> limitsByPath, int order) {
        this.limitsByPath = Map.copyOf(limitsByPath);
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // Apenas POST nos paths configurados; o resto passa direto.
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !limitsByPath.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        Bandwidth limit = limitsByPath.get(path); // nao-nulo: garantido por shouldNotFilter
        String clientIp = clientIp(request);

        Bucket bucket = buckets.computeIfAbsent(path + "|" + clientIp,
                k -> Bucket.builder().addLimit(limit).build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
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

    /**
     * IP do cliente. Atras do proxy TLS (Render) o IP real chega em X-Forwarded-For;
     * o primeiro elemento da lista e o cliente original. Sem proxy, usa o remote addr.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
