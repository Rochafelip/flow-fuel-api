package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import java.io.PrintWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // quando Authorization ausente/inválido, retornar JSON detalhado
            writeDetailedError(response, request, "Acesso negado",
                    "Full authentication is required to access this resource");
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
                    });
                }
            }

            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            String msg = ex.getMessage() == null ? "Token inválido" : ex.getMessage();
            writeDetailedError(response, request, "Acesso negado", msg);
            return;
        }
    }

    private void writeDetailedError(HttpServletResponse response, HttpServletRequest request, String errorLabel,
            String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        try (PrintWriter out = response.getWriter()) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append('"').append("error").append('"').append(':');
            sb.append('"').append(escapeJson(errorLabel)).append('"').append(',');
            sb.append('"').append("message").append('"').append(':');
            sb.append('"').append(escapeJson(message)).append('"').append(',');
            sb.append('"').append("timestamp").append('"').append(':');
            sb.append(System.currentTimeMillis()).append(',');

            sb.append('"').append("request").append('"').append(':').append('{');
            sb.append('"').append("method").append('"').append(':');
            sb.append('"').append(escapeJson(request.getMethod())).append('"').append(',');
            sb.append('"').append("uri").append('"').append(':');
            sb.append('"').append(escapeJson(request.getRequestURI())).append('"').append(',');
            sb.append('"').append("query").append('"').append(':');
            sb.append('"').append(escapeJson(request.getQueryString())).append('"').append(',');
            sb.append('"').append("remoteAddr").append('"').append(':');
            sb.append('"').append(escapeJson(request.getRemoteAddr())).append('"').append(',');

            sb.append('"').append("headers").append('"').append(':').append('{');
            Enumeration<String> headerNames = request.getHeaderNames();
            boolean firstHeader = true;
            while (headerNames != null && headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if ("Authorization".equalsIgnoreCase(name)) {
                    continue; // não incluir Authorization
                }
                if (!firstHeader)
                    sb.append(',');
                firstHeader = false;
                sb.append('"').append(escapeJson(name)).append('"').append(':');
                sb.append('[');
                Enumeration<String> values = request.getHeaders(name);
                boolean firstVal = true;
                while (values != null && values.hasMoreElements()) {
                    if (!firstVal)
                        sb.append(',');
                    firstVal = false;
                    sb.append('"').append(escapeJson(values.nextElement())).append('"');
                }
                sb.append(']');
            }
            sb.append('}'); // end headers

            sb.append('}'); // end request
            sb.append('}'); // end root

            out.write(sb.toString());
            out.flush();
        }
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}