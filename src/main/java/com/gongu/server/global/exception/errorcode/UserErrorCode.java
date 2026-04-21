package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND("USER_001", "존재하지 않는 회원입니다", 404),
    DUPLICATE_EMAIL("USER_002", "이미 사용 중인 이메일입니다", 409);

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
