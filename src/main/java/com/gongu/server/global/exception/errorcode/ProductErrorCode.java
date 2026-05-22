package com.gongu.server.global.exception.errorcode;

import com.gongu.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND("PRODUCT_001", "존재하지 않는 상품입니다", 404),
    INSUFFICIENT_STOCK("PRODUCT_002", "재고가 부족합니다", 409),
    INVALID_PRODUCT_STATUS("PRODUCT_003", "판매 중이지 않은 상품입니다", 400),
    INVALID_PRODUCT_DATA("PRODUCT_004", "유효하지 않은 상품 데이터입니다", 400),
    CANNOT_ACTIVATE_PRODUCT("PRODUCT_005", "활성화할 수 없는 상태의 상품입니다", 400);

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
