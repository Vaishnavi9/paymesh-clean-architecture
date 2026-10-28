package com.practice.cashflow.application;

import com.practice.cashflow.application.port.in.AcceptPaymentUseCase;
import com.practice.cashflow.application.port.out.PaymentAcceptanceStore;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class AcceptPaymentService implements AcceptPaymentUseCase {

    private static final Pattern MERCHANT_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final Pattern MERCHANT_REFERENCE =
            Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    private static final Set<String> PAYMENT_METHOD_TOKENS =
            Set.of("pm_funded", "pm_low", "pm_blocked", "pm_usd");

    private final PaymentAcceptanceStore paymentAcceptanceStore;

    public AcceptPaymentService (PaymentAcceptanceStore paymentAcceptanceStore) {
        this.paymentAcceptanceStore = Objects.requireNonNull(paymentAcceptanceStore);
    }
    @Override
    public PaymentAcceptance accept(AcceptPaymentCommand command) {
        validate(command);

        return paymentAcceptanceStore.acceptAtomically(command);
    }

    private static void validate(AcceptPaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Payment command is required."
            );
        }

        requireMatch(command.merchantId(), MERCHANT_ID, "merchantId");
        requireMatch(command.idempotencyKey(), IDEMPOTENCY_KEY, "idempotencyKey");

        if (command.amountMinor() < 1
                || command.amountMinor() > 99_999_999L) {
            throw new IllegalArgumentException(
                    "amountMinor must be between 1 and 99999999."
            );
        }

        if (command.currency() == null) {
            throw new IllegalArgumentException("currency is required.");
        }

        if (command.provider() == null) {
            throw new IllegalArgumentException("provider is required.");
        }

        requireAllowed(
                command.paymentMethodToken(),
                PAYMENT_METHOD_TOKENS,
                "paymentMethodToken"
        );

        requireMatch(
                command.merchantReference(),
                MERCHANT_REFERENCE,
                "merchantReference"
        );
    }

    private static void requireMatch(
            String value,
            Pattern pattern,
            String field
    ) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " has an invalid format."
            );
        }
    }

    private static void requireAllowed(
            String value,
            Set<String> allowed,
            String field
    ) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(
                    field + " has an unsupported value."
            );
        }
    }
}
