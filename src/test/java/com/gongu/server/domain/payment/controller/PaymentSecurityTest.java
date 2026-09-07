package com.gongu.server.domain.payment.controller;

import com.gongu.server.domain.payment.service.PaymentService;
import com.gongu.server.global.config.SecurityConfig;
import com.gongu.server.global.security.handler.JwtAccessDeniedHandler;
import com.gongu.server.global.security.handler.JwtAuthenticationEntryPoint;
import com.gongu.server.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        PaymentSecurityTest.VerifierConfig.class
})
class PaymentSecurityTest {

    @TestConfiguration
    static class VerifierConfig {
        @Bean
        com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier portOneWebhookVerifier() {
            return new com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier(
                    new com.gongu.server.global.infrastructure.portone.PortOneProperties(
                            null, null, WebhookSignatures.TEST_SECRET));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("POST /payments/webhook — 인증 없이 유효 서명이면 200 (permitAll)")
    void receiveWebhook_validSignature_noAuth_returns200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willReturn(new com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse(
                        1L, "pay-uuid-001", 10_000L,
                        com.gongu.server.domain.payment.domain.PaymentStatus.PAID,
                        LocalDateTime.now(),
                        com.gongu.server.domain.order.entity.OrderStatus.PAID));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook — 서명 없으면 400 (permitAll이어도 검증에서 거부)")
    void receiveWebhook_noSignature_returns400() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/prepare — 인증 없이 401 (authenticated)")
    void preparePayment_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/payments/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\": 1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /payments/verify — 인증 없이 401 (authenticated)")
    void verifyPayment_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\": 1, \"payment_id\": \"pay-uuid-001\"}"))
                .andExpect(status().isUnauthorized());
    }
}
