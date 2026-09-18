package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * #230 — 재고 부족 시 결제 확정이 실제로 롤백되지 않고 PAID로 커밋되던 버그의 회귀 테스트.
 * PaymentServiceTest는 Mockito 단위 테스트라 in-memory mock 상태만 본다 — 재고 부족 예외가
 * noRollbackFor 대상이라 실제로는 커밋되는 버그였더라도, mock만으로는 "무엇이 진짜 DB에
 * 반영됐는지" 알 수 없다. 여기서는 실제 Spring 트랜잭션 + DB로 커밋 결과를 확인한다.
 */
@SpringBootTest
class PaymentInsufficientStockIntegrationTest {

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
        // (completePayment가 재고 부족 보상 분기에서 이력을 남기므로 PaymentHistoryIntegrationTest와
        // 동일한 순서가 필요하다).
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        storeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("재고_부족하면_결제는_REFUNDED_주문은_CANCELLED로_커밋되고_PG_취소가_호출된다")
    void completePayment_재고부족_실제_DB_REFUNDED_CANCELLED() {
        // given
        User user = userRepository.save(User.of("결제자3", "010-7777-8888"));
        Store store = storeRepository.save(Store.create("테스트매장", "서울시 강남구", "02-1234-5678"));
        Product product = productRepository.save(Product.create(
                store, "품절임박상품", "설명", 10_000L, 5,
                ProductStatus.ACTIVE, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)));
        // remainingStock을 주문 수량(2)보다 적게 만든다 — Redis 예약은 성공했는데
        // MySQL 재고가 이미 다른 확정으로 줄어든 상황을 재현한다.
        for (int i = 0; i < 4; i++) {
            product.confirmStock(1);
        }
        productRepository.save(product); // remainingStock = 1

        Order order = orderRepository.save(Order.create(user, 20_000L));
        orderItemRepository.save(OrderItem.create(order, product, 2L));
        Payment payment = paymentRepository.save(
                Payment.initiate(order, "idem-int-stock-1", "pay-int-stock-1", 20_000L));

        String rawJson = "{\"id\":\"pay-int-stock-1\",\"status\":\"PAID\",\"amount\":{\"total\":20000}}";
        given(portOneClient.getPayment("pay-int-stock-1")).willReturn(new PortOnePaymentResult(
                new PortOnePaymentResponse("pay-int-stock-1", "PAID",
                        new PortOnePaymentResponse.Amount(20_000L), OffsetDateTime.now()),
                rawJson));
        given(portOneClient.cancelPayment(eq("pay-int-stock-1"), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(new PortOnePaymentResult(null, "{}"));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment("pay-int-stock-1", PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        // then — 새 조회로 실제 DB 커밋 상태를 확인한다 (같은 영속성 컨텍스트가 아님)
        Payment reloadedPayment = paymentRepository.findByMerchantUid("pay-int-stock-1").orElseThrow();
        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloadedProduct.getRemainingStock()).isEqualTo(1); // 차감되지 않았어야 한다
        org.mockito.Mockito.verify(portOneClient).cancelPayment(eq("pay-int-stock-1"), org.mockito.ArgumentMatchers.anyString());
    }
}
