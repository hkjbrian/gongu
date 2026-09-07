package com.gongu.server.domain.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.service.PaymentService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @org.springframework.context.annotation.Bean
        com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier portOneWebhookVerifier() {
            return new com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier(
                    new com.gongu.server.global.infrastructure.portone.PortOneProperties(
                            null, null, WebhookSignatures.TEST_SECRET));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor asUser(Long userId) {
        return (MockHttpServletRequest request) -> {
            UserPrincipal principal = new UserPrincipal(userId, Role.USER);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("POST /payments/prepare 성공 → 200 + paymentId, amount")
    void preparePayment_성공_200() throws Exception {
        // given
        given(paymentService.preparePayment(anyLong(), anyLong()))
                .willReturn(new PaymentPrepareResult("pay-uuid-001", 10_000L));

        String requestBody = "{\"order_id\": 1}";

        // when & then
        mockMvc.perform(post("/payments/prepare")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value("pay-uuid-001"))
                .andExpect(jsonPath("$.data.amount").value(10000));
    }

    @Test
    @DisplayName("POST /payments/prepare 인증 없음 → 401")
    void preparePayment_인증없음_401() throws Exception {
        mockMvc.perform(post("/payments/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\": 1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /payments/prepare orderId 누락 → 400")
    void preparePayment_orderId_누락_400() throws Exception {
        mockMvc.perform(post("/payments/prepare")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/verify 성공 → 200 + VerifyPaymentResponse")
    void verifyPayment_성공_200() throws Exception {
        // given
        VerifyPaymentResponse response = new VerifyPaymentResponse(
                1L, "pay-uuid-001", 10_000L,
                PaymentStatus.PAID, LocalDateTime.now(), OrderStatus.PAID
        );
        given(paymentService.completePayment(anyString())).willReturn(response);

        String requestBody = "{\"order_id\": 1, \"payment_id\": \"pay-uuid-001\"}";

        // when & then
        mockMvc.perform(post("/payments/verify")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value("pay-uuid-001"))
                .andExpect(jsonPath("$.data.amount").value(10000))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    @DisplayName("POST /payments/verify 인증 없음 → 401")
    void verifyPayment_인증없음_401() throws Exception {
        String requestBody = "{\"order_id\": 1, \"payment_id\": \"pay-uuid-001\"}";

        mockMvc.perform(post("/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /payments/verify paymentId 누락 → 400")
    void verifyPayment_paymentId_누락_400() throws Exception {
        String requestBody = "{\"order_id\": 1}";

        mockMvc.perform(post("/payments/verify")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/webhook 성공 → 200 OK (인증 불필요)")
    void receiveWebhook_성공_200() throws Exception {
        // given — completePayment는 VerifyPaymentResponse 반환하지만 webhook은 무시
        given(paymentService.completePayment(anyString()))
                .willReturn(new VerifyPaymentResponse(1L, "pay-uuid-001", 10_000L,
                        PaymentStatus.PAID, LocalDateTime.now(), OrderStatus.PAID));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        // when & then — 인증 없이 호출 가능
        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());

        verify(paymentService).completePayment("pay-uuid-001");
    }

    @Test
    @DisplayName("POST /payments/webhook Transaction.Paid 이외 타입 → 200 OK (completePayment 미호출)")
    void receiveWebhook_비결제타입_200_completePayment_미호출() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Failed\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook PG 조회 실패(PAYMENT_PG_UNAVAILABLE) → 503 (재시도 유효)")
    void receiveWebhook_PG조회실패_503() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("POST /payments/webhook PG 장애 InfraException → 503 (재시도 유효, BusinessException catch 우회 확인)")
    void receiveWebhook_PG장애_InfraException_503() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("POST /payments/webhook 주문 만료 자동환불 완료(ORDER_EXPIRED_REFUNDED) → 200 (재시도 중단)")
    void receiveWebhook_주문만료환불완료_200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook PG 미결제 확정(PAYMENT_NOT_COMPLETED) → 200 (재시도 중단)")
    void receiveWebhook_PG미결제확정_200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook 이미 터미널 상태(PAYMENT_INVALID_STATE_TRANSITION) → 200 (재시도 중단)")
    void receiveWebhook_이미터미널_200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook 금액 불일치(PAYMENT_AMOUNT_MISMATCH) → 200 (보상 완료, 재시도 중단)")
    void receiveWebhook_금액불일치_200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook 서명 헤더 없음 → 400 (재시도 미유발)")
    void receiveWebhook_서명없음_400() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"));

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook 서명 불일치(바디 변조) → 400")
    void receiveWebhook_서명불일치_400() throws Exception {
        String signedBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";
        String tamperedBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-999\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(signedBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook 바디 없음 → 400 PAYMENT_010 (500/재시도 미유발)")
    void receiveWebhook_바디없음_400() throws Exception {
        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"));

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook 유효 서명 + 비 JSON 바디 → 400 PAYMENT_010")
    void receiveWebhook_유효서명_비JSON바디_400() throws Exception {
        String body = "not json";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"));

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook 유효 서명 + paymentId 공백 → 400 PAYMENT_010")
    void receiveWebhook_유효서명_paymentId공백_400() throws Exception {
        String body = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"));

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/verify 결제 없음 → 404")
    void verifyPayment_결제없음_404() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        String requestBody = "{\"order_id\": 1, \"payment_id\": \"nonexistent\"}";

        mockMvc.perform(post("/payments/verify")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_001"));
    }

    @Test
    @DisplayName("POST /payments/verify 소유권 불일치 → 409")
    void verifyPayment_소유권불일치_409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED))
                .when(paymentService).validateOwnership(anyLong(), anyString());

        String requestBody = "{\"order_id\": 1, \"payment_id\": \"pay-uuid-001\"}";

        mockMvc.perform(post("/payments/verify")
                        .with(asUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_004"));
    }
}
