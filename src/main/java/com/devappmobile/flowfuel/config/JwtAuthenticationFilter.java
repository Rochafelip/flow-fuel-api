package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.error.ProblemDetailWriter;
import com.devappmobile.flowfuel.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String MDC_USER_ID = "userId";

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // getServletPath() é vazio no MockMvc (ainda não processado pelo DispatcherServlet);
        // getRequestURI() é sempre confiável em ambos os ambientes.
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/forgot-password")
                || path.equals("/api/v1/auth/reset-password")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.equals("/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Acesso sem Bearer code={} method={} path={}",
                    ErrorCode.AUTH_REQUIRED.code(), request.getMethod(), request.getRequestURI());
            ProblemDetailWriter.write(response, request.getRequestURI(),
                    ErrorCode.AUTH_REQUIRED,
                    "Autenticação necessária para acessar este recurso");
            return;
        }

        String token = authHeader.substring(7).trim();
        try {
            String email = jwtUtil.extractEmail(token);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (jwtUtil.validateToken(token)) {
                    userRepository.findByEmail(email).ifPresent(user -> {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                user, null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        if (user.getId() != null) {
                            MDC.put(MDC_USER_ID, user.getId().toString());
                        }
                    });
                }
            }

            try {
                filterChain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_USER_ID);
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Token JWT invalido code={} method={} path={} reason={}",
                    ErrorCode.AUTH_TOKEN_INVALID.code(), request.getMethod(),
                    request.getRequestURI(), ex.getMessage());
            ProblemDetailWriter.write(response, request.getRequestURI(),
                    ErrorCode.AUTH_TOKEN_INVALID,
                    ex.getMessage() != null ? ex.getMessage() : "Token inválido");
        }
    }
}
