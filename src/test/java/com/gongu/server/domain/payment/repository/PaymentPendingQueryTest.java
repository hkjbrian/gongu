package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.config.JpaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class PaymentPendingQueryTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void countByStatus_해당_상태의_결제만_센다() {
        User user = userRepository.save(User.of("u", "010-1111-2222"));
        Order order1 = orderRepository.save(Order.create(user, 10000L));
        Order order2 = orderRepository.save(Order.create(user, 20000L));
        paymentRepository.save(Payment.initiate(order1, "idem-1", "pay-1", 10000L));
        paymentRepository.save(Payment.initiate(order2, "idem-2", "pay-2", 20000L));

        assertThat(paymentRepository.countByStatus(PaymentStatus.PENDING)).isEqualTo(2);
        assertThat(paymentRepository.countByStatus(PaymentStatus.PAID)).isZero();
    }

    @Test
    void findOldestCreatedAtByStatus_가장_오래된_결제_생성시각을_반환한다() {
        User user = userRepository.save(User.of("u", "010-1111-2222"));
        Order order1 = orderRepository.save(Order.create(user, 10000L));
        Order order2 = orderRepository.save(Order.create(user, 20000L));
        Payment first = paymentRepository.save(Payment.initiate(order1, "idem-1", "pay-1", 10000L));
        paymentRepository.save(Payment.initiate(order2, "idem-2", "pay-2", 20000L));
        paymentRepository.flush();

        assertThat(paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING))
                .hasValue(first.getCreatedAt());
    }

    @Test
    void findOldestCreatedAtByStatus_해당_상태가_없으면_빈_값() {
        assertThat(paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING)).isEmpty();
    }
}
