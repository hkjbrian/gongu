package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.response.AdminMemberResponse;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.MemberStoreRepository;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
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
public class StoreAdminService {

    private final StoreAdminRepository storeAdminRepository;
    private final MemberStoreRepository memberStoreRepository;

    public Page<AdminMemberResponse> getMembers(Long storeAdminId, String nameFilter, Pageable pageable) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();
        String filter = (nameFilter != null && !nameFilter.isBlank()) ? nameFilter : null;

        return memberStoreRepository.findAllByStoreWithMember(store, filter, pageable)
                .map(AdminMemberResponse::from);
    }
}
