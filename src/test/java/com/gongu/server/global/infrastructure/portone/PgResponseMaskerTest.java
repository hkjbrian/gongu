package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgResponseMaskerTest {

    private final PgResponseMasker masker = new PgResponseMasker(new ObjectMapper());

    @Test
    void mask_화이트리스트_필드는_유지하고_나머지는_마스킹() {
        String raw = "{\"id\":\"pay-1\",\"status\":\"PAID\",\"amount\":{\"total\":10000,\"currency\":\"KRW\"},"
                + "\"customer\":{\"name\":\"홍길동\",\"birthYear\":\"1990\"}}";

        String masked = masker.mask(raw);

        assertThat(masked).contains("\"id\":\"pay-1\"");
        assertThat(masked).contains("\"status\":\"PAID\"");
        assertThat(masked).contains("\"total\":10000");
        assertThat(masked).doesNotContain("홍길동");
        assertThat(masked).doesNotContain("1990");
    }

    @Test
    void mask_null이나_빈문자열은_그대로_반환() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void mask_파싱불가능한_문자열은_마스킹값으로_대체() {
        assertThat(masker.mask("not-a-json")).isEqualTo("***");
    }
}
