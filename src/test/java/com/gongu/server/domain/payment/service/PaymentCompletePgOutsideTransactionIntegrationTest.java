package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * #146 — completePayment의 PG 조회가 실제로 @Transactional 밖에서 실행되는지 증명한다.
 * Task 1/2의 Mockito 단위 테스트는 호출 순서만 검증하므로, completePayment에 트랜잭션이
 * 다시 걸려도 초록불일 수 있다 — 여기서는 진짜 Spring 트랜잭션 매니저에게
 * "지금 트랜잭션이 열려 있냐"고 직접 물어본다.
 * <p>
 * 단, 트랜잭션이 없다는 것과 DB 커넥션을 쥐고 있지 않다는 것은 다르다. NOT_SUPPORTED 는 실제 트랜잭션 없이도
 * 트랜잭션 동기화 범위를 열어 커넥션을 메서드 끝까지 붙잡았다. 그래서 아래에 풀의 활성 커넥션 수를 직접
 * 검증하는 테스트를 별도로 둔다.
 */
@SpringBootTest
class PaymentCompletePgOutsideTransactionIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private PortOneClient portOneClient;

    @AfterEach
    void tearDown() {
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("completePayment_PG_getPayment_호출_시점에_활성_트랜잭션이_없다")
    void completePayment_PG_호출_시점_트랜잭션_비활성() {
        // given
        User user = userRepository.save(User.of("결제자4", "010-1111-2222"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        paymentRepository.save(Payment.initiate(order, "idem-int-tx-1", "pay-int-tx-1", 10_000L));

        AtomicBoolean transactionActiveDuringPgCall = new AtomicBoolean(true); // 기본값 true — 호출이 아예 안 되면 실패로 드러나게
        given(portOneClient.getPayment("pay-int-tx-1")).willAnswer(invocation -> {
            transactionActiveDuringPgCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PortOnePaymentResult(
                    new PortOnePaymentResponse("pay-int-tx-1", "PAID",
                            new PortOnePaymentResponse.Amount(10_000L), OffsetDateTime.now()),
                    "{\"id\":\"pay-int-tx-1\",\"status\":\"PAID\"}");
        });

        // when
        paymentService.completePayment("pay-int-tx-1", PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(transactionActiveDuringPgCall.get())
                .as("portOneClient.getPayment 호출 시점에 활성 Spring 트랜잭션이 없어야 한다 (#146)")
                .isFalse();

        Payment saved = paymentRepository.findByMerchantUid("pay-int-tx-1").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    /**
     * 위 테스트는 "활성 트랜잭션이 없다"만 보므로, 트랜잭션이 없어도 DB 커넥션을 쥔 채 PG를 기다리는 상태를
     * 잡지 못한다. 실제로 PG 무응답 측정(2026-10-08)에서 verify 30건만으로 Hikari active가 풀 최대(25)까지 찼다.
     * PG 호출 시점에 풀에서 빌려간 커넥션이 없어야 PG 지연이 DB 풀 고갈로 번지지 않는다.
     */
    @Test
    @DisplayName("completePayment_PG_getPayment_호출_시점에_DB_커넥션을_점유하지_않는다")
    void completePayment_PG_호출_시점_DB_커넥션_미점유() {
        // given
        User user = userRepository.save(User.of("결제자5", "010-1111-3333"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        paymentRepository.save(Payment.initiate(order, "idem-int-conn-1", "pay-int-conn-1", 10_000L));

        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        int activeBeforeCall = pool.getActiveConnections(); // 측정이 유효하려면 호출 전에는 0이어야 한다

        AtomicInteger activeDuringPgCall = new AtomicInteger(-1); // -1 — 호출이 아예 안 되면 실패로 드러나게
        given(portOneClient.getPayment("pay-int-conn-1")).willAnswer(invocation -> {
            activeDuringPgCall.set(pool.getActiveConnections());
            return new PortOnePaymentResult(
                    new PortOnePaymentResponse("pay-int-conn-1", "PAID",
                            new PortOnePaymentResponse.Amount(10_000L), OffsetDateTime.now()),
                    "{\"id\":\"pay-int-conn-1\",\"status\":\"PAID\"}");
        });

        // when
        paymentService.completePayment("pay-int-conn-1", PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(activeBeforeCall)
                .as("전제: PG 호출 전에는 풀에서 빌려간 커넥션이 없어야 측정이 유효하다")
                .isZero();
        assertThat(activeDuringPgCall.get())
                .as("portOneClient.getPayment 호출 시점에 풀에서 빌려간 DB 커넥션이 없어야 한다")
                .isZero();

        Payment saved = paymentRepository.findByMerchantUid("pay-int-conn-1").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
    }
}
