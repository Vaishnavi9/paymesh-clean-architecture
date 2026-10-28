package com.paymesh.provider;


public record ProviderSettings(
        String kind, String database, String mongoUri, String apiKey, String adminKey,
        String webhookUrl, String webhookSecret, long processingDelayMs,
        long retryBaseMs, int maxAttempts, long leaseMs, boolean workersEnabled) {
    public ProviderSettings {
        if (!java.util.Set.of("stripe", "adyen", "paypal").contains(kind))
            throw new IllegalArgumentException("Select stripe, adyen or paypal");
        if (apiKey == null || apiKey.isBlank() || adminKey == null || adminKey.isBlank())
            throw new IllegalArgumentException("Provider and admin keys are required");
        if (processingDelayMs < 0 || retryBaseMs < 1 || maxAttempts < 1 || leaseMs < 1000)
            throw new IllegalArgumentException("Invalid worker settings");
    }
}
