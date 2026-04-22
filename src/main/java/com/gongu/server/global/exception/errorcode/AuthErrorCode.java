package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_TOKEN("AUTH_001", "유효하지 않은 토큰입니다", 401),
    EXPIRED_TOKEN("AUTH_002", "만료된 토큰입니다", 401),
    KAKAO_API_ERROR("AUTH_003", "카카오 API 호출에 실패했습니다", 502),
    UNAUTHORIZED("AUTH_004", "인증이 필요합니다", 401),
    FORBIDDEN("AUTH_005", "접근 권한이 없습니다", 403);

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
