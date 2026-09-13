package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class PaymentHistoryIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PortOneClient portOneClient;

    @AfterEach
    void tearDown() {
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("completePayment_확정되면_PAID_이력이_원문과_함께_DB에_남는다")
    void completePayment_확정되면_PAID_이력이_원문과_함께_DB에_남는다() {
        User user = userRepository.save(User.of("결제자", "010-2222-3333"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        Payment payment = paymentRepository.save(Payment.initiate(order, "idem-int-1", "pay-int-1", 10_000L));

        String rawJson = "{\"id\":\"pay-int-1\",\"status\":\"PAID\",\"amount\":{\"total\":10000},"
                + "\"paidAt\":\"2026-01-01T00:00:00+09:00\"}";
        given(portOneClient.getPayment("pay-int-1")).willReturn(new PortOnePaymentResult(
                new PortOnePaymentResponse("pay-int-1", "PAID",
                        new PortOnePaymentResponse.Amount(10_000L),
                        OffsetDateTime.parse("2026-01-01T00:00:00+09:00")),
                rawJson));

        paymentService.completePayment("pay-int-1", PaymentHistoryTrigger.CLIENT_VERIFY);

        Payment saved = paymentRepository.findByMerchantUid("pay-int-1").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);

        List<PaymentHistory> histories = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(saved.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(histories.get(0).getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
        assertThat(histories.get(0).getPgRawResponse()).contains("\"status\":\"PAID\"");
    }

    @Test
    @DisplayName("completePayment_PG조회실패시_이력이_남지_않고_상태도_바뀌지_않는다")
    void completePayment_PG조회실패시_이력이_남지_않고_상태도_바뀌지_않는다() {
        User user = userRepository.save(User.of("결제자2", "010-4444-5555"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        Payment payment = paymentRepository.save(Payment.initiate(order, "idem-int-2", "pay-int-2", 10_000L));

        given(portOneClient.getPayment("pay-int-2")).willReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.gongu.server.global.exception.BusinessException.class,
                () -> paymentService.completePayment("pay-int-2", PaymentHistoryTrigger.CLIENT_VERIFY));

        Payment saved = paymentRepository.findByMerchantUid("pay-int-2").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(saved.getId())).isEmpty();
    }
}
