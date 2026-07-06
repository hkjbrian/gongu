package com.gongu.server.global.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class TracedAnnotationAspect {

    private static final String ORDER_SECTION_TIMER = "gongu.order.section";

    private final MeterRegistry meterRegistry;

    @Around("@annotation(traced)")
    public Object traceAnnotation(ProceedingJoinPoint pjp, Traced traced) throws Throwable {
        return record(pjp, traced.value());
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
