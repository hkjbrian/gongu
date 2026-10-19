package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserStoreCacheServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private UserStoreRepository userStoreRepository;

    @InjectMocks
    private UserStoreCacheService userStoreCacheService;

    @Test
    @DisplayName("existsByUserAndStore_구독중_true반환")
    void existsByUserAndStore_구독중_true반환() {
        // given
        User user = user(1L);
        Store store = store(10L);
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(true);

        // when
        boolean result = userStoreCacheService.existsByUserAndStore(1L, 10L);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("existsByUserAndStore_미구독_false반환")
    void existsByUserAndStore_미구독_false반환() {
        // given
        User user = user(1L);
        Store store = store(10L);
        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(false);

        // when
        boolean result = userStoreCacheService.existsByUserAndStore(1L, 10L);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("existsByUserAndStore_존재하지않는유저_예외")
    void existsByUserAndStore_존재하지않는유저_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userStoreCacheService.existsByUserAndStore(999L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
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

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
