package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentExpireReconciler의 @Transactional(REQUIRES_NEW)이 실제 Spring 프록시를 통해
 * 적용되는지를 진짜 DB로 검증한다.
 *
 * 이 테스트가 존재하는 이유: PaymentExpireServiceTest / PaymentExpireReconcilerTest는 전부
 * Mockito 단위 테스트라 in-memory 객체 상태 변화만 확인한다 — self-invocation으로
 * @Transactional이 무시되는 버그(이 브랜치에서 실제로 발견되어 PaymentExpireReconciler를
 * 별도 빈으로 분리해 고쳤다)가 있었더라도, payment.incrementExpiryCheckAttempts()가
 * DB에 반영되지 않은 채 단위 테스트는 전부 초록불이었을 것이다. 여기서는 실제 Spring
 * 컨텍스트를 띄워 별도 리포지토리 조회로 DB 반영 여부까지 확인한다.
 */
@SpringBootTest
class PaymentExpireReconcilerIntegrationTest {

    @Autowired
    private PaymentExpireReconciler reconciler;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("recordInconclusiveAttempt_호출_후_expiry_check_attempts가_실제_DB에_반영된다")
    void recordInconclusiveAttempt_DB_반영_확인() {
        // given
        User user = userRepository.save(User.of("결제자", "010-9999-0000"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        Payment payment = paymentRepository.save(
                Payment.initiate(order, "idem-int-recon-1", "pay-int-recon-1", 10_000L));
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(0);

        // when — 한도(3)에는 못 미치는 시도이므로 카운터/이력 기록 없이 횟수만 늘어나야 한다.
        reconciler.recordInconclusiveAttempt(payment.getId(), 3);

        // then — 같은 영속성 컨텍스트가 아니라 새 조회로, 실제 DB에 커밋됐는지를 확인한다.
        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getExpiryCheckAttempts()).isEqualTo(1);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
