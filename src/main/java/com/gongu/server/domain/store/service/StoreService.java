package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
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

    public Page<StoreResponse> getStores(Pageable pageable) {
        return storeRepository.findAllByIsActiveTrueAndDeletedAtIsNull(pageable)
                .map(StoreResponse::from);
    }

    public StoreResponse getStore(Long storeId) {
        return storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .map(StoreResponse::from)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));
    }
}
