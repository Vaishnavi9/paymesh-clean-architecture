package com.paymesh.paypal;

import com.mongodb.client.*;
import com.paymesh.provider.ProviderSettings;
import com.paymesh.provider.application.*;
import com.paymesh.provider.persistence.ProviderStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@ApplicationScoped
public class ProviderBeans {
    @Produces @Singleton
    ProviderSettings settings(Config config) {
        return new ProviderSettings("paypal", value(config, "database"), value(config, "mongo-uri"),
                value(config, "api-key"), value(config, "admin-key"),
                config.getOptionalValue("provider.webhook-url", String.class).orElse(""),
                value(config, "webhook-secret"), config.getValue("provider.processing-delay-ms", Long.class),
                config.getValue("provider.retry-base-ms", Long.class), config.getValue("provider.max-attempts", Integer.class),
                config.getValue("provider.lease-ms", Long.class), config.getValue("provider.workers-enabled", Boolean.class));
    }
    private String value(Config config, String name) { return config.getValue("provider." + name, String.class); }
    @Produces @Singleton
    MongoClient mongoClient(ProviderSettings settings) { return MongoClients.create(settings.mongoUri()); }
    void closeMongo(@Disposes MongoClient client) { client.close(); }
    @Produces @Singleton
    ProviderStore store(MongoClient client, ProviderSettings settings) {
        var store = new ProviderStore(client, client.getDatabase(settings.database()));
        store.initialise();
        return store;
    }
    void initialise(@Observes StartupEvent event, ProviderStore store) { store.ready(); }
    @Produces @Singleton
    Clock clock() { return Clock.systemUTC(); }
    @Produces @Singleton
    PaymentService payments(ProviderStore store, ProviderSettings settings, Clock clock) {
        return new PaymentService(store, settings, clock);
    }
    @Produces @Singleton
    HttpClient webhookClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    void closeHttp(@Disposes HttpClient client) { client.close(); }
    @Produces @Singleton
    WebhookDelivery delivery(ProviderStore store, ProviderSettings settings, PaymentService payments,
                             HttpClient http, Clock clock) {
        return new WebhookDelivery(store, settings, payments, http, clock);
    }
}
