package com.devappmobile.flowfuel.common;

import jakarta.servlet.http.HttpServletRequest;

/** Extração de IP do cliente, considerando proxy reverso (X-Forwarded-For). */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
