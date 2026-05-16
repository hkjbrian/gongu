package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.request.RegisterUserStoreRequest;
import com.gongu.server.domain.store.dto.response.RegisterUserStoreResponse;
import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.UserStore;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStoreRepository userStoreRepository;

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

    // ─── registerUserStore ────────────────────────────────────────────────

    @Test
    @DisplayName("registerUserStore_성공_비선호매장")
    void registerUserStore_성공_비선호매장() {
        // given
        User user = User.of("홍길동", "hong@test.com", "010-1234-5678");
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        RegisterUserStoreRequest request = new RegisterUserStoreRequest(1L, false);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(false);
        given(userStoreRepository.save(any(UserStore.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        RegisterUserStoreResponse response = storeService.registerUserStore(1L, request);

        // then
        assertThat(response.storeName()).isEqualTo("테스트매장"); // storeId는 영속화 전이라 null이므로 storeName으로 검증
        assertThat(response.isPreferred()).isFalse();
        verify(userStoreRepository).save(any(UserStore.class));
    }

    @Test
    @DisplayName("registerUserStore_성공_선호매장_기존선호_해제")
    void registerUserStore_성공_선호매장_기존선호_해제() {
        // given
        User user = User.of("홍길동", "hong@test.com", "010-1234-5678");
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        Store oldStore = Store.create("기존선호매장", "서울시 서초구", "02-9999-8888");
        UserStore existingPreferred = UserStore.create(user, oldStore, true);
        RegisterUserStoreRequest request = new RegisterUserStoreRequest(1L, true);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(false);
        given(userStoreRepository.findByUserAndIsPreferredTrue(user))
                .willReturn(Optional.of(existingPreferred));
        given(userStoreRepository.save(any(UserStore.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        storeService.registerUserStore(1L, request);

        // then
        assertThat(existingPreferred.isPreferred()).isFalse();
        verify(userStoreRepository).save(any(UserStore.class));
    }

    @Test
    @DisplayName("registerUserStore_존재하지_않는_유저_USER_NOT_FOUND_예외")
    void registerUserStore_존재하지_않는_유저_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());
        RegisterUserStoreRequest request = new RegisterUserStoreRequest(1L, false);

        // when & then
        assertThatThrownBy(() -> storeService.registerUserStore(999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("registerUserStore_중복_등록_MEMBER_STORE_DUPLICATE_예외")
    void registerUserStore_중복_등록_MEMBER_STORE_DUPLICATE_예외() {
        // given
        User user = User.of("홍길동", "hong@test.com", "010-1234-5678");
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        RegisterUserStoreRequest request = new RegisterUserStoreRequest(1L, false);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> storeService.registerUserStore(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.MEMBER_STORE_DUPLICATE));
    }

    @Test
    @DisplayName("registerUserStore_존재하지_않는_매장_STORE_NOT_FOUND_예외")
    void registerUserStore_존재하지_않는_매장_STORE_NOT_FOUND_예외() {
        // given
        User user = User.of("홍길동", "hong@test.com", "010-1234-5678");
        RegisterUserStoreRequest request = new RegisterUserStoreRequest(999L, false);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeService.registerUserStore(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
    }
}
