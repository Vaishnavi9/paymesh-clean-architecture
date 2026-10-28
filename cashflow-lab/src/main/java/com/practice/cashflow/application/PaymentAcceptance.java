package com.practice.cashflow.application;

import java.time.Instant;
import java.util.UUID;

public record PaymentAcceptance(
        UUID paymentId,
        Instant acceptedAt,
        boolean replayed
) {
}
