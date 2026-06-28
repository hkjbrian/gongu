package com.gongu.server.domain.product.service;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockRedisServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private StockRedisService stockRedisService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("reserveStock_정상케이스")
    void reserveStock_정상케이스() {
        // given
        when(valueOperations.decrement("stock:product:1", 10L)).thenReturn(5L);

        // when & then
        assertThatCode(() -> stockRedisService.reserveStock(1L, 10))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reserveStock_재고부족")
    void reserveStock_재고부족() {
        // given
        when(valueOperations.decrement("stock:product:1", 10L)).thenReturn(-1L);

        // when & then
        assertThatThrownBy(() -> stockRedisService.reserveStock(1L, 10))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK));
        verify(valueOperations).increment("stock:product:1", 10L);
    }

    @Test
    @DisplayName("reserveStock_키없음")
    void reserveStock_키없음() {
        // given
        when(valueOperations.decrement("stock:product:1", 10L)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> stockRedisService.reserveStock(1L, 10))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("releaseStock_정상케이스")
    void releaseStock_정상케이스() {
        // when
        stockRedisService.releaseStock(1L, 10);

        // then
        verify(valueOperations).increment("stock:product:1", 10L);
    }

    @Test
    @DisplayName("initializeStock_정상케이스")
    void initializeStock_정상케이스() {
        // when
        stockRedisService.initializeStock(1L, 100);

        // then
        verify(valueOperations).set("stock:product:1", "100");
    }
}
