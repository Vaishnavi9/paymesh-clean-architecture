package com.practice.cashflow.application;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("This idempotency key was already used for different payment details.");
    }
}
