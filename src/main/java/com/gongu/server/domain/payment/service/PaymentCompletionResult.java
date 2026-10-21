package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.global.exception.ErrorCode;

/**
 * {@link PaymentReconciler#completePayment}의 결과 (#249).
 * <p>
 * 상태를 바꾼 뒤 끝나는 분기(만료 환불·PG 미결제·금액 불일치·재고 부족)는 예외를 던지지 않고
 * {@link Rejected}를 반환한다 — 반환이므로 리컨실러 트랜잭션이 정상 커밋된다.
 * 호출부({@link PaymentService})가 커밋 이후에 {@code Rejected}를 같은 에러코드의 예외로 바꿔 던진다.
 * 아무것도 바꾸지 않고 실패하는 경우는 계속 예외이며, 트랜잭션은 롤백된다.
 */
public sealed interface PaymentCompletionResult {

    /** 결제가 확정됐거나 이미 확정돼 있다. */
    record Confirmed(VerifyPaymentResponse response) implements PaymentCompletionResult {}

    /** 상태 변경(환불·실패·주문 취소 등)을 커밋한 채로 요청은 실패로 끝난다. */
    record Rejected(ErrorCode errorCode) implements PaymentCompletionResult {}
}
