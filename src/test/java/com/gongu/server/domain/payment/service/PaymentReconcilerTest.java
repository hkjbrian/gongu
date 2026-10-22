package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentReconcilerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentHistoryRecorder paymentHistoryRecorder;

    private Counter paymentCompletedCounter;
    private Counter paymentFailedOrderExpiredIdempotentCounter;
    private Counter paymentFailedOrderExpiredCancelCounter;
    private Counter paymentFailedPgErrorCounter;
    private Counter paymentFailedPgNullCounter;
    private Counter paymentFailedPgStatusMismatchCounter;
    private Counter paymentFailedAmountMismatchCounter;
    private Counter paymentFailedInsufficientStockCounter;
    private Counter paymentFetchedUnderLockCounter;

    private PaymentReconciler reconciler;

    private Order order;

    private static final Long ORDER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        paymentCompletedCounter = Counter.builder("gongu.payment.completed").register(meterRegistry);
        paymentFailedOrderExpiredIdempotentCounter = paymentFailedCounter(meterRegistry, "order_expired_idempotent");
        paymentFailedOrderExpiredCancelCounter = paymentFailedCounter(meterRegistry, "order_expired_cancel");
        paymentFailedPgErrorCounter = paymentFailedCounter(meterRegistry, "pg_error");
        paymentFailedPgNullCounter = paymentFailedCounter(meterRegistry, "pg_null_response");
        paymentFailedPgStatusMismatchCounter = paymentFailedCounter(meterRegistry, "pg_status_mismatch");
        paymentFailedAmountMismatchCounter = paymentFailedCounter(meterRegistry, "amount_mismatch");
        paymentFailedInsufficientStockCounter = paymentFailedCounter(meterRegistry, "insufficient_stock");
        paymentFetchedUnderLockCounter = Counter.builder("gongu.payment.pg_fetch_under_lock").register(meterRegistry);
        reconciler = new PaymentReconciler(
                orderRepository, orderItemRepository, productRepository, paymentRepository,
                stockRedisService, portOneClient, paymentHistoryRecorder,
                paymentCompletedCounter,
                paymentFailedOrderExpiredIdempotentCounter,
                paymentFailedOrderExpiredCancelCounter,
                paymentFailedPgErrorCounter,
                paymentFailedPgNullCounter,
                paymentFailedPgStatusMismatchCounter,
                paymentFailedAmountMismatchCounter,
                paymentFailedInsufficientStockCounter,
                paymentFetchedUnderLockCounter
        );

        order = Mockito.mock(Order.class);
        lenient().when(order.getId()).thenReturn(ORDER_ID);
        lenient().when(order.getTotalPrice()).thenReturn(AMOUNT);
    }

    private Counter paymentFailedCounter(SimpleMeterRegistry meterRegistry, String reason) {
        return Counter.builder("gongu.payment.failed")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    @Test
    @DisplayName("completePayment_성공_금액일치")
    void completePayment_성공_금액일치() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        VerifyPaymentResponse response = ((PaymentCompletionResult.Confirmed) result).response();
        assertThat(response).isNotNull();
        assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        InOrder inOrder = Mockito.inOrder(order, payment);
        inOrder.verify(order).pay();
        inOrder.verify(payment).confirm(eq(AMOUNT), any(LocalDateTime.class));
        verify(productRepository).findByIdWithLock(1L);
        verify(lockedProduct).confirmStock(2);
    }

    @Test
    @DisplayName("completePayment_prefetched_결과를_그대로_사용하면_PG를_다시_호출하지_않는다")
    void completePayment_prefetched_결과_사용() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        PortOnePaymentResult prefetched = new PortOnePaymentResult(portOneResponse, rawBody);
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);

        // then
        assertThat(((PaymentCompletionResult.Confirmed) result).response()).isNotNull();
        verify(order).pay();
        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    @DisplayName("completePayment_멱등_이미PAID")
    void completePayment_멱등_이미PAID() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PAID);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        // when
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        VerifyPaymentResponse response = ((PaymentCompletionResult.Confirmed) result).response();
        assertThat(response).isNotNull();
        assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_Payment_없음")
    void completePayment_Payment_없음() {
        // given
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("completePayment_상태_PENDING_아님")
    void completePayment_상태_PENDING_아님() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.FAILED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_PG조회_InfraException_전파 — payment는 PENDING 유지 (fail 미호출)")
    void completePayment_PortOne_InfraException() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).fail();
    }

    @Test
    @DisplayName("completePayment_PG_빈응답 — PAYMENT_PG_UNAVAILABLE + payment는 PENDING 유지 (fail 미호출)")
    void completePayment_PG_빈응답_PENDING_유지() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        verify(payment, never()).fail();
    }

    @Test
    @DisplayName("completePayment_조회실패후_재시도시_정상확정 — 1회차 InfraException(PENDING 유지), 2회차 PAID로 수렴")
    void completePayment_조회실패_재시도_정상확정() {
        // given — 실제 Payment 엔티티로 상태 전이를 검증한다 (mock 고정 stub이 아님)
        Payment payment = Payment.initiate(order, "idem-key-207", PAYMENT_ID, AMOUNT);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse paidResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .willReturn(new PortOnePaymentResult(paidResponse, rawBody));
        // 참고: 원본 테스트(PaymentServiceTest.completePayment_조회실패_재시도_정상확정)와 동일하게
        // order.getStatus()를 명시적으로 stub한다 (setUp()의 lenient 기본값과 값은 같지만, 원본 기준을 그대로 따름).

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when — 1회차: InfraException 전파, payment는 PENDING 그대로
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        // when — 2회차: PG 복구 후 정상 확정으로 수렴
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(((PaymentCompletionResult.Confirmed) result).response()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(order).pay();
        verify(lockedProduct).confirmStock(2);
    }

    @Test
    @DisplayName("completePayment_PortOne_status_미완료")
    void completePayment_PortOne_status_미완료() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "FAILED", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"FAILED\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        verify(payment).fail();
    }

    @Test
    @DisplayName("completePayment_금액불일치_보상처리")
    void completePayment_금액불일치_보상처리() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        Long mismatchAmount = 5_000L;
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(mismatchAmount), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("completePayment_재고부족_보상처리")
    void completePayment_재고부족_보상처리() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(1);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        verify(order, never()).pay();
        verify(payment, never()).confirm(any(), any());
        verify(lockedProduct, never()).confirmStock(anyInt());
        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("completePayment_재고부족_PG취소실패시_상태불변_InfraException_전파")
    void completePayment_재고부족_PG취소실패_상태불변() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(1);

        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
        verify(order, never()).cancel(anyString());
        verify(stockRedisService, never()).releaseStockAfterCommit(any(), anyInt());
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_환불_성공")
    void completePayment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResult.class));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_PG결제없음_환불미호출")
    void completePayment_ORDER_EXPIRED_PG결제없음_환불미호출() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment, never()).refund();
        verify(payment).expire();
    }

    @Test
    @DisplayName("completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공")
    void completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.CANCELLED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResult.class));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_REFUNDED_CANCELLED_Order_멱등_ORDER_EXPIRED_REFUNDED")
    void completePayment_REFUNDED_CANCELLED_Order_멱등_ORDER_EXPIRED_REFUNDED() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.REFUNDED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
        verify(payment, never()).refund();
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파")
    void completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
    }

    // ===== 공백 분기·PG 취소 변형·다중 상품 특성화 테스트 (#249) =====

    @Test
    @DisplayName("completePayment_주문없음_ORDER_NOT_FOUND")
    void completePayment_주문없음_ORDER_NOT_FOUND() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));

        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_상품없음_PRODUCT_NOT_FOUND")
    void completePayment_상품없음_PRODUCT_NOT_FOUND() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.empty());

        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, "{\"status\":\"PAID\"}");

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));

        verify(order, never()).pay();
        verify(payment, never()).confirm(any(), any());
        verify(payment, never()).refund();
    }

    @Test
    @DisplayName("completePayment_주문CANCELLED_결제FAILED_INVALID_STATE_TRANSITION")
    void completePayment_주문CANCELLED_결제FAILED_INVALID_STATE_TRANSITION() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.FAILED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    @DisplayName("completePayment_주문CANCELLED_CANCELLED결제_PG결제없음_환불도_만료도_하지_않는다")
    void completePayment_주문CANCELLED_CANCELLED결제_PG결제없음_환불도_만료도_하지_않는다() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.CANCELLED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(payment, never()).refund();
        verify(payment, never()).expire();
        verifyNoInteractions(paymentHistoryRecorder);
    }

    @Test
    @DisplayName("completePayment_금액불일치_PG취소실패_상태불변")
    void completePayment_금액불일치_PG취소실패_상태불변() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        PortOnePaymentResult prefetched = pgResult("PAID", 5_000L, "{\"status\":\"PAID\"}");

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
        verify(order, never()).cancel(anyString());
        verify(stockRedisService, never()).releaseStockAfterCommit(any(), anyInt());
    }

    @Test
    @DisplayName("completePayment_PG취소_ALREADY_PROCESSED면_취소된_것으로_보고_환불을_진행한다")
    void completePayment_PG취소_ALREADY_PROCESSED면_취소된_것으로_보고_환불을_진행한다() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        stubSingleOrderItem(2L, 1);
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));

        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, "{\"status\":\"PAID\"}");

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("completePayment_PG취소_알수없는_BusinessException은_그대로_전파하고_상태불변")
    void completePayment_PG취소_알수없는_BusinessException은_그대로_전파하고_상태불변() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        stubSingleOrderItem(2L, 1);
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, "{\"status\":\"PAID\"}");

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        verify(payment, never()).refund();
        verify(order, never()).cancel(anyString());
    }

    @Test
    @DisplayName("completePayment_다중상품_뒤상품_재고부족_어느상품도_차감되지_않는다")
    void completePayment_다중상품_뒤상품_재고부족_어느상품도_차감되지_않는다() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        OrderItem itemA = Mockito.mock(OrderItem.class);
        Product orderProductA = Mockito.mock(Product.class);
        Product lockedA = Mockito.mock(Product.class);
        OrderItem itemB = Mockito.mock(OrderItem.class);
        Product orderProductB = Mockito.mock(Product.class);
        Product lockedB = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(itemA, itemB));
        given(itemA.getProduct()).willReturn(orderProductA);
        given(orderProductA.getId()).willReturn(1L);
        given(itemA.getQuantity()).willReturn(2L);
        given(itemB.getProduct()).willReturn(orderProductB);
        given(orderProductB.getId()).willReturn(2L);
        given(itemB.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedA));
        given(productRepository.findByIdWithLock(2L)).willReturn(Optional.of(lockedB));
        given(lockedA.getRemainingStock()).willReturn(10);
        given(lockedB.getRemainingStock()).willReturn(1);

        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, "{\"status\":\"PAID\"}");

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        verify(lockedA, never()).confirmStock(anyInt());
        verify(lockedB, never()).confirmStock(anyInt());
        verify(order, never()).pay();
        verify(payment).refund();
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
        verify(stockRedisService).releaseStockAfterCommit(2L, 2);
    }

    // ===== 이력·카운터 단언 테스트 (실제 Payment 사용, #249) =====

    @Test
    @DisplayName("completePayment_확정_이력과_완료카운터")
    void completePayment_확정_이력과_완료카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        Product lockedProduct = stubSingleOrderItem(2L, 10);
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, rawBody);

        // when
        reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, rawBody);
        verify(lockedProduct).confirmStock(2);
        assertThat(paymentCompletedCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_PG상태불일치_FAILED_이력과_카운터")
    void completePayment_PG상태불일치_FAILED_이력과_카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"FAILED\"}";
        PortOnePaymentResult prefetched = pgResult("FAILED", AMOUNT, rawBody);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.FAILED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "PG 상태 불일치: FAILED", rawBody);
        assertThat(paymentFailedPgStatusMismatchCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_금액불일치_REFUNDED_이력은_PG취소_원문과_카운터")
    void completePayment_금액불일치_REFUNDED_이력은_PG취소_원문과_카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        stubOrderItemsOnly(2L);
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        String cancelRawBody = "{\"cancellation\":\"mismatch\"}";
        given(portOneClient.cancelPayment(PAYMENT_ID, "결제 금액 불일치"))
                .willReturn(new PortOnePaymentResult(null, cancelRawBody));
        PortOnePaymentResult prefetched = pgResult("PAID", 5_000L, rawBody);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "결제 금액 불일치", cancelRawBody);
        verify(order).cancel("결제 금액 불일치");
        assertThat(paymentFailedAmountMismatchCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_금액불일치_PG취소_원문이_null이면_PG조회_원문으로_폴백한다")
    void completePayment_금액불일치_PG취소_원문이_null이면_PG조회_원문으로_폴백한다() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        stubOrderItemsOnly(2L);
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.cancelPayment(PAYMENT_ID, "결제 금액 불일치"))
                .willReturn(new PortOnePaymentResult(null, null));
        PortOnePaymentResult prefetched = pgResult("PAID", 5_000L, rawBody);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "결제 금액 불일치", rawBody);
        assertThat(paymentFailedAmountMismatchCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_재고부족_REFUNDED_이력은_PG취소_원문과_카운터")
    void completePayment_재고부족_REFUNDED_이력은_PG취소_원문과_카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        stubSingleOrderItem(2L, 1);
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        String cancelRawBody = "{\"cancellation\":\"stock\"}";
        given(portOneClient.cancelPayment(PAYMENT_ID, "재고 부족으로 인한 자동 환불"))
                .willReturn(new PortOnePaymentResult(null, cancelRawBody));
        PortOnePaymentResult prefetched = pgResult("PAID", AMOUNT, rawBody);

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "재고 부족으로 인한 자동 환불", cancelRawBody);
        verify(order).cancel("재고 부족으로 인한 자동 환불");
        assertThat(paymentFailedInsufficientStockCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_주문만료_REFUNDED_이력과_만료카운터")
    void completePayment_주문만료_REFUNDED_이력과_만료카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.CANCELLED);
        String cancelRawBody = "{\"cancellation\":\"expired\"}";
        given(portOneClient.cancelPayment(PAYMENT_ID, "주문 만료로 인한 자동 환불"))
                .willReturn(new PortOnePaymentResult(null, cancelRawBody));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "주문 만료로 인한 자동 환불", cancelRawBody);
        assertThat(paymentFailedOrderExpiredCancelCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_주문만료_PG에결제없음_CANCELLED_이력과_만료카운터")
    void completePayment_주문만료_PG에결제없음_CANCELLED_이력과_만료카운터() {
        // given
        Payment payment = stubRealPayment(OrderStatus.CANCELLED);
        given(portOneClient.cancelPayment(PAYMENT_ID, "주문 만료로 인한 자동 환불"))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.CANCELLED,
                PaymentHistoryTrigger.CLIENT_VERIFY, "주문 만료 - PG에 결제 없음", null);
        assertThat(paymentFailedOrderExpiredCancelCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_주문만료_멱등_이력없이_멱등카운터만_증가")
    void completePayment_주문만료_멱등_이력없이_멱등카운터만_증가() {
        // given
        Payment payment = stubRealPayment(OrderStatus.CANCELLED);
        payment.refund(); // REFUNDED + order CANCELLED

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verifyNoInteractions(paymentHistoryRecorder);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
        assertThat(paymentFailedOrderExpiredIdempotentCounter.count()).isEqualTo(1.0);
        assertThat(paymentFailedOrderExpiredCancelCounter.count()).isZero();
    }

    @Test
    @DisplayName("completePayment_prefetched_null이면_락_하_직접조회_카운터가_증가한다")
    void completePayment_prefetched_null이면_락_하_직접조회_카운터가_증가한다() {
        // given
        stubRealPayment(OrderStatus.RESERVED);
        given(portOneClient.getPayment(PAYMENT_ID))
                .willReturn(pgResult("FAILED", AMOUNT, "{\"status\":\"FAILED\"}"));

        // when & then
        PaymentCompletionResult result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);
        assertThat(result).isEqualTo(new PaymentCompletionResult.Rejected(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        verify(portOneClient).getPayment(PAYMENT_ID);
        assertThat(paymentFetchedUnderLockCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("completePayment_PG조회_InfraException이면_PG에러카운터가_증가하고_PENDING_유지")
    void completePayment_PG조회_InfraException이면_PG에러카운터가_증가하고_PENDING_유지() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentFailedPgErrorCounter.count()).isEqualTo(1.0);
        verifyNoInteractions(paymentHistoryRecorder);
    }

    @Test
    @DisplayName("completePayment_PG빈응답이면_PG널카운터가_증가하고_PENDING_유지")
    void completePayment_PG빈응답이면_PG널카운터가_증가하고_PENDING_유지() {
        // given
        Payment payment = stubRealPayment(OrderStatus.RESERVED);
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentFailedPgNullCounter.count()).isEqualTo(1.0);
        verifyNoInteractions(paymentHistoryRecorder);
    }

    // ===== 헬퍼 =====

    private PortOnePaymentResult pgResult(String status, long amount, String rawBody) {
        return new PortOnePaymentResult(
                new PortOnePaymentResponse(PAYMENT_ID, status, new PortOnePaymentResponse.Amount(amount),
                        OffsetDateTime.now()),
                rawBody);
    }

    /** 실제 Payment(PENDING)를 만들고 락 조회 stub과 주문 상태를 설정한다. */
    private Payment stubRealPayment(OrderStatus orderStatus) {
        Payment payment = Payment.initiate(order, "idem-key", PAYMENT_ID, AMOUNT);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(order.getStatus()).willReturn(orderStatus);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        return payment;
    }

    /** 주문 항목 1개(상품 id 1L) + 락 후 재고가 lockedStock인 상품을 stub하고 락 상품을 반환한다. */
    private Product stubSingleOrderItem(long quantity, int lockedStock) {
        Product lockedProduct = Mockito.mock(Product.class);
        stubOrderItemsOnly(quantity);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(lockedStock);
        return lockedProduct;
    }

    /** 주문 항목 1개(상품 id 1L)만 stub한다 — 재고 락 조회 이전에 끝나는 분기용. */
    private void stubOrderItemsOnly(long quantity) {
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(quantity);
    }
}
