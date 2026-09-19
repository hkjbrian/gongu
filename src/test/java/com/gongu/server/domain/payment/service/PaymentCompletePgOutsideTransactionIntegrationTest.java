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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * #146 — completePayment의 PG 조회가 실제로 @Transactional 밖에서 실행되는지 증명한다.
 * Task 1/2의 Mockito 단위 테스트는 호출 순서만 검증하므로, PaymentService.completePayment의
 * @Transactional(propagation = NOT_SUPPORTED)가 빠지거나 잘못돼도 초록불일 수 있다 — 여기서는
 * 진짜 Spring 트랜잭션 매니저에게 "지금 트랜잭션이 열려 있냐"고 직접 물어본다.
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
}
