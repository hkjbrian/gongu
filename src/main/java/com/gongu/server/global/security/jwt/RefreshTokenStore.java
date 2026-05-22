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
     * Redis 키에 role 접두사를 포함해 User·StoreAdmin 간 ID 충돌을 방지한다.
     * 예: "refresh:USER:1", "refresh:STORE_ADMIN:1"
     */
    private String key(Long userId, Role role) {
        return "refresh:" + role.name() + ":" + userId;
    }

    public void save(Long userId, Role role, String refreshToken) {
        stringRedisTemplate.opsForValue()
                .set(key(userId, role), refreshToken, refreshExpiration, TimeUnit.MILLISECONDS);
    }

    public Optional<String> get(Long userId, Role role) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key(userId, role)));
    }

    public void delete(Long userId, Role role) {
        stringRedisTemplate.delete(key(userId, role));
    }
}
