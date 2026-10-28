package com.practice.cashflow.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

public record PaymentSubmissionResponse(
        UUID paymentId,
        String status,
        String statusUrl,
        Instant acceptedAt
) {
}
