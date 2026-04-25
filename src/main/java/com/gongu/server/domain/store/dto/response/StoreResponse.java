package com.gongu.server.domain.store.dto.response;

import com.gongu.server.domain.store.entity.Store;

public record StoreResponse(Long id, String name, String address, String phone) {

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getPhone()
        );
    }
}
