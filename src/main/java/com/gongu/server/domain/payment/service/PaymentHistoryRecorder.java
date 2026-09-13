package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.global.infrastructure.portone.PgResponseMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentHistoryRecorder {

    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PgResponseMasker pgResponseMasker;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                        PaymentHistoryTrigger trigger, String reason, String rawPgResponse) {
        String maskedResponse = rawPgResponse == null ? null : pgResponseMasker.mask(rawPgResponse);
        paymentHistoryRepository.save(
                PaymentHistory.record(payment, fromStatus, toStatus, trigger, reason, maskedResponse));
    }
}
