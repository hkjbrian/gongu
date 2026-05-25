package com.gongu.server.domain.order.service;

import com.gongu.server.domain.order.dto.response.ArriveProductResponse;
import com.gongu.server.domain.order.dto.response.OrderDetailResponse;
import com.gongu.server.domain.order.dto.response.OrderSummaryResponse;
import com.gongu.server.domain.order.dto.response.ReceiveOrderResponse;
import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StoreAdminRepository storeAdminRepository;

    @Mock
    private UserStoreRepository userStoreRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("createOrder_정상_주문_생성")
    void createOrder_정상_주문_생성() {
        // given
        User user = user(1L);
        Product product = product(1L, store(1L), 10);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));
        given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(orderItemRepository.save(any(OrderItem.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        OrderDetailResponse result = orderService.createOrder(1L, 1L, 2);

        // then
        assertThat(result.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(result.getTotalPrice()).isEqualTo(20_000L);
        assertThat(product.getRemainingStock()).isEqualTo(8);
        verify(orderRepository).save(any(Order.class));
        verify(orderItemRepository).save(any(OrderItem.class));
    }

    @Test
    @DisplayName("createOrder_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void createOrder_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.createOrder(999L, 1L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("createOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void createOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        User user = user(1L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.createOrder(1L, 999L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("createOrder_미가입_매장_STORE_NOT_JOINED_예외")
    void createOrder_미가입_매장_STORE_NOT_JOINED_예외() {
        // given
        User user = user(1L);
        Product product = product(1L, store(1L), 10);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));
        given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(false);

        // when & then
        assertThatThrownBy(() -> orderService.createOrder(1L, 1L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("createOrder_미가입_매장_주문_시도_재고_차감_없음")
    void createOrder_미가입_매장_주문_시도_재고_차감_없음() {
        // given
        User user = user(1L);
        Product product = product(1L, store(1L), 10);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));
        given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(false);

        // when & then
        assertThatThrownBy(() -> orderService.createOrder(1L, 1L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));

        assertThat(product.getRemainingStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("cancelOrder_정상_주문_취소_및_재고_복원")
    void cancelOrder_정상_주문_취소_및_재고_복원() {
        // given
        User user = user(1L);
        Product product = product(1L, 10);
        product.decreaseStock(2);
        Order order = order(1L, user, 20_000L);
        OrderItem item = orderItem(order, product, 2L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));

        // when
        orderService.cancelOrder(1L, 1L, "단순 변심");

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("단순 변심");
        assertThat(product.getRemainingStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("cancelOrder_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void cancelOrder_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.cancelOrder(999L, 1L, "취소"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("cancelOrder_존재하지_않는_주문_ORDER_NOT_FOUND_예외")
    void cancelOrder_존재하지_않는_주문_ORDER_NOT_FOUND_예외() {
        // given
        User user = user(1L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.cancelOrder(1L, 999L, "취소"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("cancelOrder_타인_주문_ORDER_NOT_FOUND_예외")
    void cancelOrder_타인_주문_ORDER_NOT_FOUND_예외() {
        // given
        User user = user(1L);
        User anotherUser = user(2L);
        Order order = order(1L, anotherUser, 10_000L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> orderService.cancelOrder(1L, 1L, "취소"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("getOrder_정상_주문_상세_반환")
    void getOrder_정상_주문_상세_반환() {
        // given
        User user = user(1L);
        Product product = product(1L, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 1L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdAndUser(1L, user)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        OrderDetailResponse result = orderService.getOrder(1L, 1L);

        // then
        assertThat(result.getOrderId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getProductName()).isEqualTo("상품1");
    }

    @Test
    @DisplayName("getOrder_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void getOrder_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrder(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("getOrder_존재하지_않는_주문_ORDER_NOT_FOUND_예외")
    void getOrder_존재하지_않는_주문_ORDER_NOT_FOUND_예외() {
        // given
        User user = user(1L);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdAndUser(999L, user)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrder(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("getMyOrders_정상_status_null_내_주문_목록_반환")
    void getMyOrders_정상_status_null_내_주문_목록_반환() {
        // given
        User user = user(1L);
        Product product = product(1L, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)).willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order))).willReturn(List.of(item));

        // when
        Page<OrderSummaryResponse> result = orderService.getMyOrders(1L, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting("productName").containsExactly("상품1");
        verify(orderRepository).findAllByUserOrderByCreatedAtDesc(user, pageable);
    }

    @Test
    @DisplayName("getMyOrders_정상_status_RESERVED_내_주문_목록_반환")
    void getMyOrders_정상_status_RESERVED_내_주문_목록_반환() {
        // given
        User user = user(1L);
        Product product = product(1L, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserAndStatusOrderByCreatedAtDesc(user, OrderStatus.RESERVED, pageable))
                .willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order))).willReturn(List.of(item));

        // when
        Page<OrderSummaryResponse> result = orderService.getMyOrders(1L, OrderStatus.RESERVED, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting("status").containsExactly(OrderStatus.RESERVED);
        verify(orderRepository).findAllByUserAndStatusOrderByCreatedAtDesc(user, OrderStatus.RESERVED, pageable);
    }

    @Test
    @DisplayName("getMyOrders_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void getMyOrders_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getMyOrders(999L, null, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("getMyOrders_빈_페이지_IN쿼리_미호출")
    void getMyOrders_빈_페이지_IN쿼리_미호출() {
        // given
        User user = user(1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> emptyPage = Page.empty(pageable);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)).willReturn(emptyPage);

        // when
        Page<OrderSummaryResponse> result = orderService.getMyOrders(1L, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(0);
        verify(orderItemRepository, never()).findAllByOrderInWithProduct(any());
    }

    @Test
    @DisplayName("getMyOrders_OrderItem_누락_ORDER_ITEM_NOT_FOUND_예외")
    void getMyOrders_OrderItem_누락_ORDER_ITEM_NOT_FOUND_예외() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)).willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order))).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> orderService.getMyOrders(1L, null, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    @Test
    @DisplayName("getMyOrders_복수_주문_정상_매핑")
    void getMyOrders_복수_주문_정상_매핑() {
        // given
        User user = user(1L);
        Product product1 = product(1L, 10);
        Product product2 = product(2L, 10);
        Order order1 = order(1L, user, 10_000L);
        Order order2 = order(2L, user, 20_000L);
        OrderItem item1 = orderItem(order1, product1, 1L);
        OrderItem item2 = orderItem(order2, product2, 2L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order1, order2), pageable, 2);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)).willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order1, order2)))
                .willReturn(List.of(item1, item2));

        // when
        Page<OrderSummaryResponse> result = orderService.getMyOrders(1L, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting("productName")
                .containsExactlyInAnyOrder("상품1", "상품2");
    }

    @Test
    @DisplayName("getOrdersByProduct_정상_상품별_주문_목록_반환")
    void getOrdersByProduct_정상_상품별_주문_목록_반환() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        User user = user(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));
        given(orderRepository.findAllByProduct(product, pageable)).willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order))).willReturn(List.of(item));

        // when
        Page<OrderSummaryResponse> result = orderService.getOrdersByProduct(1L, 1L, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting("productName").containsExactly("상품1");
    }

    @Test
    @DisplayName("getOrdersByProduct_존재하지_않는_매장관리자_STORE_ADMIN_NOT_FOUND_예외")
    void getOrdersByProduct_존재하지_않는_매장관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrdersByProduct(999L, 1L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("getOrdersByProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void getOrdersByProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        Pageable pageable = PageRequest.of(0, 20);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(999L, store)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrdersByProduct(1L, 999L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("getOrdersByUser_정상_회원별_주문_목록_반환")
    void getOrdersByUser_정상_회원별_주문_목록_반환() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        User user = user(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 1L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findAllByUserAndStore(user, store, pageable)).willReturn(orderPage);
        given(orderItemRepository.findAllByOrderInWithProduct(List.of(order))).willReturn(List.of(item));

        // when
        Page<OrderSummaryResponse> result = orderService.getOrdersByUser(1L, 1L, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting("productName").containsExactly("상품1");
    }

    @Test
    @DisplayName("getOrdersByUser_존재하지_않는_매장관리자_STORE_ADMIN_NOT_FOUND_예외")
    void getOrdersByUser_존재하지_않는_매장관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrdersByUser(999L, 1L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("getOrdersByUser_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void getOrdersByUser_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        Pageable pageable = PageRequest.of(0, 20);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getOrdersByUser(1L, 999L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("arriveOrder_정상_입고_처리_ARRIVED_전이")
    void arriveOrder_정상_입고_처리_ARRIVED_전이() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        User user = user(1L);
        Product product = product(1L, store, 10);
        product.decreaseStock(2);
        Order order = order(1L, user, 20_000L);
        order.pay();

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));
        given(orderRepository.findAllByProductAndStatus(product, OrderStatus.PAID)).willReturn(List.of(order));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        ArriveProductResponse result = orderService.arriveOrder(1L, 1L);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ARRIVED);
        assertThat(result.arrivedOrderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("arriveOrder_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void arriveOrder_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.arriveOrder(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("arriveOrder_이미_입고된_상품_ARRIVE_NOT_ALLOWED_예외")
    void arriveOrder_이미_입고된_상품_ARRIVE_NOT_ALLOWED_예외() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);
        User user = user(1L);
        Product product = product(1L, store, 10);
        product.decreaseStock(2);
        Order arrivedOrder = order(1L, user, 20_000L);
        arrivedOrder.pay();
        arrivedOrder.arrive();

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));
        given(orderRepository.findAllByProductAndStatus(product, OrderStatus.PAID)).willReturn(List.of());
        given(orderRepository.findAllByProductAndStatus(product, OrderStatus.ARRIVED)).willReturn(List.of(arrivedOrder));

        // when & then
        assertThatThrownBy(() -> orderService.arriveOrder(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ARRIVE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("arriveOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void arriveOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        Store store = store(1L);
        StoreAdmin storeAdmin = storeAdmin(1L, store);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(999L, store)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.arriveOrder(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("receiveOrder_정상_수령_처리_RECEIVED_전이")
    void receiveOrder_정상_수령_처리_RECEIVED_전이() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 20_000L);
        order.pay();
        order.arrive();

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        ReceiveOrderResponse result = orderService.receiveOrder(1L, 1L);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(result.orderId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(result.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("receiveOrder_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void receiveOrder_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.receiveOrder(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("receiveOrder_타인_주문_ORDER_NOT_FOUND_예외")
    void receiveOrder_타인_주문_ORDER_NOT_FOUND_예외() {
        // given
        User user = user(1L);
        User anotherUser = user(2L);
        Order order = order(1L, anotherUser, 10_000L);
        order.pay();
        order.arrive();

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> orderService.receiveOrder(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private User user(Long id) {
        User user = User.of("홍길동" + id, "010-1234-567" + id);
        setId(user, id);
        return user;
    }

    private Store store(Long id) {
        Store store = Store.create("매장" + id, "서울시 강남구", "02-1234-5678");
        setId(store, id);
        return store;
    }

    private StoreAdmin storeAdmin(Long id, Store store) {
        StoreAdmin storeAdmin = StoreAdmin.of(store, "admin" + id + "@test.com", "password", "관리자" + id);
        setId(storeAdmin, id);
        return storeAdmin;
    }

    private Product product(Long id, int totalStock) {
        return product(id, store(1L), totalStock);
    }

    private Product product(Long id, Store store, int totalStock) {
        Product product = Product.create(
                store,
                "상품" + id,
                "상품 설명",
                10_000L,
                totalStock,
                ProductStatus.ACTIVE,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1)
        );
        setId(product, id);
        return product;
    }

    private Order order(Long id, User user, long totalPrice) {
        Order order = Order.create(user, totalPrice);
        setId(order, id);
        return order;
    }

    private OrderItem orderItem(Order order, Product product, Long quantity) {
        OrderItem item = OrderItem.create(order, product, quantity);
        setId(item, 1L);
        return item;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
