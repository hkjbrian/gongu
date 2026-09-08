package com.gongu.server.global.config;

import com.gongu.server.global.infrastructure.portone.PortOneProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class RestClientConfig {

    /**
     * PortOne 전용 RestClient. 전역 spring.http.client 타임아웃 대신
     * portone.connect-timeout / portone.read-timeout 을 적용한다 (#222).
     * KakaoApiClient 등 다른 소비자는 주입받은 별도 빌더 인스턴스로 전역 설정을 그대로 쓴다.
     */
    @Bean
    public RestClient portOneRestClient(RestClient.Builder restClientBuilder, PortOneProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.connectTimeout())
                .withReadTimeout(props.readTimeout());

        return restClientBuilder
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "PortOne " + props.apiSecret())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
