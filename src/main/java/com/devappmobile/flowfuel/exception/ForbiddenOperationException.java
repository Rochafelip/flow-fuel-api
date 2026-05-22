package com.devappmobile.flowfuel.exception;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ForbiddenOperationException extends AppException {

    public ForbiddenOperationException(String message) {
        super(ErrorCode.FORBIDDEN_OPERATION, message);
    }

    public ForbiddenOperationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
