package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserStoreCacheService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final UserStoreRepository userStoreRepository;

    @Cacheable(value = "user-store", key = "#userId + ':' + #storeId")
    public boolean existsByUserAndStore(Long userId, Long storeId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        Store store = storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));

        return userStoreRepository.existsByUserAndStore(user, store);
    }

    @CacheEvict(value = "user-store", key = "#userId + ':' + #storeId")
    public void evict(Long userId, Long storeId) {
    }
}
