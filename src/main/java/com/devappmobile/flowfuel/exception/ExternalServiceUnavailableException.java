package com.devappmobile.flowfuel.exception;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ExternalServiceUnavailableException extends AppException {

    public ExternalServiceUnavailableException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, message);
    }

    public ExternalServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, message, cause);
    }
}
