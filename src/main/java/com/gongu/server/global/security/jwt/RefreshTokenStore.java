package com.gongu.server.global.security.jwt;

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

    private String key(Long memberId) {
        return "refresh:" + memberId;
    }

    public void save(Long memberId, String refreshToken) {
        stringRedisTemplate.opsForValue()
                .set(key(memberId), refreshToken, refreshExpiration, TimeUnit.MILLISECONDS);
    }

    public Optional<String> get(Long memberId) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key(memberId)));
    }

    public void delete(Long memberId) {
        stringRedisTemplate.delete(key(memberId));
    }
}
