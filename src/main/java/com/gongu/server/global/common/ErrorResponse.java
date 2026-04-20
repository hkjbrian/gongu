package com.gongu.server.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gongu.server.global.exception.ErrorCode;
import lombok.Getter;

import java.util.List;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final List<FieldError> errors;

    private ErrorResponse(String code, String message, List<FieldError> errors) {
        this.code = code;
        this.message = message;
        this.errors = errors;
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), errors);
    }

    @Getter
    public static class FieldError {

        private final String field;
        private final String reason;

        private FieldError(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }

        public static FieldError of(String field, String reason) {
            return new FieldError(field, reason);
        }
    }
}
