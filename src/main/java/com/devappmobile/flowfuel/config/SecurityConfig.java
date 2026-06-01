package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.error.ProblemDetailWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;
    private final Environment environment;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        boolean isProd = environment.matchesProfiles("prod");

        http
            .cors(cors -> {})
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                        "/",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                    ).permitAll()
                    .anyRequest().authenticated())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> {
                // X-Content-Type-Options: nosniff (FLOW-013)
                headers.contentTypeOptions(contentType -> {});
                // X-XSS-Protection: 1; mode=block (FLOW-013)
                headers.xssProtection(xss -> xss
                        .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK));

                if (isProd) {
                    // HSTS habilitado apenas em producao (exige HTTPS).
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000));
                } else {
                    // frameOptions desabilitado apenas fora de producao
                    // (ex.: console H2 / ferramentas de dev em iframe).
                    headers.frameOptions(frameOptions -> frameOptions.disable());
                }
            })
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint(problemDetailAuthEntryPoint())
                    .accessDeniedHandler(problemDetailAccessDeniedHandler()))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationEntryPoint problemDetailAuthEntryPoint() {
        return (request, response, authException) -> ProblemDetailWriter.write(
                response, request.getRequestURI(),
                ErrorCode.AUTH_REQUIRED,
                authException.getMessage() != null ? authException.getMessage()
                        : "Autenticação necessária para acessar este recurso");
    }

    private AccessDeniedHandler problemDetailAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> ProblemDetailWriter.write(
                response, request.getRequestURI(),
                ErrorCode.FORBIDDEN_OPERATION,
                accessDeniedException.getMessage() != null ? accessDeniedException.getMessage()
                        : "Você não tem permissão para acessar este recurso");
    }
}
