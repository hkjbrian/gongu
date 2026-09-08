package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.InfraException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "portone.api-secret=test-secret",
        // 재시도 대기는 그대로 두되(0.5s) 각 시도의 read-timeout이 짧아야 함
})
@DisplayName("PortOne 전용 HTTP 타임아웃 (#222)")
class PortOneTimeoutTest {

    private static HttpServer server;
    private static java.util.concurrent.ExecutorService stubExecutor;
    private static final AtomicInteger hits = new AtomicInteger();

    @DynamicPropertySource
    static void portOneBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        stubExecutor = Executors.newCachedThreadPool();
        server.setExecutor(stubExecutor);
        server.createContext("/payments/", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(5_000); // read-timeout(2s)보다 확실히 김
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        registry.add("portone.base-url", () -> "http://localhost:" + server.getAddress().getPort());
    }

    @Autowired
    private PortOneClient portOneClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        hits.set(0);
        circuitBreakerRegistry.circuitBreaker("portone").reset();
    }

    @AfterEach
    void tearDownCb() {
        circuitBreakerRegistry.circuitBreaker("portone").reset();
    }

    @Test
    @DisplayName("느린 PG는 전용 read-timeout(2s)으로 시도마다 끊기고, 총 소요가 5s×3보다 빠르다")
    void slowPg_isBoundedByDedicatedReadTimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> portOneClient.getPayment("pg-timeout-1"))
                .isInstanceOf(InfraException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 3회 재시도 × ~2s + 2 × 0.5s ≈ 7s. 전역 5s가 적용됐다면 ≈16s.
        // 하한 5s는 "3회의 ~2s read가 실제로 일어났다"를 고정한다(예: 100ms 오설정이면 ~1s로 무너짐).
        assertThat(elapsed).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(12));
        assertThat(hits.get()).isEqualTo(3); // @Retry max-attempts
    }

    @org.junit.jupiter.api.AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
        if (stubExecutor != null) stubExecutor.shutdownNow();
    }
}
