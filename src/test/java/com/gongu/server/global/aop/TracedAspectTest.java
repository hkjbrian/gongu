package com.gongu.server.global.aop;

import com.gongu.server.domain.order.service.OrderService;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.UserStore;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TracedAspectTest {

    private static final String ORDER_SECTION_TIMER = "gongu.order.section";

    @Autowired
    private OrderService orderService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserStoreRepository userStoreRepository;

    @MockitoBean
    private StockRedisService stockRedisService;

    @Test
    @DisplayName("createOrder_AOP_프록시가_order_total과_execution_구간_Timer를_기록한다")
    void createOrder_AOP_프록시가_order_total과_execution_구간_Timer를_기록한다() {
        // given
        User user = userRepository.save(User.of("테스트유저", "010-1234-5678"));
        Store store = storeRepository.save(Store.create("테스트매장", "서울시 강남구", "02-1234-5678"));
        Product product = productRepository.save(Product.create(
                store,
                "테스트상품",
                "설명",
                10_000L,
                10,
                ProductStatus.ACTIVE,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(7)
        ));
        userStoreRepository.save(UserStore.create(user, store, false));

        long orderTotalBefore = timerCount("order_total");
        long storeMembershipBefore = timerCount("store_membership_check");

        // when
        orderService.createOrder(user.getId(), product.getId(), 2);

        // then
        Timer orderTotalTimer = timer("order_total");
        Timer storeMembershipTimer = timer("store_membership_check");

        assertThat(orderTotalTimer.count()).isGreaterThanOrEqualTo(orderTotalBefore + 1);
        assertThat(storeMembershipTimer.count()).isGreaterThanOrEqualTo(storeMembershipBefore + 1);
    }

    private Timer timer(String section) {
        return meterRegistry.get(ORDER_SECTION_TIMER)
                .tag("section", section)
                .timer();
    }

    private long timerCount(String section) {
        Timer timer = meterRegistry.find(ORDER_SECTION_TIMER)
                .tag("section", section)
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
