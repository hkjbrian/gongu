package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * #249 — completePayment의 보상·실패 분기가 실제 Spring 트랜잭션에서 어떻게 커밋(또는 롤백)되는지 고정한다.
 * 상태를 바꾼 뒤 끝나는 분기는 예외 대신 Rejected로 반환돼 트랜잭션이 정상 커밋되고, 그 뒤에
 * PaymentService가 예외를 던진다. 예외로 끝나는 분기는 롤백되며 바꾼 상태가 없다 —
 * Mockito 단위 테스트로는 이 커밋 결과를 알 수 없으므로 새 조회로 DB 최종 상태를 단언한다.
 * (OrderService.createOrder가 단일 상품 주문만 만들므로 여기서는 단일 항목 주문만 다룬다.)
 */
@SpringBootTest
class PaymentCompletionCommitIntegrationTest {

    private static final long UNIT_PRICE = 10_000L;
    private static final long QUANTITY = 2L;
    private static final long ORDER_AMOUNT = UNIT_PRICE * QUANTITY;
    private static final int INITIAL_STOCK = 5;

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
    private ProductRepository productRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PortOneClient portOneClient;

    @AfterEach
    void tearDown() {
        // payment_histories가 payments를 FK로 참조하므로 payments보다 먼저 지운다
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        storeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("금액불일치_REFUNDED_CANCELLED가_커밋된다")
    void 금액불일치_REFUNDED_CANCELLED가_커밋된다() {
        // given
        Fixture fixture = createFixture("pay-commit-mismatch", false);
        given(portOneClient.getPayment(fixture.paymentId())).willReturn(pgResult(fixture.paymentId(), "PAID", 15_000L));
        given(portOneClient.cancelPayment(eq(fixture.paymentId()), anyString()))
                .willReturn(new PortOnePaymentResult(null, "{}"));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(fixture.paymentId(), PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        Payment reloadedPayment = paymentRepository.findByMerchantUid(fixture.paymentId()).orElseThrow();
        Order reloadedOrder = orderRepository.findById(fixture.orderId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(fixture.productId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloadedProduct.getRemainingStock()).isEqualTo(INITIAL_STOCK);

        List<PaymentHistory> histories =
                paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(reloadedPayment.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(histories.get(0).getReason()).isEqualTo("결제 금액 불일치");
    }

    @Test
    @DisplayName("PG상태_미완료_FAILED가_커밋된다")
    void PG상태_미완료_FAILED가_커밋된다() {
        // given
        Fixture fixture = createFixture("pay-commit-failed", false);
        given(portOneClient.getPayment(fixture.paymentId()))
                .willReturn(pgResult(fixture.paymentId(), "FAILED", ORDER_AMOUNT));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(fixture.paymentId(), PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        Payment reloadedPayment = paymentRepository.findByMerchantUid(fixture.paymentId()).orElseThrow();
        Order reloadedOrder = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.RESERVED);

        List<PaymentHistory> histories =
                paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(reloadedPayment.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("주문만료_환불이_커밋된다")
    void 주문만료_환불이_커밋된다() {
        // given — 주문은 이미 만료(CANCELLED)됐는데 결제는 PENDING으로 남은 상황
        Fixture fixture = createFixture("pay-commit-expired", true);
        given(portOneClient.cancelPayment(eq(fixture.paymentId()), anyString()))
                .willReturn(new PortOnePaymentResult(null, "{}"));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(fixture.paymentId(), PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        Payment reloadedPayment = paymentRepository.findByMerchantUid(fixture.paymentId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        List<PaymentHistory> histories =
                paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(reloadedPayment.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("금액불일치_PG취소실패_DB는_그대로_PENDING")
    void 금액불일치_PG취소실패_DB는_그대로_PENDING() {
        // given
        Fixture fixture = createFixture("pay-commit-cancel-fail", false);
        given(portOneClient.getPayment(fixture.paymentId())).willReturn(pgResult(fixture.paymentId(), "PAID", 15_000L));
        given(portOneClient.cancelPayment(eq(fixture.paymentId()), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(fixture.paymentId(), PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(InfraException.class);

        Payment reloadedPayment = paymentRepository.findByMerchantUid(fixture.paymentId()).orElseThrow();
        Order reloadedOrder = orderRepository.findById(fixture.orderId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(fixture.productId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(reloadedProduct.getRemainingStock()).isEqualTo(INITIAL_STOCK);
        assertThat(paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(reloadedPayment.getId()))
                .isEmpty();
    }

    private PortOnePaymentResult pgResult(String paymentId, String status, long amount) {
        String rawJson = "{\"id\":\"" + paymentId + "\",\"status\":\"" + status + "\",\"amount\":{\"total\":" + amount + "}}";
        return new PortOnePaymentResult(
                new PortOnePaymentResponse(paymentId, status, new PortOnePaymentResponse.Amount(amount),
                        OffsetDateTime.now()),
                rawJson);
    }

    /**
     * 사용자·매장·상품(재고 INITIAL_STOCK)·단일 항목 주문·PENDING 결제를 저장한다.
     * orderExpired가 true면 주문을 CANCELLED로 만든다.
     */
    private Fixture createFixture(String paymentId, boolean orderExpired) {
        User user = userRepository.save(User.of("결제자-" + paymentId, "010-9999-0000"));
        Store store = storeRepository.save(Store.create("테스트매장", "서울시 강남구", "02-1234-5678"));
        Product product = productRepository.save(Product.create(
                store, "커밋검증상품", "설명", UNIT_PRICE, INITIAL_STOCK,
                ProductStatus.ACTIVE, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)));

        Order order = Order.create(user, ORDER_AMOUNT);
        if (orderExpired) {
            order.cancel("주문 만료");
        }
        order = orderRepository.save(order);
        orderItemRepository.save(OrderItem.create(order, product, QUANTITY));
        paymentRepository.save(Payment.initiate(order, "idem-" + paymentId, paymentId, ORDER_AMOUNT));
        return new Fixture(paymentId, order.getId(), product.getId());
    }

    private record Fixture(String paymentId, Long orderId, Long productId) {}
}
