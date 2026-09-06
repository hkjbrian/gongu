package com.gongu.server.global.infrastructure.portone;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portone")
public record PortOneProperties(String apiSecret, String baseUrl, String webhookSecret) {
}
