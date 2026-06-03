package com.devappmobile.flowfuel.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;

/**
 * Escreve um ProblemDetail RFC 7807 na resposta, consistente com o GlobalExceptionHandler.
 * Usado em pontos onde a exception nao chega ao @RestControllerAdvice
 * (entry points de seguranca, filtros customizados).
 */
public final class ProblemDetailWriter {

    private static final String ERROR_TYPE_BASE = "https://flowfuel.app/errors/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemDetailWriter() {}

    public static void write(HttpServletResponse response, String path, ErrorCode code, String detail)
            throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setTitle(code.title());
        pd.setType(URI.create(ERROR_TYPE_BASE + code.code()));
        if (path != null) {
            pd.setInstance(URI.create(path));
        }
        pd.setProperty("code", code.code());
        pd.setProperty("timestamp", OffsetDateTime.now().toString());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        MAPPER.writeValue(response.getWriter(), pd);
    }
}
