package com.gongu.server.global.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class TracedAspect {

    private static final String ORDER_SECTION_TIMER = "gongu.order.section";

    private final MeterRegistry meterRegistry;

    @Around("execution(* com.gongu.server.domain.user.repository.UserRepository.findByIdAndDeletedAtIsNull(..))")
    public Object traceUserLookup(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "user_lookup");
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository.findById(..)) "
            + "&& target(com.gongu.server.domain.product.repository.ProductRepository)")
    public Object traceProductLookup(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "product_lookup");
    }

    @Around("execution(* com.gongu.server.domain.store.repository.UserStoreRepository.existsByUserAndStore(..))")
    public Object traceStoreMembershipCheck(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "store_membership_check");
    }

    @Around("execution(* com.gongu.server.domain.product.service.StockRedisService.reserveStock(..))")
    public Object traceStockReserve(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "stock_reserve");
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository.save(..)) "
            + "&& target(com.gongu.server.domain.order.repository.OrderRepository)")
    public Object traceOrderSave(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "order_save");
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository.save(..)) "
            + "&& target(com.gongu.server.domain.order.repository.OrderItemRepository)")
    public Object traceOrderItemSave(ProceedingJoinPoint pjp) throws Throwable {
        return recordIfActive(pjp, "order_item_save");
    }

    private Object recordIfActive(ProceedingJoinPoint pjp, String section) throws Throwable {
        if (!OrderSectionTracingContext.isActive()) {
            return pjp.proceed();
        }

        return record(pjp, section);
    }

    private Object record(ProceedingJoinPoint pjp, String section) throws Throwable {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return pjp.proceed();
        } finally {
            sample.stop(Timer.builder(ORDER_SECTION_TIMER)
                    .tag("section", section)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }
}
