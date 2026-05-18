package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.response.AdminUserResponse;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.entity.UserStore;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
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
class StoreAdminServiceTest {

    @Mock
    private StoreAdminRepository storeAdminRepository;

    @Mock
    private UserStoreRepository userStoreRepository;

    @InjectMocks
    private StoreAdminService storeAdminService;

    @Test
    @DisplayName("getUsers_정상_전체_목록_반환")
    void getUsers_정상_전체_목록_반환() {
        // given
        Long storeAdminId = 1L;
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        StoreAdmin storeAdmin = StoreAdmin.of(store, "admin@test.com", "encoded", "관리자");

        User user1 = User.of("홍길동", "010-1111-2222");
        User user2 = User.of("김철수", "010-3333-4444");
        UserStore userStore1 = UserStore.create(user1, store, false);
        UserStore userStore2 = UserStore.create(user2, store, false);

        Pageable pageable = PageRequest.of(0, 20);
        Page<UserStore> userStorePage = new PageImpl<>(List.of(userStore1, userStore2), pageable, 2);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)).willReturn(Optional.of(storeAdmin));
        given(userStoreRepository.findAllByStoreWithUser(store, null, pageable)).willReturn(userStorePage);

        // when
        Page<AdminUserResponse> result = storeAdminService.getUsers(storeAdminId, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting("name")
                .containsExactly("홍길동", "김철수");
    }

    @Test
    @DisplayName("getUsers_이름_필터링_일치하는_회원만_반환")
    void getUsers_이름_필터링_일치하는_회원만_반환() {
        // given — DB가 nameFilter로 이미 필터된 결과를 반환하는 상황을 모킹
        Long storeAdminId = 1L;
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        StoreAdmin storeAdmin = StoreAdmin.of(store, "admin@test.com", "encoded", "관리자");

        User user1 = User.of("홍길동", "010-1111-2222");
        UserStore userStore1 = UserStore.create(user1, store, false);

        Pageable pageable = PageRequest.of(0, 20);
        Page<UserStore> filteredPage = new PageImpl<>(List.of(userStore1), pageable, 1);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)).willReturn(Optional.of(storeAdmin));
        given(userStoreRepository.findAllByStoreWithUser(store, "홍", pageable)).willReturn(filteredPage);

        // when
        Page<AdminUserResponse> result = storeAdminService.getUsers(storeAdminId, "홍", pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("getUsers_존재하지_않는_어드민_STORE_ADMIN_NOT_FOUND_예외")
    void getUsers_존재하지_않는_어드민_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        Long storeAdminId = 999L;
        Pageable pageable = PageRequest.of(0, 20);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeAdminService.getUsers(storeAdminId, null, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }
}
