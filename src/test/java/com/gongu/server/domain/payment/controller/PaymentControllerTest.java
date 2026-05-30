package com.gongu.server.domain.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.service.PaymentService;
import com.gongu.server.global.exception.BusinessException;
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
    static class SecurityConfig {}

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());

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
