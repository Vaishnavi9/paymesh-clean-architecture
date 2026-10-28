package com.paymesh.provider.domain;

import java.math.BigDecimal;
import java.util.Set;

public final class PaymentRules {
    private PaymentRules() {}
    public static final long MAX_MINOR = 99_999_999L;
    public static final Set<String> CURRENCIES = Set.of("EUR", "USD");

    public static void validate(long amount, String currency, String reference) {
        if (amount < 1 || amount > MAX_MINOR)
            throw new IllegalArgumentException("Amount must be between 1 and 99999999 minor units");
        if (!CURRENCIES.contains(currency))
            throw new IllegalArgumentException("This simulator supports EUR and USD");
        if (reference == null || reference.isBlank() || reference.length() > 100)
            throw new IllegalArgumentException("Reference must contain 1 to 100 characters");
    }

    public static long decimalToMinor(String value) {
        if (value == null || !value.matches("[0-9]{1,6}\\.[0-9]{2}"))
            throw new IllegalArgumentException("Use an exact decimal amount, for example 25.00");
        return new BigDecimal(value).movePointRight(2).longValueExact();
    }

    public static String decline(String accountCurrency, boolean blocked, long available,
                                 String currency, long amount) {
        if (!currency.equals(accountCurrency)) return "currency_mismatch";
        if (blocked) return "account_blocked";
        if (available < amount) return "insufficient_funds";
        return null;
    }
}
