package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PortOne 응답 원문을 저장하기 전 개인정보를 제거한다.
 * 화이트리스트(default-deny) 방식 — 여기 없는 필드는 스키마가 바뀌어도 자동으로 마스킹된다.
 * 참고: https://developers.portone.io/api/rest-v2/payment
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgResponseMasker {

    private static final String MASKED = "***";

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "id",
            "status",
            "transactionId",
            "currency",
            "amount.total",
            "amount.currency",
            "paidAt",
            "requestedAt",
            "method.type",
            "channel.pgProvider"
    );

    private final ObjectMapper objectMapper;

    public String mask(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) {
            return rawJson;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode masked = maskNode(root, "");
            return objectMapper.writeValueAsString(masked);
        } catch (Exception e) {
            log.warn("PG 응답 원문 마스킹 실패 — 전체 마스킹으로 대체", e);
            return MASKED;
        }
    }

    private JsonNode maskNode(JsonNode node, String path) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                result.set(entry.getKey(), maskNode(entry.getValue(), childPath));
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(element -> result.add(maskNode(element, path)));
            return result;
        }
        if (ALLOWED_PATHS.contains(path)) {
            return node;
        }
        return objectMapper.getNodeFactory().textNode(MASKED);
    }
}
