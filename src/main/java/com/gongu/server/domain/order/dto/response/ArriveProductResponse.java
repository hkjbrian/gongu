package com.gongu.server.domain.order.dto.response;

public record ArriveProductResponse(Long productId, int arrivedOrderCount, int notifiedCount) {

    public static ArriveProductResponse of(Long productId, int count) {
        // notifiedCount: 알림 도메인 미구현으로 0 고정, 추후 알림 발송 기능 연동 시 갱신 예정
        return new ArriveProductResponse(productId, count, 0);
    }
}
