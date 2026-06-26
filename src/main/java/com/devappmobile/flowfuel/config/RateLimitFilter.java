package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.ClientIpResolver;
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
        String clientIp = ClientIpResolver.resolve(request);
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
}
