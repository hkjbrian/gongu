package com.gongu.server.domain.store.dto.response;

import com.gongu.server.domain.store.entity.MemberStore;

import java.time.LocalDateTime;

public record AdminMemberResponse(
        Long memberId,
        String name,
        String phone,
        LocalDateTime registeredAt
) {

    public static AdminMemberResponse from(MemberStore memberStore) {
        return new AdminMemberResponse(
                memberStore.getMember().getId(),
                memberStore.getMember().getName(),
                memberStore.getMember().getPhone(),
                memberStore.getCreatedAt()
        );
    }
}
