package com.practice.cashflow.application.port.out;

import com.practice.cashflow.application.AcceptPaymentCommand;
import com.practice.cashflow.application.PaymentAcceptance;
import com.practice.cashflow.application.PaymentStatus;

import java.util.Optional;
import java.util.UUID;

public interface PaymentAcceptanceStore {
    PaymentAcceptance acceptAtomically(AcceptPaymentCommand command);

    Optional<PaymentStatus> find(UUID merchantId, UUID paymentId);
}
