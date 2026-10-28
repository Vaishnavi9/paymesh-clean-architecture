package com.paymesh.provider.application;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.paymesh.provider.ProviderSettings;
import com.paymesh.provider.domain.ApiResult;
import com.paymesh.provider.domain.PaymentRules;
import com.paymesh.provider.domain.ProviderError;
import com.paymesh.provider.persistence.ProviderStore;
import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;

public class PaymentService {
    private final ProviderStore store;
    private final ProviderSettings settings;
    private final Clock clock;

    public PaymentService(ProviderStore store, ProviderSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    public Document get(String id) {
        Document payment = store.payment(id);
        if (payment == null) throw new ProviderError(404, "not_found", "Payment not found");
        return payment;
    }

    public ApiResult create(String key, long amount, String currency, String reference, String token,
                            boolean confirm, boolean manualCapture) {
        PaymentRules.validate(amount, currency, reference);
        if (token != null && !Set.of("pm_funded", "pm_low", "pm_blocked", "pm_usd").contains(token))
            throw new ProviderError(400, "invalid_payment_method", "Use a seeded simulator payment token");
        Document request = new Document("amount", amount).append("currency", currency)
                .append("reference", reference).append("token", token).append("confirm", confirm)
                .append("manualCapture", manualCapture);
        String id = prefix() + UUID.randomUUID().toString().replace("-", "");
        return mutate("create", key, request, session -> {
            Date now = now();
            Document scenario = store.collection("scenarios").find(session, eq("_id", reference)).first();
            Document payment = new Document("_id", id).append("merchant", "merchant-demo")
                    .append("provider", settings.kind()).append("amount", amount).append("currency", currency)
                    .append("reference", reference).append("token", token).append("manualCapture", manualCapture)
                    .append("state", token == null ? "REQUIRES_PAYMENT_METHOD" : "CREATED")
                    .append("version", 0L).append("attempts", 0).append("createdAt", now).append("updatedAt", now)
                    .append("transientFailures", scenario == null ? 0 : scenario.getInteger("transientFailures", 0))
                    .append("loseResponse", scenario != null && scenario.getBoolean("loseResponse", false))
                    .append("delayMs", scenario == null ? settings.processingDelayMs()
                            : ((Number) scenario.getOrDefault("delayMs", settings.processingDelayMs())).longValue());
            if (settings.kind().equals("adyen")) {
                if (token == null) throw new ProviderError(400, "payment_method_required", "Payment method is required");
                payment.put("state", "PROCESSING");
                payment.put("nextAttemptAt", new Date(now.getTime() + payment.getLong("delayMs")));
            }
            store.collection("payments").insertOne(session, payment);
            if (settings.kind().equals("stripe") && confirm) {
                if (token == null) throw new ProviderError(400, "payment_method_required", "Payment method is required");
                authorise(session, payment);
                if (!manualCapture && "AUTHORISED".equals(payment.getString("state"))) captureFunds(session, payment);
            }
            return payment;
        });
    }

    public ApiResult confirm(String id, String key, String token) {
        if (token != null && !Set.of("pm_funded", "pm_low", "pm_blocked", "pm_usd").contains(token))
            throw new ProviderError(400, "invalid_payment_method", "Use a seeded simulator payment token");
        return mutate("confirm:" + id, key, new Document("token", token), session -> {
            Document p = require(session, id);
            if (!Set.of("CREATED", "REQUIRES_PAYMENT_METHOD", "DECLINED").contains(p.getString("state")))
                throw new ProviderError(409, "invalid_state", "Payment cannot be confirmed in its current state");
            if (token != null && !token.isBlank()) p.put("token", token);
            if (p.getString("token") == null)
                throw new ProviderError(400, "payment_method_required", "Payment method is required");
            authorise(session, p);
            if (!p.getBoolean("manualCapture") && "AUTHORISED".equals(p.getString("state"))) captureFunds(session, p);
            return p;
        });
    }

    public ApiResult approve(String id, String key) {
        return mutate("approve:" + id, key, new Document(), session -> {
            Document p = require(session, id);
            if (!"CREATED".equals(p.getString("state")))
                throw new ProviderError(409, "invalid_state", "Only a created order can be approved");
            change(session, p, "APPROVED"); // Buyer approval reserves no funds.
            return p;
        });
    }

    public ApiResult capture(String id, String key) {
        return mutate("capture:" + id, key, new Document(), session -> {
            Document p = require(session, id);
            if ("CAPTURED".equals(p.getString("state"))) return p;
            if (settings.kind().equals("paypal") && "APPROVED".equals(p.getString("state"))) authorise(session, p);
            if ("DECLINED".equals(p.getString("state"))) return p;
            if (!"AUTHORISED".equals(p.getString("state")))
                throw new ProviderError(409, "invalid_state", "Funds must be authorised before capture");
            captureFunds(session, p);
            return p;
        });
    }

    public ApiResult requestAdyenCapture(String id, String key, long amount, String currency, String reference) {
        PaymentRules.validate(amount, currency, reference);
        String captureId = "psp_capture_" + UUID.randomUUID().toString().replace("-", "");
        return mutate("capture:" + id, key,
                new Document("amount", amount).append("currency", currency).append("reference", reference), session -> {
                    Document p = require(session, id);
                    if (p.getLong("amount") != amount || !currency.equals(p.getString("currency")))
                        throw new ProviderError(400, "full_capture_required", "Capture the exact authorised amount and currency");
                    if (!"AUTHORISED".equals(p.getString("state")))
                        throw new ProviderError(409, "invalid_state", "Only an authorised payment can be submitted for capture");
                    p.put("captureReference", reference);
                    p.put("captureId", captureId);
                    p.put("attempts", 0);
                    p.remove("leaseUntil");
                    p.remove("leaseToken");
                    p.put("nextAttemptAt", new Date(clock.millis() + p.getLong("delayMs")));
                    change(session, p, "CAPTURE_PROCESSING");
                    return p;
                });
    }

    private ApiResult mutate(String operation, String key, Document request,
                             Function<ClientSession, Document> work) {
        int keyLimit = switch (settings.kind()) { case "stripe" -> 255; case "paypal" -> 38; default -> 64; };
        if (key == null || key.length() > keyLimit || !key.matches("[A-Za-z0-9._:-]+"))
            throw new ProviderError(400, "idempotency_key_required", "Use a valid idempotency key, maximum " + keyLimit + " characters");
        String recordId = "merchant-demo|" + operation + "|" + key;
        String fingerprint = fingerprint(request.toJson());
        try {
            return store.transaction(session -> {
                Document previous = store.collection("idempotency").find(session, eq("_id", recordId)).first();
                if (previous != null) {
                    if (!fingerprint.equals(previous.getString("fingerprint")))
                        throw new ProviderError(409, "idempotency_conflict", "The same key was used with different input");
                    Document snapshot = previous.get("snapshot", Document.class);
                    if (settings.kind().equals("paypal")) snapshot = require(session, snapshot.getString("_id"));
                    return new ApiResult(previous.getInteger("status"), snapshot, true, false);
                }
                Document payment = work.apply(session);
                int status = "DECLINED".equals(payment.getString("state"))
                        ? (settings.kind().equals("stripe") ? 402 : 422)
                        : (settings.kind().equals("paypal")
                        && (operation.equals("create") || operation.startsWith("capture:")) ? 201 : 200);
                if (settings.kind().equals("adyen") && operation.startsWith("capture:")) status = 201;
                store.collection("idempotency").insertOne(session, new Document("_id", recordId)
                        .append("fingerprint", fingerprint).append("snapshot", new Document(payment))
                        .append("status", status).append("createdAt", now()));
                boolean lost = payment.getBoolean("loseResponse", false)
                        && Set.of("AUTHORISED", "CAPTURED").contains(payment.getString("state"));
                return new ApiResult(status, payment, false, lost);
            });
        } catch (MongoException failure) {
            if (failure.getCode() == 11000)
                throw new ProviderError(409, "request_in_progress", "A concurrent operation won. Retry the SAME key.");
            throw failure;
        }
    }

    private void authorise(ClientSession session, Document p) {
        Document account = store.collection("accounts").find(session, eq("_id", p.getString("token"))).first();
        if (account == null) throw new ProviderError(400, "invalid_payment_method", "Unknown simulator funding token");
        String reason = PaymentRules.decline(account.getString("currency"), account.getBoolean("blocked"),
                account.getLong("available"), p.getString("currency"), p.getLong("amount"));
        if (reason != null) {
            p.put("declineReason", reason);
            change(session, p, "DECLINED");
            event(session, p, "DECLINED");
            return;
        }
        long amount = p.getLong("amount");
        var result = store.collection("accounts").updateOne(session,
                and(eq("_id", p.getString("token")), gte("available", amount), eq("blocked", false)),
                new Document("$inc", new Document("available", -amount).append("reserved", amount)));
        if (result.getModifiedCount() != 1) throw new ProviderError(409, "account_changed", "Retry the SAME operation");
        p.remove("declineReason");
        ledger(session, p, "RESERVE");
        change(session, p, "AUTHORISED");
        event(session, p, "AUTHORISED");
    }

    private void captureFunds(ClientSession session, Document p) {
        long amount = p.getLong("amount");
        var result = store.collection("accounts").updateOne(session,
                and(eq("_id", p.getString("token")), gte("reserved", amount)),
                new Document("$inc", new Document("reserved", -amount)));
        if (result.getModifiedCount() != 1)
            throw new ProviderError(409, "reservation_missing", "No matching reserved balance");
        store.collection("accounts").updateOne(session, eq("_id", "merchant_" + p.getString("currency")),
                new Document("$inc", new Document("available", amount)));
        ledger(session, p, "CAPTURE");
        change(session, p, "CAPTURED");
        event(session, p, "CAPTURED");
    }

    private void ledger(ClientSession session, Document p, String type) {
        store.collection("ledger").insertOne(session, new Document("_id", UUID.randomUUID().toString())
                .append("operation", p.getString("_id") + ":" + type).append("paymentId", p.getString("_id"))
                .append("type", type).append("amount", p.getLong("amount")).append("currency", p.getString("currency"))
                .append("debitAccount", p.getString("token"))
                .append("creditAccount", type.equals("CAPTURE") ? "merchant_" + p.getString("currency") : "reserved")
                .append("createdAt", now()));
    }

    private void change(ClientSession session, Document p, String state) {
        p.put("state", state);
        p.put("version", p.getLong("version") + 1);
        p.put("updatedAt", now());
        store.collection("payments").replaceOne(session, eq("_id", p.getString("_id")), p);
    }

    private void event(ClientSession session, Document p, String type) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        Document payload = new Document("id", eventId).append("provider", settings.kind())
                .append("paymentId", p.getString("_id")).append("reference", p.getString("reference"))
                .append("type", type).append("version", p.getLong("version"))
                .append("amountMinor", p.getLong("amount")).append("currency", p.getString("currency"))
                .append("occurredAt", clock.instant().toString()).append("payment", new Document(p));
        store.collection("webhooks").insertOne(session, new Document("_id", eventId)
                .append("paymentId", p.getString("_id")).append("payload", payload)
                .append("state", "PENDING").append("attempts", 0).append("nextAttemptAt", now()).append("createdAt", now()));
    }

    public Document claim() {
        Date now = now();
        String claimToken = UUID.randomUUID().toString();
        return store.collection("payments").findOneAndUpdate(
                and(in("state", "PROCESSING", "CAPTURE_PROCESSING"), lte("nextAttemptAt", now),
                        or(exists("leaseUntil", false), lte("leaseUntil", now))),
                new Document("$set", new Document("leaseToken", claimToken)
                        .append("leaseUntil", new Date(now.getTime() + settings.leaseMs()))),
                new FindOneAndUpdateOptions().sort(new Document("nextAttemptAt", 1))
                        .returnDocument(ReturnDocument.AFTER));
    }

    public void processClaim(Document claim) {
        store.transaction(session -> {
            Document p = store.collection("payments").find(session, and(eq("_id", claim.getString("_id")),
                    in("state", "PROCESSING", "CAPTURE_PROCESSING"), eq("leaseToken", claim.getString("leaseToken")),
                    gt("leaseUntil", now()))).first();
            if (p == null) return null; // Another worker owns the lease, or it expired.
            int attempt = p.getInteger("attempts") + 1;
            p.put("attempts", attempt);
            if (attempt <= p.getInteger("transientFailures")) {
                if (attempt >= settings.maxAttempts()) {
                    change(session, p, "REVIEW_REQUIRED");
                    event(session, p, "REVIEW_REQUIRED");
                } else {
                    p.put("nextAttemptAt", new Date(clock.millis() + retryDelay(attempt)));
                    p.remove("leaseUntil");
                    p.remove("leaseToken");
                    change(session, p, p.getString("state"));
                }
            } else {
                if ("CAPTURE_PROCESSING".equals(p.getString("state"))) captureFunds(session, p);
                else authorise(session, p);
            }
            return null;
        });
    }

    public long retryDelay(int attempt) {
        long capped = Math.min(30_000L, settings.retryBaseMs() * (1L << Math.min(attempt - 1, 10)));
        return capped + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1L, capped / 4));
    }

    private Document require(ClientSession session, String id) {
        Document p = store.collection("payments").find(session, eq("_id", id)).first();
        if (p == null) throw new ProviderError(404, "not_found", "Payment not found");
        return p;
    }
    private Date now() { return Date.from(clock.instant()); }
    private String prefix() { return switch (settings.kind()) { case "stripe" -> "pi_"; case "adyen" -> "psp_"; default -> "order_"; }; }
    private static String fingerprint(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
