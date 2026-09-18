package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 만료 스케줄러가 집어간 PENDING 결제를 PG 재확인 없이 취소하던 것(#215)을 고친다.
 * PG 조회·PAID 확정 로직은 completePayment(verify/webhook과 동일 경로)를 그대로 재사용하고,
 * 이 클래스는 그 결과에 따른 분기(주문 취소 / 재시도 대기 / 한도 초과 표시)만 담당한다.
 * 실제 트랜잭션이 필요한 후속 조치(주문 취소, 시도 횟수 기록)는 {@link PaymentExpireReconciler}에
 * 위임한다 — 같은 클래스 안에서 self-invocation으로 호출하면 Spring 프록시 기반
 * @Transactional이 무시되기 때문에 별도 빈으로 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpireService {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PaymentExpireReconciler reconciler;

    public void reconcileExpiredPayment(Long paymentId, int maxAttempts) {
        Optional<Payment> optionalPayment = paymentRepository.findById(paymentId);
        if (optionalPayment.isEmpty()) {
            return;
        }

        Payment payment = optionalPayment.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        if (payment.getExpiryCheckAttempts() >= maxAttempts) {
            return;
        }

        String merchantUid = payment.getMerchantUid();
        Long orderId = payment.getOrder().getId();

        try {
            // PG 호출은 completePayment 내부의 짧은 트랜잭션 안에서만 일어난다 —
            // 여기서는 어떤 트랜잭션도 들고 있지 않은 채로 호출한다 (#146 원칙).
            paymentService.completePayment(merchantUid, PaymentHistoryTrigger.EXPIRY_SCHEDULER);
        } catch (BusinessException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_COMPLETED) {
                // PG가 결제 안 됨을 확정 — completePayment가 이미 payment를 FAILED로 확정·기록했다.
                // 남은 건 예약된 주문·재고를 정리하는 것뿐이다.
                reconciler.cancelOrderAfterPgConfirmedUnpaid(orderId);
                return;
            }
            if (e.getErrorCode() == PaymentErrorCode.ORDER_EXPIRED_REFUNDED
                    || e.getErrorCode() == PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH) {
                // completePayment가 이미 자기 트랜잭션 안에서 스스로 정리를 끝냈다
                // (환불/만료 처리 + 주문 취소, 또는 금액 불일치로 인한 환불 + 주문 취소) —
                // 여기서 더 할 일이 없다.
                return;
            }
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                // PortOneClient.getPayment는 이제 진짜 404(PG가 이 paymentId를 전혀 모름)만 이 코드로 던진다 —
                // 401/403/429 등 나머지 4xx는 InfraException(PAYMENT_PG_UNAVAILABLE)로 분리됐다.
                // PG가 결제 안 됨을 명시적으로 확정한 것과 같은 무게이므로 정산한다.
                reconciler.settleUnconfirmedPayment(paymentId, orderId);
                return;
            }
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_PG_UNAVAILABLE) {
                // PG가 빈/파싱 불가 응답을 준 경우 — 판정 불가. 취소하지 않는다.
                reconciler.recordInconclusiveAttempt(paymentId, maxAttempts);
                throw e;
            }
            // 예상 밖의 에러 코드 — 취소하지 않고 그대로 전파해 다음 주기가 재시도하게 하되,
            // 무엇이 발생했는지는 남긴다.
            log.warn("만료 Payment 재확인 중 예상치 못한 BusinessException: paymentId={}, orderId={}, errorCode={}",
                    paymentId, orderId, e.getErrorCode(), e);
            throw e;
        } catch (InfraException e) {
            // PG 조회 판정 불가 — 취소하지 않는다. 다음 스케줄러 주기가 다시 시도한다.
            reconciler.recordInconclusiveAttempt(paymentId, maxAttempts);
            throw e;
        }
    }
}
