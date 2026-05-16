package com.gongu.server.domain.store.dto.response;

import com.gongu.server.domain.store.entity.UserStore;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long userId,
        String name,
        String phone,
        LocalDateTime registeredAt
) {

    public static AdminUserResponse from(UserStore userStore) {
        return new AdminUserResponse(
                userStore.getUser().getId(),
                userStore.getUser().getName(),
                userStore.getUser().getPhone(),
                userStore.getCreatedAt()
        );
    }
}
