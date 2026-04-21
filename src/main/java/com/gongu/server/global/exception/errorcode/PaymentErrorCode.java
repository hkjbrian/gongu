package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_FOUND("PAYMENT_001", "존재하지 않는 결제입니다", 404),
    PAYMENT_AMOUNT_MISMATCH("PAYMENT_002", "결제 금액이 일치하지 않습니다", 400),
    PAYMENT_ALREADY_PROCESSED("PAYMENT_003", "이미 처리된 결제입니다", 409),
    PG_API_ERROR("PAYMENT_004", "결제 API 호출에 실패했습니다", 502);

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
