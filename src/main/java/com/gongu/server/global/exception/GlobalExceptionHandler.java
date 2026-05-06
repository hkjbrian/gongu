package com.gongu.server.global.exception;

import com.gongu.server.global.common.ErrorResponse;
import com.gongu.server.global.exception.errorcode.CommonErrorCode;
import jakarta.validation.ConstraintViolationException;
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
        return handleGonguException(e);
    }

    @ExceptionHandler(InfraException.class)
    public ResponseEntity<ErrorResponse> handleInfraException(InfraException e) {
        log.error("External system failure: {}", e.getMessage(), e);
        return handleGonguException(e);
    }

    @ExceptionHandler(GonguException.class)
    public ResponseEntity<ErrorResponse> handleGonguException(GonguException e) {
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

    /**
     * @Validated + @RequestParam 검증 실패 시 발생하는 ConstraintViolationException 처리.
     * @Valid + @RequestBody의 MethodArgumentNotValidException과 동일하게 HTTP 400으로 반환한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> {
                    String field = violation.getPropertyPath().toString();
                    // "methodName.paramName" 형태에서 파라미터명만 추출
                    int lastDot = field.lastIndexOf('.');
                    if (lastDot >= 0) {
                        field = field.substring(lastDot + 1);
                    }
                    return ErrorResponse.FieldError.of(field, violation.getMessage());
                })
                .toList();

        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, fieldErrors));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(org.springframework.security.core.AuthenticationException e) {
        ErrorCode errorCode = CommonErrorCode.UNAUTHORIZED;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException e) {
        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
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
