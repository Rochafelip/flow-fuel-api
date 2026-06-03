package com.devappmobile.flowfuel.common.error;

/**
 * Exception base do dominio. Carrega um {@link ErrorCode} que e exposto
 * no campo {@code code} do ProblemDetail e usado para correlacionar logs.
 */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AppException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
