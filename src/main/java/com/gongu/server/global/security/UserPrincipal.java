package com.gongu.server.global.security;

/**
 * Authentication의 principal로 사용되는 단순 DTO.
 * Spring Security UserDetails를 구현하지 않는다.
 */
public record UserPrincipal(Long memberId, Role role) {
}
