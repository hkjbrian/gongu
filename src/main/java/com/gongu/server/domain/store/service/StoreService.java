package com.gongu.server.domain.store.service;

import com.gongu.server.domain.store.dto.request.RegisterMemberStoreRequest;
import com.gongu.server.domain.store.dto.response.RegisterMemberStoreResponse;
import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.entity.MemberStore;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.MemberStoreRepository;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.user.entity.Member;
import com.gongu.server.domain.user.repository.MemberRepository;
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
    private final MemberRepository memberRepository;
    private final MemberStoreRepository memberStoreRepository;

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
    public RegisterMemberStoreResponse registerMemberStore(Long memberId, RegisterMemberStoreRequest request) {
        Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Store store = storeRepository.findByIdAndDeletedAtIsNull(request.storeId())
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));

        if (memberStoreRepository.existsByMemberAndStore(member, store)) {
            throw new BusinessException(StoreErrorCode.MEMBER_STORE_DUPLICATE);
        }

        if (Boolean.TRUE.equals(request.isPreferred())) {
            memberStoreRepository.findByMemberAndIsPreferredTrue(member)
                    .ifPresent(MemberStore::unmarkAsPreferred);
        }

        MemberStore memberStore = MemberStore.create(member, store, request.isPreferred());
        memberStoreRepository.save(memberStore);

        return RegisterMemberStoreResponse.from(memberStore);
    }
}
