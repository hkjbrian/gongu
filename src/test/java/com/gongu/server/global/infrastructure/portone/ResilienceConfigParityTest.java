package com.gongu.server.global.infrastructure.portone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code src/test/resources/application.yml} 은 테스트 클래스패스에서
 * {@code src/main/resources/application.yml} 을 가린다(Spring Boot 는 클래스패스 첫
 * {@code application.yml} 을 사용하고 테스트 리소스가 우선). 따라서
 * {@link PortOneClientResilienceTest} 는 사실상 테스트 사본의 resilience4j 설정만 검증한다.
 *
 * <p>이 테스트는 Spring 컨텍스트 없이 두 yaml 파일을 직접 파싱해
 * {@code resilience4j.circuitbreaker.instances.portone} 와
 * {@code resilience4j.retry.instances.portone} 맵이 두 파일 사이에서 동일한지 검증한다.
 * 누군가 운영 쪽 {@code record-exceptions} 를 되돌리면 여기서 실패한다 (#214).
 *
 * <p>예외 목록(record/retry/ignore-exceptions)은 순서에 의미가 없으므로 Set 으로
 * 정규화해 비교한다. 나머지 스칼라 설정(window size, threshold 등)은 그대로 비교한다.
 */
class ResilienceConfigParityTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");
    private static final Path TEST_YML = Path.of("src/test/resources/application.yml");

    @Test
    @DisplayName("circuitbreaker.instances.portone 설정이 main/test yml 사이에서 동일하다")
    void circuitBreakerPortoneConfig_isInSync() throws IOException {
        assertThat(normalize(portoneNode(load(TEST_YML), "circuitbreaker")))
                .isEqualTo(normalize(portoneNode(load(MAIN_YML), "circuitbreaker")));
    }

    @Test
    @DisplayName("retry.instances.portone 설정이 main/test yml 사이에서 동일하다")
    void retryPortoneConfig_isInSync() throws IOException {
        assertThat(normalize(portoneNode(load(TEST_YML), "retry")))
                .isEqualTo(normalize(portoneNode(load(MAIN_YML), "retry")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object portoneNode(Map<String, Object> root, String component) {
        Map<String, Object> resilience4j = (Map<String, Object>) root.get("resilience4j");
        Map<String, Object> comp = (Map<String, Object>) resilience4j.get(component);
        Map<String, Object> instances = (Map<String, Object>) comp.get("instances");
        Object portone = instances.get("portone");
        assertThat(portone).as("resilience4j.%s.instances.portone", component).isNotNull();
        return portone;
    }

    /** 리스트는 순서 무관 Set 으로, 맵은 재귀적으로 정규화한다. */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.HashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (node instanceof List<?> list) {
            Set<Object> out = new HashSet<>();
            for (Object e : list) {
                out.add(normalize(e));
            }
            return out;
        }
        return node;
    }
}
