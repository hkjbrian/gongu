package com.gongu.server.domain.payment.controller;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 테스트에서 Standard Webhooks 서명 헤더를 생성한다.
 * 운영 시크릿과 무관한 고정 테스트 시크릿을 사용한다 (src/test/resources/application.yml 과 동일).
 */
final class WebhookSignatures {

    static final String TEST_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    private WebhookSignatures() {
    }

    /** 유효한 서명 + 현재 타임스탬프 헤더를 부착하는 RequestPostProcessor. */
    static RequestPostProcessor signedHeaders(String body) {
        String id = "msg_test";
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String signature = "v1," + sign(id + "." + timestamp + "." + body);
        return request -> {
            request.addHeader("webhook-id", id);
            request.addHeader("webhook-timestamp", timestamp);
            request.addHeader("webhook-signature", signature);
            return request;
        };
    }

    private static String sign(String signedContent) {
        try {
            byte[] key = Base64.getDecoder().decode(TEST_SECRET.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
