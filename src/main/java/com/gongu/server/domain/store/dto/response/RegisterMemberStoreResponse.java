package com.gongu.server.domain.store.dto.response;

import com.gongu.server.domain.store.entity.MemberStore;

public record RegisterMemberStoreResponse(Long storeId, String storeName, boolean isPreferred) {

    public static RegisterMemberStoreResponse from(MemberStore memberStore) {
        return new RegisterMemberStoreResponse(
                memberStore.getStore().getId(),
                memberStore.getStore().getName(),
                memberStore.isPreferred()
        );
    }
}
