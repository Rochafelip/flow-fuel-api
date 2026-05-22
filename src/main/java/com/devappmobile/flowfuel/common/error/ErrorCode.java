package com.devappmobile.flowfuel.common.error;

import org.springframework.http.HttpStatus;

/**
 * Catalogo unico de codigos de erro da API.
 * Toda exception de dominio deve mapear para um ErrorCode aqui.
 * O campo {@code code} (= name()) e parte do contrato publico da API.
 */
public enum ErrorCode {

    // 400 — entrada invalida
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validação falhou"),
    BUSINESS_RULE_VIOLATED(HttpStatus.BAD_REQUEST, "Regra de negócio violada"),
    REQUEST_MALFORMED(HttpStatus.BAD_REQUEST, "Requisição malformada"),

    // 401 — autenticacao
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Não autenticado"),
    AUTH_BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Credenciais inválidas"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token inválido"),
    AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token inválido"),
    AUTH_REFRESH_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token expirado"),
    AUTH_REFRESH_REVOKED(HttpStatus.UNAUTHORIZED, "Refresh token revogado"),

    // 403 — autorizacao
    FORBIDDEN_OPERATION(HttpStatus.FORBIDDEN, "Operação não permitida"),

    // 404 — recurso ausente
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado"),

    // 409 — conflito
    CONFLICT(HttpStatus.CONFLICT, "Conflito"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email já cadastrado"),

    // 500 — generico
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String code() {
        return name();
    }
}
