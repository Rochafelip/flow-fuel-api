package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;
import com.devappmobile.flowfuel.common.error.RequestIdFilter;
import com.devappmobile.flowfuel.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** URI base dos tipos de erro. Apontara para uma pagina de catalogo no futuro. */
    private static final String ERROR_TYPE_BASE = "https://flowfuel.app/errors/";

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex,
            HttpServletRequest req) {
        logClientError(ex.getErrorCode(), req, ex.getMessage());
        ProblemDetail pd = problemDetail(ex.getErrorCode(), ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(ex.getErrorCode().status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(pd);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException ex, HttpServletRequest req) {
        ErrorCode code = ex.getErrorCode();
        logClientError(code, req, ex.getMessage());
        return build(code, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Email ou senha inválidos";
        logClientError(ErrorCode.AUTH_BAD_CREDENTIALS, req, detail);
        return build(ErrorCode.AUTH_BAD_CREDENTIALS, detail, req.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest req) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(this::violationToMap)
                .toList();
        logClientError(ErrorCode.VALIDATION_FAILED, req, "Constraint violation");
        ProblemDetail pd = problemDetail(ErrorCode.VALIDATION_FAILED,
                "Um ou mais parâmetros são inválidos", req.getRequestURI());
        pd.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", fe.getField());
                    m.put("message", fe.getDefaultMessage());
                    return m;
                })
                .toList();
        String path = pathFromRequest(request);
        logClientError(ErrorCode.VALIDATION_FAILED, path, "Method argument not valid");
        ProblemDetail pd = problemDetail(ErrorCode.VALIDATION_FAILED,
                "Um ou mais campos são inválidos", path);
        pd.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException ex,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String path = pathFromRequest(request);
        logClientError(ErrorCode.REQUEST_MALFORMED, path, "Body not readable");
        ProblemDetail pd = problemDetail(ErrorCode.REQUEST_MALFORMED,
                "Corpo da requisição inválido ou ausente", path);
        return ResponseEntity.status(ErrorCode.REQUEST_MALFORMED.status()).body(pd);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex,
            HttpServletRequest req) {
        String detail = "Arquivo excede o tamanho máximo permitido no upload";
        logClientError(ErrorCode.BUSINESS_RULE_VIOLATED, req, detail);
        return build(ErrorCode.BUSINESS_RULE_VIOLATED, detail, req.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest req) {
        logClientError(ErrorCode.CONFLICT, req, "Constraint de unicidade violada");
        return build(ErrorCode.CONFLICT, "Recurso já existe ou viola uma restrição única", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Erro nao tratado method={} path={} code={}",
                req.getMethod(), req.getRequestURI(), ErrorCode.INTERNAL_ERROR.code(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Ocorreu um erro inesperado", req.getRequestURI());
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, String path) {
        return ResponseEntity.status(code.status()).body(problemDetail(code, detail, path));
    }

    private ProblemDetail problemDetail(ErrorCode code, String detail, String path) {
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
        return pd;
    }

    private void logClientError(ErrorCode code, HttpServletRequest req, String detail) {
        logClientError(code, req.getRequestURI(), req.getMethod(), detail);
    }

    private void logClientError(ErrorCode code, String path, String detail) {
        logClientError(code, path, null, detail);
    }

    private void logClientError(ErrorCode code, String path, String method, String detail) {
        // 4xx esperados: WARN. Nunca enviar pra Sentry (config via sentry.logging.minimum-event-level=error).
        if (method != null) {
            log.warn("Erro de cliente code={} status={} method={} path={} detail={}",
                    code.code(), code.status().value(), method, path, detail);
        } else {
            log.warn("Erro de cliente code={} status={} path={} detail={}",
                    code.code(), code.status().value(), path, detail);
        }
    }

    private String pathFromRequest(WebRequest request) {
        String desc = request.getDescription(false);
        return desc != null && desc.startsWith("uri=") ? desc.substring(4) : desc;
    }

    private Map<String, String> violationToMap(ConstraintViolation<?> v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", v.getPropertyPath().toString());
        m.put("message", v.getMessage());
        return m;
    }
}
