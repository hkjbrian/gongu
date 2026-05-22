package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND("ORDER_001", "존재하지 않는 주문입니다", 404),
    CANCEL_NOT_ALLOWED("ORDER_002", "취소할 수 없는 상태의 주문입니다", 409),
    RECEIVE_NOT_ALLOWED("ORDER_003", "수령할 수 없는 상태의 주문입니다", 409),
    PAY_NOT_ALLOWED("ORDER_004", "결제할 수 없는 상태의 주문입니다", 409),
    ARRIVE_NOT_ALLOWED("ORDER_005", "입고 처리할 수 없는 상태의 주문입니다", 409),
    INVALID_ORDER_DATA("ORDER_006", "유효하지 않은 주문 데이터입니다", 400),
    ORDER_ITEM_NOT_FOUND("ORDER_007", "주문 항목이 존재하지 않습니다", 500);

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
