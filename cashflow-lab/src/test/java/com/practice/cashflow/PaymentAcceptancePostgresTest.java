package com.practice.cashflow;

import com.practice.cashflow.adapter.out.persistence.*;
import com.practice.cashflow.application.*;
import com.practice.cashflow.application.port.out.*;
import com.practice.cashflow.domain.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP and JDBC tests against a disposable PostgreSQL container. */
@EnabledIfSystemProperty(named = "cashflow.acceptance-postgres-tests", matches = "true")
@TestMethodOrder(MethodOrderer.MethodName.class)
class PaymentAcceptancePostgresTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.9")
            .withDatabaseName("paymesh_review").withUsername("review")
            .withPassword("isolated-test-only");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JdbcPaymentAcceptanceStore acceptance;
    static JdbcOutboxStore outbox;
    static CommitObserver observer;
    static final String MERCHANT = "10000000-0000-4000-8000-000000000001";
    static final UUID M = UUID.fromString(MERCHANT);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static String base;
    static final String BODY = """
        {"amountMinor":2500,"currency":"EUR","provider":"STRIPE","paymentMethodToken":"pm_funded","merchantReference":"order-1"}
        """;
    static class CommitObserver {
        final List<OutboxReady> committed = new CopyOnWriteArrayList<>();
        @TransactionalEventListener public void committed(OutboxReady event) { committed.add(event); }
    }
    @TestConfiguration static class EvidenceConfiguration {
        @Bean CommitObserver commitObserver() { return new CommitObserver(); }
    }
    @BeforeAll static void start() {
        POSTGRES.start();
        context = new SpringApplication(CashflowApplication.class,EvidenceConfiguration.class).run(
                "--spring.profiles.active=db,acceptance", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.database.url="+POSTGRES.getJdbcUrl(),
                "--app.database.username="+POSTGRES.getUsername(), "--app.database.password="+POSTGRES.getPassword());
        jdbc=context.getBean(JdbcTemplate.class);
        assertEquals("paymesh_review",jdbc.queryForObject("select current_database()",String.class));
        // The schema is created only inside the new disposable container.
        var ds=context.getBean(HikariDataSource.class);
        new ResourceDatabasePopulator(new ClassPathResource("db/payment-acceptance/V001__payment_acceptance.sql"),
                new ClassPathResource("db/payment-acceptance/V002__outbox_claims.sql")).execute(ds);
        acceptance=context.getBean(JdbcPaymentAcceptanceStore.class);
        outbox=new JdbcOutboxStore(context.getBean(NamedParameterJdbcTemplate.class),context.getBean(TransactionTemplate.class));
        observer=context.getBean(CommitObserver.class);
        base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort()+"/api/v1/payments";
    }
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE paymesh_acceptance.outbox_messages, paymesh_acceptance.payment_requests");
        observer.committed.clear();
    }
    @AfterAll static void stop() {
        try { if(context!=null) context.close(); } finally { POSTGRES.stop(); }
    }
    static AcceptPaymentCommand command(String merchant,String key,long amount) {
        return new AcceptPaymentCommand(merchant,key,amount,PaymentCurrency.EUR,PaymentProvider.STRIPE,"pm_funded","order-1");
    }
    static HttpResponse<String> post(String key,String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").header("Idempotency-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> get(String suffix) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base+suffix)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }
    static int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM paymesh_acceptance."+table,Integer.class); }
    static void expire() { jdbc.update("UPDATE paymesh_acceptance.outbox_messages SET lease_until=clock_timestamp()-interval '1 second' WHERE claim_token IS NOT NULL"); }
    @Test void a_validRequestReplayStatusAndConflict() throws Exception {
        var first=post("evidence-1",BODY); assertEquals(202,first.statusCode(),first.body());
        var receipt=json(first); var id=receipt.get("paymentId").asString();
        assertEquals("false",first.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("/api/v1/payments/"+id,first.headers().firstValue("Location").orElseThrow());
        var replay=post("evidence-1",BODY);assertEquals(202,replay.statusCode());assertEquals(receipt,json(replay));
        assertEquals("true",replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(1,count("payment_requests"));assertEquals(1,count("outbox_messages"));assertEquals(1,observer.committed.size());
        var status=get("/"+id);assertEquals(200,status.statusCode());assertEquals(2500,json(status).get("amountMinor").asLong());
        assertFalse(status.body().contains("pm_funded"));assertFalse(status.body().contains("idempotencyKey"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
        var conflict=post("evidence-1",BODY.replace("2500","2600"));assertEquals(409,conflict.statusCode());
        System.out.println("HTTP_DB_EVIDENCE first="+first.body()+" replaySame=true rows=1/1 status="+status.body()+" conflict="+conflict.body());
    }
    @Test void b_tenantScopedKeysAndStatus() throws Exception {
        var first=acceptance.acceptAtomically(command(MERCHANT,"shared",2500));
        var other=acceptance.acceptAtomically(command("20000000-0000-4000-8000-000000000002","shared",2500));
        assertNotEquals(first.paymentId(),other.paymentId());assertEquals(2,count("payment_requests"));
        assertTrue(acceptance.find(M,other.paymentId()).isEmpty());assertEquals(404,get("/"+other.paymentId()).statusCode());
        assertEquals(404,get("/"+UUID.randomUUID()).statusCode());assertEquals(400,get("/not-a-uuid").statusCode());
    }
    @Test void c_outboxFailureRollsBackPaymentAndDoesNotSignal() {
        jdbc.execute("ALTER TABLE paymesh_acceptance.outbox_messages ADD CONSTRAINT force_review_failure CHECK (message_type <> 'payment.execute.v1')");
        try {
            assertThrows(DataIntegrityViolationException.class,()->acceptance.acceptAtomically(command(MERCHANT,"rollback",2500)));
            assertEquals(0,count("payment_requests"));assertEquals(0,count("outbox_messages"));assertEquals(0,observer.committed.size());
        } finally { jdbc.execute("ALTER TABLE paymesh_acceptance.outbox_messages DROP CONSTRAINT force_review_failure"); }
    }
    @Test void d_concurrentIdenticalRequestsHaveOneWinner() throws Exception {
        int n=8;var ready=new CountDownLatch(n);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(n)) {
            var tasks=new ArrayList<Future<PaymentAcceptance>>();
            for(int i=0;i<n;i++)tasks.add(pool.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return acceptance.acceptAtomically(command(MERCHANT,"concurrent",2500));}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            var receipts=new ArrayList<PaymentAcceptance>();for(var f:tasks)receipts.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(1,receipts.stream().filter(r->!r.replayed()).count());
            assertEquals(1,receipts.stream().map(PaymentAcceptance::paymentId).distinct().count());
            assertEquals(1,receipts.stream().map(PaymentAcceptance::acceptedAt).distinct().count());
            assertEquals(1,count("payment_requests"));assertEquals(1,count("outbox_messages"));assertEquals(1,observer.committed.size());
        }
    }
    @Test void e_strictBodyAndHeaderValidation() throws Exception {
        for(String bad:List.of(BODY.replace("2500","0"),BODY.replace("2500","2500.5"),BODY.replace("2500","\"2500\""),
                BODY.replace("2500","null"),BODY.replace("EUR","eur"),BODY.replace("STRIPE","UNKNOWN"),
                BODY.replace("pm_funded","bad-token"),BODY.replace("\"amountMinor\":2500,",""),BODY.replace("{","{\"extra\":1,"))) {
            var r=post("bad-input",bad);assertEquals(400,r.statusCode(),bad+" -> "+r.body());
        }
        var missing=HTTP.send(HttpRequest.newBuilder(URI.create(base)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,missing.statusCode());
        var duplicate=HTTP.send(HttpRequest.newBuilder(URI.create(base)).header("Content-Type","application/json")
                .header("Idempotency-Key","one").header("Idempotency-Key","two")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,duplicate.statusCode());assertEquals(0,count("payment_requests"));
    }
    @Test void f_claimOwnershipAndExpiry() {
        acceptance.acceptAtomically(command(MERCHANT,"claim",2500));
        var first=outbox.claimNext().orElseThrow();assertTrue(outbox.claimNext().isEmpty());
        expire();var second=outbox.claimNext().orElseThrow();assertEquals(first.messageId(),second.messageId());
        assertNotEquals(first.claimToken(),second.claimToken());assertFalse(outbox.markPublished(first));assertTrue(outbox.markPublished(second));
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
    }
    @Test void g_ackThenDbFailureRedeliversSameMessage() {
        acceptance.acceptAtomically(command(MERCHANT,"ack-lost",2500));
        var unstable=spy(outbox);doThrow(new DataAccessResourceFailureException("controlled review failure"))
                .doCallRealMethod().when(unstable).markPublished(any());
        var delivered=new ArrayList<UUID>();var publisher=new OutboxPublisher(unstable,c->delivered.add(c.messageId()));
        assertThrows(DataAccessResourceFailureException.class,publisher::publishOne);
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
        expire();assertTrue(publisher.publishOne());assertEquals(2,delivered.size());assertEquals(delivered.getFirst(),delivered.getLast());
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
    }
    @Test void h_sendFailuresRetryThenPark() {
        acceptance.acceptAtomically(command(MERCHANT,"retry",2500));
        var publisher=new OutboxPublisher(outbox,c->{throw new IllegalStateException("controlled network failure");});
        for(int i=1;i<=8;i++) {
            assertTrue(publisher.publishOne());assertEquals(i,jdbc.queryForObject("SELECT publish_attempts FROM paymesh_acceptance.outbox_messages",Integer.class));
            if(i<8){assertTrue(outbox.claimNext().isEmpty());jdbc.update("UPDATE paymesh_acceptance.outbox_messages SET available_at=clock_timestamp()-interval '1 second'");}
        }
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
        assertTrue(outbox.claimNext().isEmpty());
    }
    @Test void i_crashOnFinalClaimIsParkedAfterLeaseExpiry() {
        acceptance.acceptAtomically(command(MERCHANT,"final-crash",2500));
        jdbc.update("UPDATE paymesh_acceptance.outbox_messages SET publish_attempts=7");
        var claim=outbox.claimNext().orElseThrow();assertEquals(8,claim.attempt());expire();
        assertTrue(outbox.claimNext().isEmpty());assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM paymesh_acceptance.outbox_messages",String.class));
    }
    @Test void j_concurrentClaimersDoNotOwnSameLease() throws Exception {
        acceptance.acceptAtomically(command(MERCHANT,"two-workers",2500));var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{gate.await();return outbox.claimNext();});
            var b=executor.submit(()->{gate.await();return outbox.claimNext();});gate.countDown();
            assertEquals(1,(a.get(10,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(10,TimeUnit.SECONDS).isPresent()?1:0));
        }
    }
    @Test void k_afterCommitOnlyObserverSeesDurableWork() {
        acceptance.acceptAtomically(command(MERCHANT,"signal",2500));
        assertEquals(1,observer.committed.size());assertEquals(1,count("outbox_messages"));
        acceptance.acceptAtomically(command(MERCHANT,"signal",2500));assertEquals(1,observer.committed.size());
    }
    @Test void z_unavailableDatabaseReturns503WithSameKeyAdvice() throws Exception {
        context.getBean(HikariDataSource.class).close();
        var response=post("database-offline",BODY);assertEquals(503,response.statusCode(),response.body());
        assertTrue(response.body().contains("same idempotency key"));assertFalse(response.body().contains("isolated-review-only"));
    }
}
