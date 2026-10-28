package com.paymesh.provider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class ProviderConfiguration {
    @Bean
    ProviderSettings providerSettings(org.springframework.core.env.Environment env) {
        String kind = env.getRequiredProperty("provider.kind");
        if (!java.util.Set.of("stripe", "adyen").contains(kind))
            throw new IllegalArgumentException("Use the Quarkus application for PayPal");
        return new ProviderSettings(kind, env.getRequiredProperty("provider.database"),
                env.getRequiredProperty("provider.mongo-uri"), env.getRequiredProperty("provider.api-key"),
                env.getRequiredProperty("provider.admin-key"), env.getProperty("provider.webhook-url", ""),
                env.getRequiredProperty("provider.webhook-secret"),
                env.getRequiredProperty("provider.processing-delay-ms", Long.class),
                env.getRequiredProperty("provider.retry-base-ms", Long.class),
                env.getRequiredProperty("provider.max-attempts", Integer.class),
                env.getRequiredProperty("provider.lease-ms", Long.class),
                env.getRequiredProperty("provider.workers-enabled", Boolean.class));
    }
    @Bean
    com.paymesh.provider.persistence.ProviderStore providerStore(MongoClient client, MongoDatabase db) {
        var store = new com.paymesh.provider.persistence.ProviderStore(client, db);
        store.initialise();
        return store;
    }
    @Bean
    com.paymesh.provider.application.PaymentService paymentService(
            com.paymesh.provider.persistence.ProviderStore store, ProviderSettings settings, Clock clock) {
        return new com.paymesh.provider.application.PaymentService(store, settings, clock);
    }
    @Bean
    com.paymesh.provider.application.WebhookDelivery webhookDelivery(
            com.paymesh.provider.persistence.ProviderStore store, ProviderSettings settings,
            com.paymesh.provider.application.PaymentService payments, HttpClient http, Clock clock) {
        return new com.paymesh.provider.application.WebhookDelivery(store, settings, payments, http, clock);
    }

    @Bean(destroyMethod = "close")
    MongoClient mongoClient(ProviderSettings settings) {
        return MongoClients.create(settings.mongoUri());
    }

    @Bean
    MongoDatabase mongoDatabase(MongoClient client, ProviderSettings settings) {
        return client.getDatabase(settings.database());
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close")
    HttpClient webhookClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
