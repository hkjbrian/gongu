package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_FOUND("PAYMENT_001", "존재하지 않는 결제입니다", 404),
    PAYMENT_AMOUNT_MISMATCH("PAYMENT_002", "결제 금액이 일치하지 않습니다", 422),
    PAYMENT_ALREADY_PROCESSED("PAYMENT_003", "이미 처리된 결제입니다", 409),
    PAYMENT_NOT_ALLOWED("PAYMENT_004", "결제할 수 없는 주문 상태입니다", 409),
    PAYMENT_PG_UNAVAILABLE("PAYMENT_005", "결제 서비스를 일시적으로 사용할 수 없습니다", 503),
    PAYMENT_INVALID_STATE_TRANSITION("PAYMENT_006", "유효하지 않은 결제 상태 전이입니다", 409),
    PAYMENT_NOT_COMPLETED("PAYMENT_007", "결제가 완료되지 않은 상태입니다", 422);

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
