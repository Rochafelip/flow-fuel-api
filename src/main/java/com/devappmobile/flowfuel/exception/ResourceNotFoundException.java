package com.devappmobile.flowfuel.exception;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s não encontrado: %s".formatted(resource, id));
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
