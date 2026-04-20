package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT_VALUE("COMMON_001", "입력값이 올바르지 않습니다", 400),
    INTERNAL_SERVER_ERROR("COMMON_002", "서버 내부 오류가 발생했습니다", 500),
    UNAUTHORIZED("COMMON_003", "인증이 필요합니다", 401),
    FORBIDDEN("COMMON_004", "접근 권한이 없습니다", 403);

    private final String code;
    private final String message;
    private final int httpStatus;

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public int getHttpStatus() {
        return httpStatus;
    }
}
