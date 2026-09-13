package com.gongu.server.domain.payment.domain;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentHistoryTest {

    @Test
    void record_필드가_그대로_보관된다() {
        User user = User.of("user", "010-0000-0000");
        Order order = Order.create(user, 10000L);
        Payment payment = Payment.initiate(order, "idem-1", "pay-1", 10000L);

        PaymentHistory history = PaymentHistory.record(
                payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, "{\"status\":\"***\"}");

        assertThat(history.getPayment()).isSameAs(payment);
        assertThat(history.getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(history.getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
        assertThat(history.getReason()).isNull();
        assertThat(history.getPgRawResponse()).isEqualTo("{\"status\":\"***\"}");
    }
}
