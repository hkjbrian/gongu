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
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
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
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(result.amount()).isEqualTo(AMOUNT);
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
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);

        // then
        assertThat(result).isNotNull();
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
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
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
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

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
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

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
}
