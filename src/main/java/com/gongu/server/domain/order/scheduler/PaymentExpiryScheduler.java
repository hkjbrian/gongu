package com.gongu.server.domain.order.scheduler;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.payment.service.PaymentExpireService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryScheduler {

    private final PaymentExpireService paymentExpireService;
    private final PaymentRepository paymentRepository;

    @Value("${order.reservation-ttl-minutes}")
    private long reservationTtlMinutes;

    @Scheduled(fixedDelay = 60_000)
    public void expireReservedPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(reservationTtlMinutes);
        List<Long> expiredIds = paymentRepository.findExpiredPendingPaymentIds(
                PaymentStatus.PENDING, OrderStatus.RESERVED, threshold, PageRequest.of(0, 100));

        int count = 0;
        for (Long id : expiredIds) {
            try {
                paymentExpireService.cancelExpiredPayment(id, threshold);
                count++;
            } catch (Exception e) {
                log.warn("만료 Payment 취소 실패: paymentId={}", id, e);
            }
        }
        log.info("만료 Payment 처리 완료: {}건", count);
    }
}
