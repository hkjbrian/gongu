package com.gongu.server.global.aop;

final class OrderSectionTracingContext {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private OrderSectionTracingContext() {
    }

    static void activate() {
        DEPTH.set(DEPTH.get() + 1);
    }

    static void deactivate() {
        int currentDepth = DEPTH.get();
        if (currentDepth <= 1) {
            DEPTH.remove();
            return;
        }

        DEPTH.set(currentDepth - 1);
    }

    static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
