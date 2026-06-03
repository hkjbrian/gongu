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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class PaymentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("POST /payments/webhook — 인증 없이 200 (permitAll)")
    void receiveWebhook_withoutAuthentication_returns200() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
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
