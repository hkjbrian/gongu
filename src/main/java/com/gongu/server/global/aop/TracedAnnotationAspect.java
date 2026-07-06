package com.gongu.server.global.aop;

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

    private final SectionTimerRecorder sectionTimerRecorder;

    @Around("@annotation(traced)")
    public Object traceAnnotation(ProceedingJoinPoint pjp, Traced traced) throws Throwable {
        OrderSectionTracingContext.activate();
        try {
            return sectionTimerRecorder.record(pjp, traced.value());
        } finally {
            OrderSectionTracingContext.deactivate();
        }
    }
}
