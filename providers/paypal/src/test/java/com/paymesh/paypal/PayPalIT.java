package com.paymesh.paypal;

import com.mongodb.client.*;
import com.paymesh.provider.application.WebhookDelivery;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged Quarkus service over HTTP and inspects committed MongoDB evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayPalIT {
    private static final String URI = System.getProperty("provider.test.mongo",
            "mongodb://127.0.0.1:27028/?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=5000");
    private final String database = "provider_it_quarkus_" + UUID.randomUUID().toString().replace("-", "");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final Map<String, Boolean> signatures = new ConcurrentHashMap<>();
    private MongoClient mongo;
    private MongoDatabase db;
    private Process process;
    private String url;
    private HttpServer receiver;

    @BeforeAll void start() throws Exception {
        mongo = MongoClients.create(URI); db = mongo.getDatabase(database);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String id = exchange.getRequestHeaders().getFirst("X-Simulator-Event-Id");
            String time = exchange.getRequestHeaders().getFirst("X-Simulator-Timestamp");
            signatures.merge(id, WebhookDelivery.sign("test-webhook", time + "." + body)
                    .equals(exchange.getRequestHeaders().getFirst("X-Simulator-Signature")), (a,b) -> a && b);
            int attempt = deliveries.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 204, -1); exchange.close();
        });
        receiver.start(); startApp();
    }
    private void startApp() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        url = "http://127.0.0.1:" + port;
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dquarkus.http.port=" + port, "-Dprovider.database=" + database, "-Dprovider.mongo-uri=" + URI,
                "-Dprovider.api-key=test-paypal", "-Dprovider.admin-key=test-admin", "-Dprovider.retry-base-ms=1",
                "-Dprovider.webhook-secret=test-webhook",
                "-Dprovider.webhook-url=http://127.0.0.1:" + receiver.getAddress().getPort() + "/events",
                "-jar", "target/quarkus-app/quarkus-run.jar")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("target/paypal-it.log").toFile())).start();
        waitFor(() -> {
            if (!process.isAlive()) throw new AssertionError("Quarkus exited; inspect target/paypal-it.log");
            try { return http.send(HttpRequest.newBuilder(java.net.URI.create(url + "/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
            catch (Exception notReady) { return false; }
        }, 30);
    }
    private void stopApp() throws Exception {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    @AfterAll void stop() throws Exception {
        try { stopApp(); }
        finally {
            if (db != null && database.startsWith("provider_it_quarkus_")) db.drop();
            if (mongo != null) mongo.close(); if (receiver != null) receiver.stop(0); http.close();
        }
    }
    private record Reply(int code, Document body) {}
    private Reply call(String method, String path, String key, String body, String funding) throws Exception {
        var request = HttpRequest.newBuilder(java.net.URI.create(url + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header(path.startsWith("/__sim/") ? "X-Simulator-Admin-Key" : "Authorization",
                        path.startsWith("/__sim/") ? "test-admin" : "Bearer test-paypal");
        if (key != null) request.header("PayPal-Request-Id", key);
        if (funding != null) request.header("X-Simulator-Payment-Method", funding);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), Document.parse(response.body()));
    }
    private String body(String reference) {
        return new Document("intent", "CAPTURE").append("purchase_units", List.of(new Document("custom_id", reference)
                .append("amount", new Document("currency_code", "EUR").append("value", "25.00")))).toJson();
    }
    private String create(String reference, String funding) throws Exception {
        var result = call("POST", "/v2/checkout/orders", UUID.randomUUID().toString(), body(reference), funding);
        assertEquals(201, result.code, result.body.toJson()); return result.body.getString("id");
    }
    private void approve(String id) throws Exception {
        assertEquals("APPROVED", call("POST", "/__sim/orders/" + id + "/approve", UUID.randomUUID().toString(), "{}", null).body.getString("status"));
    }
    private long effects(String id, String type) {
        return db.getCollection("ledger").countDocuments(new Document("paymentId", id).append("type", type));
    }
    private static void waitFor(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for durable result");
            Thread.sleep(100);
        }
    }

    @Test void approvalCaptureAndLatestStateReplay() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        var created = call("POST", "/v2/checkout/orders", key, body, null);
        assertEquals(201, created.code); String id = created.body.getString("id");
        assertEquals("CREATED", created.body.getString("status"));
        assertEquals(409, call("POST", "/v2/checkout/orders/" + id + "/capture", "c-" + key, "{}", null).code);
        approve(id); assertEquals(0, effects(id, "RESERVE"));
        var captured = call("POST", "/v2/checkout/orders/" + id + "/capture", "c-" + key, "{}", null);
        assertEquals(201, captured.code); assertEquals("COMPLETED", captured.body.getString("status"));
        assertEquals("COMPLETED", call("POST", "/v2/checkout/orders", key, body, null).body.getString("status"));
        assertEquals(409, call("POST", "/v2/checkout/orders", key, body.replace("25.00", "26.00"), null).code);
        assertEquals(1, effects(id, "CAPTURE"));
    }
    @Test void insufficientFundsDoNotTransferMoney() throws Exception {
        String id = create("low-" + UUID.randomUUID(), "pm_low"); approve(id);
        var result = call("POST", "/v2/checkout/orders/" + id + "/capture", UUID.randomUUID().toString(), "{}", null);
        assertEquals(422, result.code); assertEquals("UNPROCESSABLE_ENTITY", result.body.getString("name"));
        assertEquals(0, effects(id, "CAPTURE")); assertEquals(0, effects(id, "RESERVE"));
    }
    @Test void lostCaptureResponseRecoversUsingSameKey() throws Exception {
        String ref = UUID.randomUUID().toString();
        assertEquals(200, call("PUT", "/__sim/scenarios/" + ref, null, "{\"loseResponse\":true}", null).code);
        String id = create(ref, "pm_funded"); approve(id); String key = UUID.randomUUID().toString();
        assertEquals(504, call("POST", "/v2/checkout/orders/" + id + "/capture", key, "{}", null).code);
        assertEquals(201, call("POST", "/v2/checkout/orders/" + id + "/capture", key, "{}", null).code);
        assertEquals(1, effects(id, "CAPTURE"));
    }
    @Test void concurrentCaptureRequestsMakeOneFinancialEffect() throws Exception {
        String id = create("concurrent-" + UUID.randomUUID(), "pm_funded"); approve(id);
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(() -> call("POST", "/v2/checkout/orders/" + id + "/capture", key, "{}", null));
            for (Future<Reply> result : executor.invokeAll(tasks, 20, TimeUnit.SECONDS))
                assertTrue(Set.of(201, 409).contains(result.get().code));
        }
        assertEquals(201, call("POST", "/v2/checkout/orders/" + id + "/capture", key, "{}", null).code);
        assertEquals(1, effects(id, "CAPTURE"));
    }
    @Test void badInputAndUnauthorizedRequestsAreRejected() throws Exception {
        var noAuth = http.send(HttpRequest.newBuilder(java.net.URI.create(url + "/v2/checkout/orders/no"))
                .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(401, noAuth.statusCode());
        assertEquals(400, call("POST", "/v2/checkout/orders", "bad", "{\"intent\":\"CAPTURE\",\"purchase_units\":[]}", null).code);
        assertEquals(400, call("POST", "/v2/checkout/orders", null, body("missing-key"), null).code);
        assertEquals(404, call("GET", "/v2/checkout/orders/missing", null, null, null).code);
        String id = create("auth-" + UUID.randomUUID(), "pm_funded");
        var wrongScope = http.send(HttpRequest.newBuilder(java.net.URI.create(url + "/__sim/orders/" + id + "/approve"))
                .header("Authorization", "Bearer test-paypal").header("PayPal-Request-Id", "test-key")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(401, wrongScope.statusCode());
    }
    @Test void stateAndIdempotencySurviveQuarkusRestart() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        var before = call("POST", "/v2/checkout/orders", key, body, null); assertEquals(201, before.code);
        stopApp(); startApp();
        var after = call("POST", "/v2/checkout/orders", key, body, null);
        assertEquals(201, after.code); assertEquals(before.body.getString("id"), after.body.getString("id"));
    }
    @Test void executorDeliversSignedWebhookAndRetriesReceiverFailure() throws Exception {
        String id = create("webhook-" + UUID.randomUUID(), "pm_funded"); approve(id);
        assertEquals(201, call("POST", "/v2/checkout/orders/" + id + "/capture", UUID.randomUUID().toString(), "{}", null).code);
        waitFor(() -> db.getCollection("webhooks").countDocuments(new Document("paymentId", id).append("state", "DELIVERED")) > 0, 15);
        Document event = db.getCollection("webhooks").find(new Document("paymentId", id).append("state", "DELIVERED")).first();
        assertEquals(2, event.getInteger("attempts"));
        assertEquals(2, deliveries.get(event.getString("_id")).get());
        assertTrue(signatures.get(event.getString("_id")));
    }
}
