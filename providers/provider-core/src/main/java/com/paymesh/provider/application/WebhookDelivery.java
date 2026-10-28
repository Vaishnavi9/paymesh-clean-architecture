package com.paymesh.provider.application;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.paymesh.provider.ProviderSettings;
import com.paymesh.provider.persistence.ProviderStore;
import com.paymesh.provider.web.ProviderViews;
import org.bson.Document;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static com.mongodb.client.model.Filters.*;

public class WebhookDelivery {
    private final ProviderStore store;
    private final ProviderSettings settings;
    private final PaymentService payments;
    private final HttpClient http;
    private final Clock clock;
    private final URI destination;

    public WebhookDelivery(ProviderStore store, ProviderSettings settings, PaymentService payments,
                           HttpClient http, Clock clock) {
        this.store = store; this.settings = settings; this.payments = payments; this.http = http; this.clock = clock;
        destination = settings.webhookUrl() == null || settings.webhookUrl().isBlank() ? null : URI.create(settings.webhookUrl());
        if (destination != null && (!Set.of("http", "https").contains(destination.getScheme())
                || destination.getHost() == null || destination.getUserInfo() != null))
            throw new IllegalArgumentException("Webhook destination must be a configured HTTP(S) URL without user info");
    }

    public Document claim() {
        if (destination == null) return null;
        Date now = new Date(clock.millis());
        return store.collection("webhooks").findOneAndUpdate(
                and(eq("state", "PENDING"), lte("nextAttemptAt", now),
                        or(exists("leaseUntil", false), lte("leaseUntil", now))),
                new Document("$set", new Document("leaseToken", UUID.randomUUID().toString())
                        .append("leaseUntil", new Date(clock.millis() + settings.leaseMs()))),
                new FindOneAndUpdateOptions().sort(new Document("nextAttemptAt", 1)).returnDocument(ReturnDocument.AFTER));
    }

    public void deliver(Document job) {
        String body = payload(job).toJson();
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        int status = 0;
        try {
            var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Simulator-Event-Id", job.getString("_id"))
                    .header("X-Simulator-Timestamp", timestamp)
                    .header("X-Simulator-Signature", sign(settings.webhookSecret(), timestamp + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return; // Lease expires; another worker can retry.
        } catch (java.io.IOException temporary) {
            // Persist the failed attempt below. Never log credential-bearing URLs or response bodies.
        }
        int attempt = job.getInteger("attempts") + 1;
        boolean ok = status >= 200 && status < 300;
        boolean retryable = status == 0 || status == 408 || status == 429 || status >= 500;
        String state = ok ? "DELIVERED" : !retryable || attempt >= settings.maxAttempts() ? "DEAD" : "PENDING";
        Document update = new Document("attempts", attempt).append("state", state).append("lastHttpStatus", status)
                .append("nextAttemptAt", new Date(clock.millis() + payments.retryDelay(attempt)));
        store.collection("webhooks").updateOne(and(eq("_id", job.getString("_id")),
                        eq("leaseToken", job.getString("leaseToken")), gt("leaseUntil", new Date(clock.millis()))),
                new Document("$set", update).append("$unset", new Document("leaseToken", "").append("leaseUntil", "")));
    }

    public static Document payload(Document job) {
        Document event = job.get("payload", Document.class);
        Document payment = event.get("payment", Document.class);
        String type = event.getString("type");
        return switch (event.getString("provider")) {
            case "stripe" -> new Document("id", job.getString("_id")).append("object", "event")
                    .append("type", switch (type) {
                        case "AUTHORISED" -> "payment_intent.amount_capturable_updated";
                        case "CAPTURED" -> "payment_intent.succeeded";
                        default -> "payment_intent.payment_failed";
                    }).append("data", new Document("object", ProviderViews.stripe(payment)));
            case "adyen" -> new Document("live", "false").append("notificationItems",
                    List.of(new Document("NotificationRequestItem", new Document("pspReference",
                                    type.equals("CAPTURED") ? payment.getString("captureId") : payment.getString("_id"))
                            .append("originalReference", type.equals("CAPTURED") ? payment.getString("_id") : null)
                            .append("merchantReference", payment.getString("reference")).append("merchantAccountCode", "merchant-demo")
                            .append("eventCode", type.equals("REVIEW_REQUIRED") ? "SIMULATOR_REVIEW_REQUIRED"
                                    : type.equals("CAPTURED") ? "CAPTURE" : "AUTHORISATION")
                            .append("success", Boolean.toString(type.equals("AUTHORISED") || type.equals("CAPTURED")))
                            .append("eventDate", event.getString("occurredAt"))
                            .append("amount", new Document("value", payment.getLong("amount")).append("currency", payment.getString("currency"))))));
            default -> new Document("id", job.getString("_id"))
                    .append("event_type", type.equals("CAPTURED") ? "PAYMENT.CAPTURE.COMPLETED" : "SIMULATOR." + type)
                    .append("resource", ProviderViews.paypal(payment));
        };
    }

    public static String sign(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Cannot sign webhook", error);
        }
    }
}
