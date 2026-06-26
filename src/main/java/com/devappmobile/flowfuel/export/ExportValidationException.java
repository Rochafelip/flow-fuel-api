package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ExportValidationException extends AppException {

    public ExportValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
