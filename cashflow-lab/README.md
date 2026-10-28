# PayMesh Part 4

A merchant submits a payment request and receives a durable acknowledgement.
Retrying the same request with the same merchant-scoped idempotency key returns
the original receipt without creating another payment.

This checkout contains the payment-acceptance backend, JDBC persistence,
transactional outbox and optional AWS SQS publisher. AWS and Kafka connection
configuration are retained. Kafka outcomes, provider execution and frontend
integration are later lessons.

## Start reading here

Follow one request through these files under `src/main/java/com/practice/cashflow`:

1. `adapter/in/web/PaymentSubmissionRequest.java`: the JSON fields and their validation.
2. `adapter/in/web/PaymentSubmissionController.java`: HTTP input, configured merchant and receipt.
3. `application/port/in/AcceptPaymentUseCase.java`: the application operation.
4. `application/AcceptPaymentService.java`: validate the command, then call the store.
5. `application/port/out/PaymentAcceptanceStore.java`: atomic acceptance and status lookup contracts.
6. `configuration/DatabaseConfiguration.java`: Hikari connection pool and JDBC template.
7. `configuration/PaymentAcceptanceJdbcConfiguration.java`: the shared DataSource and READ_COMMITTED transaction.
8. `adapter/out/persistence/JdbcPaymentAcceptanceStore.java`: uniqueness, fingerprint, replay and both writes.
9. `adapter/in/scheduling/OutboxDeliveryScheduler.java`: after-commit wake-up, executor and recovery scan.
10. `application/OutboxPublisher.java`: claim, send, then mark published or schedule retry.
11. `adapter/out/persistence/JdbcOutboxStore.java`: short claim transactions, leases and ownership checks.
12. `adapter/out/messaging/SqsOutboxSender.java`: queue lookup and acknowledged send.

`PaymentExceptionHandler` maps failures to safe HTTP responses. A stored creation
timestamp is not an exact transaction-commit timestamp. Acceptance does not prove
authorisation, capture or settlement. Successful sending followed by a failed
database update can produce duplicate delivery; downstream processing must handle it.

## API and demonstration

The contract is `src/main/resources/openapi/openapi.yaml`.

- `POST /api/v1/payments`: accept a new request or replay its original receipt.
- `GET /api/v1/payments/{paymentId}`: read the linked payment status.

Both first acceptance and identical replay return **202**. The
`Idempotency-Replayed` header distinguishes them. Changed business fields under
the same key return **409**. The merchant comes from local configuration;
authentication is not implemented.

Import `postman/PayMesh-Part4.postman_collection.json`. Its eight requests cover
acceptance, replay, conflict, status and invalid input. The transactional-outbox
visuals remain in `diagrams/transactional-outbox`.

## Run locally

The Maven target is Java 21; framework and SDK versions remain unchanged.
The existing `mvn-local.sh` selects IntelliJ's bundled JDK and the project's Maven
cache. To choose an installed compatible JDK, set `CASHFLOW_JAVA_HOME` explicitly.

Database settings come from `DB_URL`, `DB_USER`, `DB_PASSWORD`, or the ignored
`config/database-local.properties`. Inspect that local file when deciding which
configuration takes precedence. Never commit its credentials.

The SQL files in `src/main/resources/db/payment-acceptance` are **not automatically
applied**. For a new disposable database, apply V001 and then V002. Inspect an
existing schema before applying anything; V001 creates tables and must not be
rerun over an existing schema.

Acceptance only, on port 18081:

```sh
./mvn-local.sh spring-boot:run -Dspring-boot.run.profiles=db,acceptance
```

IntelliJ also has a `PayMesh Part 4 Acceptance` run configuration.

To publish pending outbox rows, explicitly enable delivery:

```sh
./mvn-local.sh spring-boot:run -Dspring-boot.run.profiles=db,acceptance,aws,outbox
```

Use the intended development AWS profile and queue. Enabling these profiles can
send existing pending rows. `AwsConfiguration` uses the SDK credentials provider
chain; no keys belong in application properties. SQS delivery does not require
the Kafka profile.

`KafkaConfiguration`, `application-kafka.properties`, and
`infra/compose.kafka.yaml` are retained for an optional local connectivity lesson.
There is no connected PayMesh execution consumer or Kafka outcome pipeline here.
The S3 and STS clients also remain as configuration for later lessons, not as
implemented report-upload or authentication endpoints.

## Verification

```sh
./mvn-local.sh test
```

The default suite tests types, validation, HTTP wiring, safe errors and SQS request
construction with mocks. `Part4BoundaryTest` uses a real Spring application context
with a mocked DataSource and acceptance store; it does not prove database durability.

For real PostgreSQL behaviour with an available Docker engine:

```sh
./mvn-local.sh -Dcashflow.acceptance-postgres-tests=true -Dtest=PaymentAcceptancePostgresTest test
```

That test starts its own disposable PostgreSQL container and applies both SQL
files there. It covers rollback, concurrent acceptance, ownership and retry cases.

The opt-in `KafkaConnectivityTest` requires a running local broker and creates a
synthetic smoke-test topic. Run it with `-Dcashflow.live-kafka=true
-Dtest=KafkaConnectivityTest`. It is separate from payment integration. Default
tests do not contact live AWS or Kafka.

