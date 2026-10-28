package com.practice.cashflow.application;

import java.util.UUID;

public record OutboxReady(UUID paymentId) {
}
