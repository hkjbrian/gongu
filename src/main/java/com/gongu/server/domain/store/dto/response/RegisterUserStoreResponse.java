package com.gongu.server.domain.store.dto.response;

import com.gongu.server.domain.store.entity.UserStore;

public record RegisterUserStoreResponse(Long storeId, String storeName, boolean isPreferred) {

    public static RegisterUserStoreResponse from(UserStore userStore) {
        return new RegisterUserStoreResponse(
                userStore.getStore().getId(),
                userStore.getStore().getName(),
                userStore.isPreferred()
        );
    }
}
