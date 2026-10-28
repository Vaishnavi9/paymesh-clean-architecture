package com.practice.cashflow.application;

import com.practice.cashflow.domain.PaymentCurrency;
import com.practice.cashflow.domain.PaymentProvider;

import java.time.Instant;
import java.util.UUID;

public record PaymentStatus(
        UUID paymentId,
        long amountMinor,
        PaymentCurrency currency,
        PaymentProvider provider,
        String merchantReference,
        String status,
        Instant createdAt
) {
}
