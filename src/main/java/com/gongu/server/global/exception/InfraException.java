package com.gongu.server.global.exception;

public class InfraException extends GonguException {

    public InfraException(ErrorCode errorCode) {
        super(errorCode);
    }
}
