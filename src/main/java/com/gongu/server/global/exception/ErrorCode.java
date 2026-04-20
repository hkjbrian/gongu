package com.gongu.server.global.exception;

public interface ErrorCode {

    String getCode();

    String getMessage();

    int getHttpStatus();
}
