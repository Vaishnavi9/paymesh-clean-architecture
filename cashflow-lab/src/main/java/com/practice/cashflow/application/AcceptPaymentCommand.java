package com.practice.cashflow.application;

import com.practice.cashflow.domain.PaymentCurrency;
import com.practice.cashflow.domain.PaymentProvider;

public record AcceptPaymentCommand(
        String merchantId,
        String idempotencyKey,
        long amountMinor,
        PaymentCurrency currency,
        PaymentProvider provider,
        String paymentMethodToken,
        String merchantReference
) {
}
