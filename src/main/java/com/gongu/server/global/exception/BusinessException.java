package com.gongu.server.global.exception;

public class BusinessException extends GonguException {

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }
}
