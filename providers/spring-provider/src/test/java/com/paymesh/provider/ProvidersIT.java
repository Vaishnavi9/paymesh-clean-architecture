package com.paymesh.provider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.application.WebhookDelivery;
import com.paymesh.provider.persistence.ProviderStore;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvidersIT {
    private final Map<String, ConfigurableApplicationContext> apps = new HashMap<>();
    private final Map<String, String> urls = new HashMap<>();
    private final Map<String, String> databases = new HashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer receiver;
    private final AtomicInteger deliveries = new AtomicInteger();
    private final List<String> deliveredIds = new CopyOnWriteArrayList<>();
    private final List<Boolean> signaturesValid = new CopyOnWriteArrayList<>();
    private static final String ADMIN = "test-admin";
    private static final String URI = System.getProperty("provider.test.mongo",
            "mongodb://127.0.0.1:27028/?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=5000");

    @BeforeAll void start() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String timestamp = exchange.getRequestHeaders().getFirst("X-Simulator-Timestamp");
            signaturesValid.add(WebhookDelivery.sign("test-webhook", timestamp + "." + body)
                    .equals(exchange.getRequestHeaders().getFirst("X-Simulator-Signature")));
            deliveredIds.add(exchange.getRequestHeaders().getFirst("X-Simulator-Event-Id"));
            int status = deliveries.incrementAndGet() == 1 ? 503 : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        receiver.start();
        for (String kind : List.of("stripe", "adyen")) startApp(kind);
    }
    private void startApp(String kind) {
        String database = databases.computeIfAbsent(kind, ignored -> "provider_it_" + kind + "_" + UUID.randomUUID().toString().replace("-", ""));
        var app = new SpringApplicationBuilder(ProviderApplication.class).run(
                "--server.port=0", "--provider.kind=" + kind, "--provider.database=" + database,
                "--provider.mongo-uri=" + URI, "--provider.api-key=test-" + kind, "--provider.admin-key=" + ADMIN,
                "--provider.workers-enabled=false", "--provider.processing-delay-ms=0", "--provider.retry-base-ms=1",
                "--provider.max-attempts=3", "--logging.level.root=WARN",
                "--provider.webhook-secret=test-webhook",
                "--provider.webhook-url=http://127.0.0.1:" + receiver.getAddress().getPort() + "/events");
        apps.put(kind, app);
        urls.put(kind, "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort());
    }
    @AfterAll void stop() {
        apps.values().forEach(ConfigurableApplicationContext::close);
        try (MongoClient client = MongoClients.create(URI)) {
            databases.values().forEach(name -> {
                if (!name.startsWith("provider_it_")) throw new IllegalStateException("Refuse to drop non-test database");
                client.getDatabase(name).drop();
            });
        }
        if (receiver != null) receiver.stop(0);
        http.close();
    }
    private MongoDatabase db(String kind) { return apps.get(kind).getBean(MongoClient.class).getDatabase(databases.get(kind)); }
    private record Reply(int code, Document body) {}
    private Reply call(String kind, String method, String path, String key, String body, boolean form) throws Exception {
        var request = HttpRequest.newBuilder(java.net.URI.create(urls.get(kind) + path)).timeout(Duration.ofSeconds(15));
        request.header(path.startsWith("/__sim/") ? "X-Simulator-Admin-Key"
                        : kind.equals("adyen") ? "X-API-Key" : "Authorization",
                path.startsWith("/__sim/") ? ADMIN : kind.equals("adyen") ? "test-adyen" : "Bearer test-" + kind);
        if (key != null) request.header(kind.equals("paypal") ? "PayPal-Request-Id" : "Idempotency-Key", key);
        request.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), Document.parse(response.body()));
    }
    private String stripe(String reference, String token, boolean confirm, boolean manual) {
        return "amount=2500&currency=eur&metadata%5Border_reference%5D=" + reference
                + "&payment_method=" + token + "&confirm=" + confirm + "&capture_method=" + (manual ? "manual" : "automatic");
    }
    private String adyen(String reference) {
        return new Document("merchantAccount", "merchant-demo").append("reference", reference)
                .append("amount", new Document("value", 2500).append("currency", "EUR"))
                .append("paymentMethod", new Document("type", "scheme").append("storedPaymentMethodId", "pm_funded")).toJson();
    }
    private String paypal(String reference) {
        return new Document("intent", "CAPTURE").append("purchase_units", List.of(
                new Document("custom_id", reference).append("amount",
                        new Document("value", "25.00").append("currency_code", "EUR")))).toJson();
    }
    private long effects(String kind, String id, String type) {
        return db(kind).getCollection("ledger").countDocuments(new Document("paymentId", id).append("type", type));
    }
    private void due(String kind, String collection, String id) {
        db(kind).getCollection(collection).updateOne(eq("_id", id),
                new Document("$set", new Document("nextAttemptAt", new Date(0))));
    }

    @Test void stripeAuthorisationAndCaptureAreDifferentAndReplayIsFrozen() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = stripe(key, "pm_funded", true, true);
        var created = call("stripe", "POST", "/v1/payment_intents", key, body, true);
        assertEquals(200, created.code);
        assertEquals("requires_capture", created.body.getString("status"));
        String id = created.body.getString("id");
        assertEquals(1, effects("stripe", id, "RESERVE"));
        assertEquals(0, effects("stripe", id, "CAPTURE"));
        var capture = call("stripe", "POST", "/v1/payment_intents/" + id + "/capture", "capture-" + key, "", true);
        assertEquals("succeeded", capture.body.getString("status"));
        assertEquals(1, effects("stripe", id, "CAPTURE"));
        var replay = call("stripe", "POST", "/v1/payment_intents", key, body, true);
        assertEquals(created.body, replay.body);
        var current = call("stripe", "GET", "/v1/payment_intents/" + id, null, null, false);
        assertEquals("succeeded", current.body.getString("status"));
        assertEquals(409, call("stripe", "POST", "/v1/payment_intents", key,
                body.replace("2500", "2600"), true).code);
    }

    @Test void declineDoesNotReserveOrCaptureFunds() throws Exception {
        var reply = call("stripe", "POST", "/v1/payment_intents", UUID.randomUUID().toString(),
                stripe("low-funds", "pm_low", true, false), true);
        assertEquals(402, reply.code);
        Document error = reply.body.get("error", Document.class);
        assertEquals("insufficient_funds", error.getString("decline_code"));
        String id = error.get("payment_intent", Document.class).getString("id");
        assertEquals(0, effects("stripe", id, "RESERVE"));
        assertEquals(0, effects("stripe", id, "CAPTURE"));
    }

    @Test void lostResponseCanBeRecoveredWithoutSecondCharge() throws Exception {
        String reference = "lost-" + UUID.randomUUID();
        call("stripe", "PUT", "/__sim/scenarios/" + reference, null,
                "{\"loseResponse\":true,\"transientFailures\":0,\"delayMs\":0}", false);
        String body = stripe(reference, "pm_funded", true, false);
        var first = call("stripe", "POST", "/v1/payment_intents", reference, body, true);
        assertEquals(504, first.code);
        var second = call("stripe", "POST", "/v1/payment_intents", reference, body, true);
        assertEquals(200, second.code);
        assertEquals("succeeded", second.body.getString("status"));
        assertEquals(1, effects("stripe", second.body.getString("id"), "CAPTURE"));
    }

    @Test void concurrentDuplicatesProduceOneFinancialEffect() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(() -> call("stripe", "POST", "/v1/payment_intents",
                    key, stripe(key, "pm_funded", true, false), true));
            Set<String> ids = new HashSet<>();
            for (var future : executor.invokeAll(tasks)) {
                Reply reply = future.get();
                assertTrue(reply.code == 200 || reply.code == 409, reply.toString());
                if (reply.code == 200) ids.add(reply.body.getString("id"));
            }
            assertEquals(1, ids.size());
            assertEquals(1, effects("stripe", ids.iterator().next(), "CAPTURE"));
        }
    }

    @Test void adyenRetriesDurablyAndThenAuthorises() throws Exception {
        String key = UUID.randomUUID().toString();
        call("adyen", "PUT", "/__sim/scenarios/" + key, null,
                "{\"transientFailures\":2,\"loseResponse\":false,\"delayMs\":0}", false);
        var created = call("adyen", "POST", "/v72/payments", key, adyen(key), false);
        assertEquals("Pending", created.body.getString("resultCode"));
        String id = created.body.getString("pspReference");
        var service = apps.get("adyen").getBean(PaymentService.class);
        for (int i = 0; i < 3; i++) { due("adyen", "payments", id); service.processClaim(service.claim()); }
        Document payment = service.get(id);
        assertEquals("AUTHORISED", payment.getString("state"));
        assertEquals(3, payment.getInteger("attempts"));
        assertEquals(1, effects("adyen", id, "RESERVE"));
        assertEquals(0, effects("adyen", id, "CAPTURE"));
    }

    @Test void exhaustedTransientRetriesRequireReviewRatherThanInventingADecline() throws Exception {
        String key = UUID.randomUUID().toString();
        call("adyen", "PUT", "/__sim/scenarios/" + key, null,
                "{\"transientFailures\":10,\"loseResponse\":false,\"delayMs\":0}", false);
        var created = call("adyen", "POST", "/v72/payments", key, adyen(key), false);
        String id = created.body.getString("pspReference");
        var service = apps.get("adyen").getBean(PaymentService.class);
        for (int i = 0; i < 3; i++) { due("adyen", "payments", id); service.processClaim(service.claim()); }
        assertEquals("REVIEW_REQUIRED", service.get(id).getString("state"));
        assertEquals(0, effects("adyen", id, "RESERVE"));
    }

    @Test void adyenCaptureIsAcceptedThenCompletedInBackground() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = call("adyen", "POST", "/v72/payments", key, adyen(key), false).body.getString("pspReference");
        var service = apps.get("adyen").getBean(PaymentService.class);
        service.processClaim(service.claim());
        String request = new Document("merchantAccount", "merchant-demo").append("reference", key + "-capture")
                .append("amount", new Document("value", 2500).append("currency", "EUR")).toJson();
        Reply accepted = call("adyen", "POST", "/v72/payments/" + id + "/captures", "cap-" + key, request, false);
        assertEquals(201, accepted.code);
        assertEquals("received", accepted.body.getString("status"));
        assertNotEquals(id, accepted.body.getString("pspReference"));
        assertEquals(0, effects("adyen", id, "CAPTURE"));
        service.processClaim(service.claim());
        assertEquals("CAPTURED", service.get(id).getString("state"));
        assertEquals(1, effects("adyen", id, "CAPTURE"));
        assertEquals(accepted.body, call("adyen", "POST", "/v72/payments/" + id + "/captures", "cap-" + key, request, false).body);
    }

    @Test void competingPaymentsCannotOverdrawTheAccount() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = UUID.randomUUID().toString();
                tasks.add(() -> call("stripe", "POST", "/v1/payment_intents", key,
                        stripe(key, "pm_usd", true, false).replace("2500", "75000").replace("currency=eur", "currency=usd"), true));
            }
            List<Integer> statuses = new ArrayList<>();
            for (var future : executor.invokeAll(tasks)) statuses.add(future.get().code);
            assertEquals(1, statuses.stream().filter(code -> code == 200).count());
            assertEquals(1, statuses.stream().filter(code -> code == 402).count());
            assertEquals(25000L, db("stripe").getCollection("accounts").find(eq("_id", "pm_usd")).first().getLong("available"));
            assertEquals(75000L, db("stripe").getCollection("accounts").find(eq("_id", "merchant_USD")).first().getLong("available"));
        }
    }

    @Test void staleWorkerCannotApplyEffectAfterNewWorkerClaimsLease() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = call("adyen", "POST", "/v72/payments", key, adyen(key), false).body.getString("pspReference");
        var service = apps.get("adyen").getBean(PaymentService.class);
        Document oldClaim = service.claim();
        db("adyen").getCollection("payments").updateOne(eq("_id", id),
                new Document("$set", new Document("leaseUntil", new Date(0))));
        Document newClaim = service.claim();
        service.processClaim(oldClaim);
        assertEquals(0, effects("adyen", id, "RESERVE"));
        service.processClaim(newClaim);
        assertEquals(1, effects("adyen", id, "RESERVE"));
    }

    @Test void webhookRetryRetainsEventIdentityAndSignature() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = call("stripe", "POST", "/v1/payment_intents", key,
                stripe(key, "pm_funded", true, true), true).body.getString("id");
        // Isolate this delivery from event jobs created by other test cases.
        db("stripe").getCollection("webhooks").updateMany(new Document(),
                new Document("$set", new Document("nextAttemptAt", new Date(Long.MAX_VALUE))));
        Document event = db("stripe").getCollection("webhooks").find(eq("paymentId", id)).first();
        due("stripe", "webhooks", event.getString("_id"));
        var delivery = apps.get("stripe").getBean(WebhookDelivery.class);
        delivery.deliver(delivery.claim());
        Document first = db("stripe").getCollection("webhooks").find(eq("_id", event.getString("_id"))).first();
        assertEquals("PENDING", first.getString("state"));
        due("stripe", "webhooks", event.getString("_id"));
        delivery.deliver(delivery.claim());
        Document second = db("stripe").getCollection("webhooks").find(eq("_id", event.getString("_id"))).first();
        assertEquals("DELIVERED", second.getString("state"));
        assertEquals(2, second.getInteger("attempts"));
        assertEquals(deliveredIds.get(0), deliveredIds.get(1));
        assertTrue(signaturesValid.stream().allMatch(Boolean::booleanValue));
    }

    @Test void paymentAndIdempotencySurviveApplicationRestart() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = stripe(key, "pm_funded", false, true);
        var before = call("stripe", "POST", "/v1/payment_intents", key, body, true);
        apps.get("stripe").close();
        startApp("stripe");
        var after = call("stripe", "POST", "/v1/payment_intents", key, body, true);
        assertEquals(before.body.getString("id"), after.body.getString("id"));
    }

    @Test void transactionRollsBackMultipleCollectionsTogether() {
        ProviderStore store = apps.get("stripe").getBean(ProviderStore.class);
        String marker = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> store.transaction(session -> {
            store.collection("payments").insertOne(session, new Document("_id", marker));
            store.collection("ledger").insertOne(session, new Document("_id", marker).append("operation", marker));
            throw new IllegalStateException("Injected before commit");
        }));
        assertNull(store.payment(marker));
        assertEquals(0, db("stripe").getCollection("ledger").countDocuments(eq("_id", marker)));
    }

    @Test void missingAuthenticationAndMalformedInputAreRejected() throws Exception {
        var unauthenticated = http.send(HttpRequest.newBuilder(java.net.URI.create(urls.get("stripe") + "/v1/payment_intents/no"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthenticated.statusCode());
        assertEquals(400, call("stripe", "POST", "/v1/payment_intents", "invalid",
                stripe("invalid", "pm_funded", false, true).replace("2500", "-1"), true).code);
        assertEquals(404, call("stripe", "GET", "/v1/payment_intents/missing", null, null, false).code);
    }
}
