# PayMesh — clean architecture and provider simulators

A Java payment-engineering learning project: merchant-scoped idempotent acceptance,
PostgreSQL transactions, an outbox with optional AWS SQS delivery, and three
independent local provider simulators implemented with Spring Boot and Quarkus.

The public snapshot contains the earlier PayMesh implementation. It is separate
from the newer Java 25 interview-practice project.

## Repository layout

| Directory | Implemented scope |
| --- | --- |
| [`cashflow-lab`](cashflow-lab/README.md) | Payment acceptance, status lookup, JDBC storage, transactional outbox, optional SQS publisher |
| [`providers`](providers/README.md) | Stripe-inspired, Adyen-inspired and PayPal-inspired local simulators; MongoDB state, idempotency, simulated funds and webhook delivery |
| [`cashflow-lab/diagrams/transactional-outbox`](cashflow-lab/diagrams/transactional-outbox) | Request flow, transaction boundaries, failure windows and recovery diagrams |
| [`providers/demo`](providers/demo/README.md) | Synthetic scenarios and event examples |

## Architecture to explore

In the acceptance service, HTTP controllers call input ports; application services
coordinate use cases through output ports. JDBC and SQS adapters implement those
ports. Configuration wires the concrete dependencies. Follow:

`PaymentSubmissionController -> AcceptPaymentUseCase -> AcceptPaymentService -> PaymentAcceptanceStore -> JdbcPaymentAcceptanceStore`

For delivery, follow:

`OutboxDeliveryScheduler -> OutboxPublisher -> OutboxStore / OutboxSender -> JDBC / SQS adapters`

Provider rules are shared between the Spring and Quarkus applications. The
provider core has no Spring/Quarkus annotations, but its application layer currently
uses MongoDB driver types and a concrete store. It is not a fully persistence-independent
hexagonal implementation; that coupling is an explicit remaining design limitation.

## Start with tests

Prerequisites: JDK 21 or a compatible newer JDK and Maven 3.9 or newer.
The sources target Java 21. Node.js/npm are needed only for contract validation;
Docker is needed for the optional database integration tests and local providers.

```sh
mvn test
npm --prefix providers/contract-tests ci
npm --prefix providers/contract-tests test
```

The default Java suite does not contact real AWS or provider systems. It excludes
opt-in PostgreSQL/Kafka checks and provider integration tests. A passing default
suite therefore does not prove end-to-end payment processing.

For PostgreSQL transaction and concurrency tests, with Docker available:

```sh
mvn -f cashflow-lab/pom.xml -Dcashflow.acceptance-postgres-tests=true -Dtest=PaymentAcceptancePostgresTest test
```

For provider integration tests, start the local MongoDB replica set, then run:

```sh
docker compose -f providers/compose.yaml up -d mongo
mvn -f providers/pom.xml -Pintegration verify
```

These provider tests use dedicated `provider_it_*` databases and clean those test
databases afterward. See each module's README before running applications or
optional integration tests. Configure `JAVA_HOME` explicitly if several JDKs are installed.

## Implemented guarantees and limits

- Payment and outbox writes use one PostgreSQL transaction. Replaying a matching
  merchant/key returns the stored receipt; changed business fields conflict.
- An SQS acknowledgement is publication evidence, not proof of money movement.
  Sending successfully and failing to save publication state permits duplicate delivery.
- Provider simulations demonstrate approval, authorisation, capture, retries,
  uncertain responses and signed local webhooks with synthetic funds.
- The acceptance service and provider simulators are **not connected end to end**.
  Provider adapters, outcome ingestion and Kafka integration remain future work.
- Acceptance uses a configured demonstration merchant; production authentication
  and tenant identity are not implemented. Provider API keys are fake local fixtures.
- This is not a live payment service, certified provider implementation or production ledger.

## Local configuration

Real credentials, local database configuration, build outputs, logs and recordings
are excluded. Copy `cashflow-lab/config/database-local.properties.example` only
when configuring a local database, or use `DB_URL`, `DB_USER` and `DB_PASSWORD`.
AWS clients use the SDK credentials-provider chain. Never put real keys in source.

Enabling the `aws,outbox` profiles can publish existing pending records to the
configured queue. Use only an explicitly selected development environment.
No AWS infrastructure is created by the default test command.

See [verification](VERIFICATION.md) for the checks run for this public snapshot.
