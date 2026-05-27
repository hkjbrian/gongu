package com.gongu.server.global.config;

import com.gongu.server.global.infrastructure.portone.PortOneProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class RestClientConfig {

    /**
     * Spring Boot 자동 구성 RestClient.Builder를 주입받아 사용한다.
     * application.yml의 spring.http.client.connect-timeout / read-timeout 설정이 적용된다.
     */
    @Bean
    public RestClient portOneRestClient(RestClient.Builder restClientBuilder, PortOneProperties props) {
        return restClientBuilder
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "PortOne " + props.apiSecret())
                .build();
    }
}
