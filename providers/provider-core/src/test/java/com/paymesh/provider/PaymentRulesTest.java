package com.paymesh.provider;

import com.paymesh.provider.application.WebhookDelivery;
import com.paymesh.provider.domain.PaymentRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentRulesTest {
    @Test void moneyIsExactAndExtraPrecisionIsRejected() {
        assertEquals(2501L, PaymentRules.decimalToMinor("25.01"));
        assertThrows(IllegalArgumentException.class, () -> PaymentRules.decimalToMinor("25.001"));
        assertThrows(IllegalArgumentException.class, () -> PaymentRules.decimalToMinor("NaN"));
        assertThrows(IllegalArgumentException.class, () -> PaymentRules.validate(Long.MAX_VALUE, "EUR", "order"));
        assertThrows(IllegalArgumentException.class, () -> PaymentRules.validate(0, "EUR", "order"));
    }
    @Test void decisionsFollowAccountRules() {
        assertNull(PaymentRules.decline("EUR", false, 2500, "EUR", 2500));
        assertEquals("insufficient_funds", PaymentRules.decline("EUR", false, 2499, "EUR", 2500));
        assertEquals("account_blocked", PaymentRules.decline("EUR", true, 10000, "EUR", 2500));
        assertEquals("currency_mismatch", PaymentRules.decline("USD", false, 10000, "EUR", 2500));
    }
    @Test void signatureMatchesKnownHmacVector() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                WebhookDelivery.sign("key", "The quick brown fox jumps over the lazy dog"));
    }
}
