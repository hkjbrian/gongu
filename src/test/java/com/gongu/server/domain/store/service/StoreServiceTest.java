package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreService storeService;

    @Test
    @DisplayName("getStores_활성_매장_목록_반환")
    void getStores_활성_매장_목록_반환() {
        // given
        Store store1 = Store.create("매장1", "서울시 강남구", "02-1234-5678");
        Store store2 = Store.create("매장2", "서울시 서초구", "02-9876-5432");
        Pageable pageable = PageRequest.of(0, 20);
        Page<Store> storePage = new PageImpl<>(List.of(store1, store2), pageable, 2);

        given(storeRepository.findAllByIsActiveTrueAndDeletedAtIsNull(pageable)).willReturn(storePage);

        // when
        Page<StoreResponse> result = storeService.getStores(pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting("name")
                .containsExactly("매장1", "매장2");
    }

    @Test
    @DisplayName("getStore_존재하는_매장_반환")
    void getStore_존재하는_매장_반환() {
        // given
        Store store = Store.create("테스트매장", "서울시 마포구", "02-1111-2222");
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));

        // when
        StoreResponse result = storeService.getStore(1L);

        // then
        assertThat(result.name()).isEqualTo("테스트매장");
        assertThat(result.address()).isEqualTo("서울시 마포구");
        assertThat(result.phone()).isEqualTo("02-1111-2222");
    }

    @Test
    @DisplayName("getStore_삭제된_매장_STORE_NOT_FOUND_예외")
    void getStore_삭제된_매장_STORE_NOT_FOUND_예외() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeService.getStore(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getErrorCode()).isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("getStore_존재하지_않는_ID_STORE_NOT_FOUND_예외")
    void getStore_존재하지_않는_ID_STORE_NOT_FOUND_예외() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeService.getStore(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getErrorCode().getCode())
                            .isEqualTo(StoreErrorCode.STORE_NOT_FOUND.getCode());
                });
    }
}
