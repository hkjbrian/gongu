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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final UserStoreRepository userStoreRepository;

    public Page<StoreResponse> getStores(Pageable pageable) {
        return storeRepository.findAllByIsActiveTrueAndDeletedAtIsNull(pageable)
                .map(StoreResponse::from);
    }

    public StoreResponse getStore(Long storeId) {
        return storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .map(StoreResponse::from)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));
    }

    @Transactional
    public RegisterUserStoreResponse registerUserStore(Long userId, RegisterUserStoreRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Store store = storeRepository.findByIdAndDeletedAtIsNull(request.storeId())
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));

        if (userStoreRepository.existsByUserAndStore(user, store)) {
            throw new BusinessException(StoreErrorCode.USER_STORE_DUPLICATE);
        }

        if (Boolean.TRUE.equals(request.isPreferred())) {
            userStoreRepository.findByUserAndIsPreferredTrue(user)
                    .ifPresent(UserStore::unmarkAsPreferred);
        }

        UserStore userStore = UserStore.create(user, store, request.isPreferred());
        userStoreRepository.save(userStore);

        return RegisterUserStoreResponse.from(userStore);
    }
}
