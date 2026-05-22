package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {

    STORE_NOT_FOUND("STORE_001", "존재하지 않는 매장입니다", 404),
    USER_STORE_DUPLICATE("STORE_002", "이미 등록된 매장입니다", 409),
    STORE_ADMIN_NOT_FOUND("STORE_003", "존재하지 않는 매장 관리자입니다", 404);

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
