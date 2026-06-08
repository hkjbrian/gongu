package com.gongu.server.domain.order.scheduler;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.order.service.OrderExpireService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryScheduler {

    private final OrderExpireService orderExpireService;
    private final OrderRepository orderRepository;
    @Qualifier("orderExpiredCounter")
    private final Counter orderExpiredCounter;
    @Qualifier("orderExpireDuration")
    private final Timer orderExpireDuration;

    @Value("${order.reservation-ttl-minutes}")
    private long reservationTtlMinutes;

    @Scheduled(fixedDelay = 60_000)
    public void expireReservedOrders() {
        try {
            int count = orderExpireDuration.recordCallable(() -> {
                LocalDateTime threshold = LocalDateTime.now().minusMinutes(reservationTtlMinutes);
                List<Long> expiredIds = orderRepository.findExpiredReservedOrderIds(
                        OrderStatus.RESERVED, threshold, PageRequest.of(0, 100));

                int processedCount = 0;
                for (Long id : expiredIds) {
                    try {
                        orderExpireService.cancelExpiredOrder(id, threshold);
                        processedCount++;
                        orderExpiredCounter.increment();
                    } catch (Exception e) {
                        log.warn("만료 주문 취소 실패: orderId={}", id, e);
                    }
                }
                return processedCount;
            });
            log.info("만료 주문 처리 완료: {}건", count);
        } catch (Exception e) {
            throw new IllegalStateException("만료 주문 처리 중 예외 발생", e);
        }
    }
}
