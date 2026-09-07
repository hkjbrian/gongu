package com.gongu.server.global.infrastructure.portone;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "portone")
public record PortOneProperties(
        String apiSecret,
        String baseUrl,
        String webhookSecret,
        Duration connectTimeout,
        Duration readTimeout
) {
}
