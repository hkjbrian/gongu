package com.gongu.server.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final String code;
    private final T data;

    private ApiResponse(String code, T data) {
        this.code = code;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("SUCCESS", null);
    }
}
