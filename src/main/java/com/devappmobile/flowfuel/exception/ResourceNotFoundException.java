package com.devappmobile.flowfuel.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super("%s não encontrado: %s".formatted(resource, id));
    }
}
