package com.gongu.server.global.exception;

import lombok.Getter;

@Getter
public abstract class GonguException extends RuntimeException {

    private final ErrorCode errorCode;

    public GonguException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
