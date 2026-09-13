package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.config.JpaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class PaymentHistoryRepositoryTest {

    @Autowired private PaymentHistoryRepository paymentHistoryRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void findByPaymentIdOrderByCreatedAtAsc_생성순으로_반환() {
        User user = userRepository.save(User.of("u", "010-1111-2222"));
        Order order = orderRepository.save(Order.create(user, 10000L));
        Payment payment = paymentRepository.save(Payment.initiate(order, "idem-1", "pay-1", 10000L));

        paymentHistoryRepository.save(PaymentHistory.record(
                payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, null));
        paymentHistoryRepository.save(PaymentHistory.record(
                payment, PaymentStatus.PAID, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.WEBHOOK, "테스트", null));

        List<PaymentHistory> histories = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());

        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(histories.get(1).getToStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
