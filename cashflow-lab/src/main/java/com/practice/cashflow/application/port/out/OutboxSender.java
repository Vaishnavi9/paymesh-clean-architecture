package com.practice.cashflow.application.port.out;

@FunctionalInterface
public interface OutboxSender {
    void send(OutboxStore.Claim message);
}
