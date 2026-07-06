package com.gongu.server.global.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SectionTimerRecorder {

    private static final String ORDER_SECTION_TIMER = "gongu.order.section";

    private final MeterRegistry meterRegistry;

    public Object record(ProceedingJoinPoint pjp, String section) throws Throwable {
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
