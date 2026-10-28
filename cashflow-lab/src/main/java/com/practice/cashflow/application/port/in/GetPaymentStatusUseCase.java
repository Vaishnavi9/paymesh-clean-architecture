package com.practice.cashflow.application.port.in;

import com.practice.cashflow.application.PaymentStatus;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface GetPaymentStatusUseCase {

    Optional<PaymentStatus> find(UUID merchantId, UUID paymentId);
}
