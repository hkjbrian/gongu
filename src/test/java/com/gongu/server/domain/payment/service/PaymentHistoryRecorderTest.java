package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.global.infrastructure.portone.PgResponseMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentHistoryRecorderTest {

    @Mock private PaymentHistoryRepository paymentHistoryRepository;
    @Mock private PgResponseMasker pgResponseMasker;
    @InjectMocks private PaymentHistoryRecorder paymentHistoryRecorder;

    @Test
    void record_원문이_있으면_마스킹후_저장한다() {
        Payment payment = mock(Payment.class);
        given(pgResponseMasker.mask("raw")).willReturn("masked");

        paymentHistoryRecorder.record(payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, "raw");

        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPgRawResponse()).isEqualTo("masked");
        assertThat(captor.getValue().getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(captor.getValue().getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(captor.getValue().getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
    }

    @Test
    void record_원문이_null이면_마스킹을_호출하지_않는다() {
        Payment payment = mock(Payment.class);

        paymentHistoryRecorder.record(payment, PaymentStatus.PENDING, PaymentStatus.CANCELLED,
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "TTL 경과", null);

        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPgRawResponse()).isNull();
        verify(pgResponseMasker, never()).mask(any());
    }
}
