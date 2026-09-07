package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * PortOne V2 웹훅 서명 검증 — Standard Webhooks 스펙(https://www.standardwebhooks.com/) 준수.
 *
 * <ul>
 *   <li>서명 대상: {@code "{webhook-id}.{webhook-timestamp}.{원문 바디}"}</li>
 *   <li>키: 시크릿에서 {@code whsec_} 접두사(있으면) 제거 후 Base64 디코드한 바이트</li>
 *   <li>알고리즘: HMAC-SHA256 → Base64 인코딩</li>
 *   <li>{@code webhook-signature} 헤더: 공백 구분 {@code {버전},{base64 서명}} 토큰 목록. 하나라도 일치하면 통과</li>
 *   <li>타임스탬프 허용 오차: ±300초 (재전송 공격 차단)</li>
 * </ul>
 */
@Slf4j
@Component
public class PortOneWebhookVerifier {

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;
    private static final String SECRET_PREFIX = "whsec_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 시크릿 미설정 시 null — 모든 웹훅을 fail-closed로 거부한다. */
    private final byte[] secretKey;
    private final Clock clock;

    @Autowired
    public PortOneWebhookVerifier(PortOneProperties properties) {
        this(properties, Clock.systemUTC());
    }

    PortOneWebhookVerifier(PortOneProperties properties, Clock clock) {
        this.clock = clock;
        String secret = properties.webhookSecret();
        if (!StringUtils.hasText(secret)) {
            this.secretKey = null;
            log.error("portone.webhook-secret 미설정 — /payments/webhook 모든 요청을 거부한다 (fail-closed)");
            return;
        }
        String base64 = secret.startsWith(SECRET_PREFIX)
                ? secret.substring(SECRET_PREFIX.length())
                : secret;
        try {
            this.secretKey = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "portone.webhook-secret 값이 올바른 Base64가 아닙니다 (PortOne 콘솔에서 발급된 시크릿을 그대로 주입하세요)", e);
        }
    }

    /**
     * @throws BusinessException {@link PaymentErrorCode#WEBHOOK_VERIFICATION_FAILED}
     *         — 시크릿 미설정 · 헤더 누락 · 타임스탬프 형식 오류/허용 오차 초과 · 서명 불일치
     */
    public void verify(String rawBody, String webhookId, String webhookTimestamp, String webhookSignature) {
        if (secretKey == null) {
            log.warn("웹훅 시크릿 미설정 상태에서 요청 수신 — 거부");
            throw reject();
        }
        if (rawBody == null
                || !StringUtils.hasText(webhookId)
                || !StringUtils.hasText(webhookTimestamp)
                || !StringUtils.hasText(webhookSignature)) {
            log.warn("웹훅 필수 헤더/바디 누락");
            throw reject();
        }

        verifyTimestamp(webhookTimestamp);

        byte[] expected = hmacSha256(webhookId + "." + webhookTimestamp + "." + rawBody);

        for (String token : webhookSignature.split(" ")) {
            int comma = token.indexOf(',');
            if (comma < 0) {
                continue;
            }
            byte[] provided;
            try {
                provided = Base64.getDecoder().decode(token.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, provided)) {
                return;
            }
        }
        log.warn("웹훅 서명 불일치: webhookId={}", webhookId);
        throw reject();
    }

    private void verifyTimestamp(String webhookTimestamp) {
        final long ts;
        try {
            ts = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("웹훅 타임스탬프 형식 오류: {}", webhookTimestamp);
            throw reject();
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("웹훅 타임스탬프 허용 오차 초과: ts={}, now={}", ts, now);
            throw reject();
        }
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 계산 실패", e);
        }
    }

    private static BusinessException reject() {
        return new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
    }
}
