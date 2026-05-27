package com.gongu.server.global.config;

import com.gongu.server.global.infrastructure.portone.PortOneProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient portOneRestClient(PortOneProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "PortOne " + props.apiSecret())
                .build();
    }
}
