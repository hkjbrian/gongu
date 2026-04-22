package com.gongu.server.global.security.jwt;

import com.gongu.server.global.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    /**
     * Redis 키에 role 접두사를 포함해 Member·StoreAdmin 간 ID 충돌을 방지한다.
     * 예: "refresh:MEMBER:1", "refresh:STORE_ADMIN:1"
     */
    private String key(Long memberId, Role role) {
        return "refresh:" + role.name() + ":" + memberId;
    }

    public void save(Long memberId, Role role, String refreshToken) {
        stringRedisTemplate.opsForValue()
                .set(key(memberId, role), refreshToken, refreshExpiration, TimeUnit.MILLISECONDS);
    }

    public Optional<String> get(Long memberId, Role role) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key(memberId, role)));
    }

    public void delete(Long memberId, Role role) {
        stringRedisTemplate.delete(key(memberId, role));
    }
}
