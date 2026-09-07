package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneWebhookVerifierTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

    private final PortOneWebhookVerifier verifier =
            new PortOneWebhookVerifier(new PortOneProperties(null, null, SECRET));

    private static String sign(String id, String timestamp, String body) throws Exception {
        byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] sig = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(sig);
    }

    private static String now() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    @Test
    @DisplayName("유효한 서명 + 최신 타임스탬프 → 통과")
    void validSignature_passes() throws Exception {
        String ts = now();
        assertThatCode(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("여러 서명 토큰 중 하나만 유효해도 통과")
    void oneValidTokenAmongMany_passes() throws Exception {
        String ts = now();
        String header = "v1,AAAA " + sign("msg_1", ts, BODY);
        assertThatCode(() -> verifier.verify(BODY, "msg_1", ts, header))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("바디 변조 → WEBHOOK_VERIFICATION_FAILED")
    void tamperedBody_rejected() throws Exception {
        String ts = now();
        String sig = sign("msg_1", ts, BODY);
        assertThatThrownBy(() -> verifier.verify(BODY + " ", "msg_1", ts, sig))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("서명이 아닌 임의 바이트 토큰 → 거부")
    void garbageSignatureToken_rejected() {
        String ts = now();
        String bogus = "v1," + Base64.getEncoder().encodeToString("not-a-real-sig".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, bogus))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Standard Webhooks 스펙 골든 벡터 → 통과 (하드코딩 기대 서명, 고정 클럭)")
    void standardWebhooksSpecVector_passes() {
        String specSecret = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
        String id = "msg_p5jXN8AQM9LWM0D4loKWxJek";
        String timestamp = "1614265330";
        String body = "{\"test\": 2432232314}";
        String signatureHeader = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

        Clock fixed = Clock.fixed(Instant.ofEpochSecond(1614265330), ZoneOffset.UTC);
        PortOneWebhookVerifier specVerifier =
                new PortOneWebhookVerifier(new PortOneProperties(null, null, specSecret), fixed);

        assertThatCode(() -> specVerifier.verify(body, id, timestamp, signatureHeader))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서명 헤더 누락(null) → 거부")
    void missingSignatureHeader_rejected() {
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", now(), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("webhook-id 누락(빈 문자열) → 거부")
    void missingId_rejected() throws Exception {
        String ts = now();
        assertThatThrownBy(() -> verifier.verify(BODY, "  ", ts, sign("", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("오래된 타임스탬프(-400초) → 거부 (재전송 방지)")
    void staleTimestamp_rejected() throws Exception {
        String ts = Long.toString(System.currentTimeMillis() / 1000L - 400);
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("미래 타임스탬프(+400초) → 거부")
    void futureTimestamp_rejected() throws Exception {
        String ts = Long.toString(System.currentTimeMillis() / 1000L + 400);
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("숫자가 아닌 타임스탬프 → 거부")
    void nonNumericTimestamp_rejected() throws Exception {
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", "not-a-number", sign("msg_1", "not-a-number", BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 시크릿으로 만든 정상 형식 서명 → 거부")
    void signatureFromDifferentSecret_rejected() throws Exception {
        String ts = now();
        byte[] otherKey = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(otherKey, "HmacSHA256"));
        String sig = "v1," + Base64.getEncoder().encodeToString(
                mac.doFinal(("msg_1." + ts + "." + BODY).getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, sig))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("시크릿 미설정 → 모든 웹훅 거부 (fail-closed)")
    void secretNotConfigured_rejectsAll() throws Exception {
        PortOneWebhookVerifier noSecret =
                new PortOneWebhookVerifier(new PortOneProperties(null, null, "  "));
        String ts = now();
        assertThatThrownBy(() -> noSecret.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }
}
