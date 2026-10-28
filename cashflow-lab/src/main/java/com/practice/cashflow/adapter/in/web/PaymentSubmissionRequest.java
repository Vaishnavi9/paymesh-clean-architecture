package com.practice.cashflow.adapter.in.web;

import com.practice.cashflow.application.AcceptPaymentCommand;
import com.practice.cashflow.domain.PaymentCurrency;
import com.practice.cashflow.domain.PaymentProvider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PaymentSubmissionRequest(

        @NotNull
        @Min(1)
        @Max(99_999_999)
        Long amountMinor,

        @NotNull
        @Pattern(regexp="EUR|USD")
        String currency,

        @NotNull
        @Pattern(regexp="STRIPE|ADYEN|PAYPAL")
        String provider,

        @NotNull
        @Pattern(regexp="pm_funded|pm_low|pm_blocked|pm_usd")
        String paymentMethodToken,

        @NotNull
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,80}")
        String merchantReference
) {
    /** Call after Bean Validation; supply merchantId from trusted application context. */
    public AcceptPaymentCommand toCommand(String merchantId, String idempotencyKey) {
        return new AcceptPaymentCommand(
                merchantId,
                idempotencyKey,
                amountMinor,
                PaymentCurrency.valueOf(currency),
                PaymentProvider.valueOf(provider),
                paymentMethodToken,
                merchantReference
        );
    }
}
