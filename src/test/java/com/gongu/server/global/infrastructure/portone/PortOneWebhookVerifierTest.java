package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
    @DisplayName("다른 시크릿으로 만든 서명 → 거부")
    void wrongSecretSignature_rejected() {
        String ts = now();
        String bogus = "v1," + Base64.getEncoder().encodeToString("not-a-real-sig".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, bogus))
                .isInstanceOf(BusinessException.class);
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
    @DisplayName("시크릿 미설정 → 모든 웹훅 거부 (fail-closed)")
    void secretNotConfigured_rejectsAll() throws Exception {
        PortOneWebhookVerifier noSecret =
                new PortOneWebhookVerifier(new PortOneProperties(null, null, "  "));
        String ts = now();
        assertThatThrownBy(() -> noSecret.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }
}
