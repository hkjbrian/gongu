package com.gongu.server.global.exception;

import com.gongu.server.global.common.ErrorResponse;
import com.gongu.server.global.exception.errorcode.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(InfraException.class)
    public ResponseEntity<ErrorResponse> handleInfraException(InfraException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        List<ErrorResponse.FieldError> fieldErrors = bindingResult.getAllErrors().stream()
                .map(error -> toFieldError(bindingResult, error))
                .toList();

        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, fieldErrors));
    }

    private ErrorResponse.FieldError toFieldError(BindingResult bindingResult, ObjectError error) {
        if (error instanceof org.springframework.validation.FieldError fieldError) {
            return ErrorResponse.FieldError.of(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return ErrorResponse.FieldError.of(
                bindingResult.getObjectName(),
                error.getDefaultMessage()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception occurred", e);
        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
    }
}
