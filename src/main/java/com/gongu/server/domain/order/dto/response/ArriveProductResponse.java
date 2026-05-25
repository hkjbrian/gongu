package com.gongu.server.domain.order.dto.response;

public record ArriveProductResponse(Long productId, int arrivedOrderCount) {

    public static ArriveProductResponse of(Long productId, int count) {
        return new ArriveProductResponse(productId, count);
    }
}
