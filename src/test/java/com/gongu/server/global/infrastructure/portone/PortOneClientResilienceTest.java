package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.InfraException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

@SpringBootTest(properties = {
        // 재시도가 (#213 수정 후) 살아나도 테스트가 느려지지 않도록 방어적으로 축소.
        // CB 기록 동작은 재시도 타이밍과 무관하므로 검증 유효성에는 영향 없음.
        "resilience4j.retry.instances.portone.wait-duration=1ms",
        // 테스트 application.yml 에는 portone 블록이 없다. props 가 (null, null) 이면
        // Authorization 헤더가 "PortOne null" 이 되고 baseUrl 구성 경로도 운영과 달라지므로
        // 여기서 명시적으로 채워 운영과 같은 코드 경로(props.baseUrl()/props.apiSecret())를 태운다.
        "portone.base-url=https://api.portone.test",
        "portone.api-secret=test-secret"
})
class PortOneClientResilienceTest {

    private static final String PAYMENT_ID = "pg-tx-1";

    /**
     * 자동 구성 RestClient.Builder 에 여러 소비자가 붙어 있어 무인자
     * MockServerRestClientCustomizer#getServer() 가 IllegalStateException 을 던지므로,
     * mock 서버에 직접 바인딩한 별도 RestClient 를 @Primary 빈으로 등록해 운영 portOneRestClient 빈을 대체한다.
     * 이 빈은 baseUrl / Authorization 헤더만 운영과 같은 방식으로 구성할 뿐, 운영 RestClientConfig 의
     * 자동 구성 빌더(타임아웃 등)를 그대로 쓰는 것은 아니다 — 서킷브레이커 기록 동작 검증에는 무관하다.
     *
     * <p>PortOneClient 는 이름이 {@code portOneRestClient} 인 생성자 파라미터로 주입받지만,
     * Spring 은 by-name 폴백보다 먼저 @Primary 후보를 고르므로(type-with-primary)
     * 이 mock 빈이 운영 빈을 이긴다.
     */
    @TestConfiguration
    static class MockServerConfig {

        private final RestClient.Builder builder = RestClient.builder();

        @Bean
        MockRestServiceServer portOneMockRestServiceServer() {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean
        @Primary
        RestClient mockPortOneRestClient(
                PortOneProperties props,
                // 미사용 파라미터지만 제거 금지 — dead code 가 아니라 초기화 순서 의존성이다.
                // portOneMockRestServiceServer() 빈(= MockRestServiceServer.bindTo(builder) 로 공유
                // builder 를 변형)이 아래 builder.build() 보다 먼저 초기화되도록 강제한다.
                // 제거하면 실제 요청 팩토리로 RestClient 가 만들어져 테스트가 api.portone.io 로 실제 HTTPS 호출을 한다.
                MockRestServiceServer server) {
            return builder
                    .baseUrl(props.baseUrl())
                    .defaultHeader("Authorization", "PortOne " + props.apiSecret())
                    .build();
        }
    }

    @Autowired
    private PortOneClient portOneClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MockRestServiceServer server;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        server.reset();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("portone");
        circuitBreaker.reset();
    }

    @AfterEach
    void tearDown() {
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
    }

    /**
     * 서킷이 OPEN 될 때까지 getPayment를 반복 호출한다.
     * 각 호출은 fallback을 거쳐 InfraException으로 떨어지므로 삼켜 준다.
     * 애스펙트 순서(#213)에 따라 OPEN 도달에 필요한 논리 호출 수가 달라질 수 있어
     * 정확한 횟수 대신 "maxCalls 안에 OPEN에 도달하는가"로 판정한다.
     */
    private void driveUntilOpen(int maxCalls) {
        for (int i = 0; i < maxCalls; i++) {
            if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                return;
            }
            try {
                portOneClient.getPayment(PAYMENT_ID);
            } catch (RuntimeException expected) {
                // InfraException(재시도 소진/서킷 개방) 또는 원본 예외 — 삼킨다
            }
        }
    }

    @Test
    @DisplayName("PG 5xx가 반복되면 서킷이 OPEN 된다 (기존 동작 회귀 가드)")
    void serverError_opens_circuit() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withServerError());

        driveUntilOpen(30);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("PG 연결 실패(ResourceAccessException)가 반복되면 서킷이 OPEN 된다")
    void networkError_opens_circuit() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withException(new ConnectException("simulated PG down")));

        driveUntilOpen(30);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("서킷이 OPEN 되면 이후 호출은 PG에 닿지 않고 InfraException으로 빠르게 실패한다")
    void open_circuit_shortCircuits_withoutHittingPg() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withException(new ConnectException("simulated PG down")));
        driveUntilOpen(30);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long notPermittedBefore = circuitBreaker.getMetrics().getNumberOfNotPermittedCalls();

        assertThatThrownBy(() -> portOneClient.getPayment(PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls())
                .isGreaterThan(notPermittedBefore);
    }
}
